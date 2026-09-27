package tr.com.innova.akis.execution;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;

/**
 * Re-checks and fires one due schedule (V049/V058) in its own short transaction, called per row by
 * {@link ScheduleDueScanner}. Kept as a separate {@code @Transactional} bean rather than a method on the poller
 * itself: {@code @Scheduled} methods run outside a Spring AOP proxy, so a transactional method the poller called on
 * itself directly would silently run without a transaction.
 */
@Service
class ScheduleFireService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleFireService.class);
    /** A due time older than this is treated as missed (the poller itself was down), not an on-time fire. */
    private static final Duration MISSED_THRESHOLD = Duration.ofMinutes(5);
    /** Do not fire occurrences more than this far in the future; limits accidental bursts after long downtime. */
    private static final Duration FIRE_WINDOW = Duration.ofMinutes(2);
    private static final long MAX_PENDING_QUEUE_REQUESTS = 100;

    private final JdbcClient jdbc;
    private final ExecutionStore store;
    private final ScheduleBackgroundAuthorization backgroundAuthorization;
    private final Clock clock;

    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    ScheduleFireService(JdbcClient jdbc, ExecutionStore store, ScheduleBackgroundAuthorization backgroundAuthorization,
            tools.jackson.databind.ObjectMapper objectMapper) {
        this(jdbc, store, backgroundAuthorization, objectMapper, Clock.systemUTC());
    }

    ScheduleFireService(JdbcClient jdbc, ExecutionStore store, ScheduleBackgroundAuthorization backgroundAuthorization,
            tools.jackson.databind.ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.backgroundAuthorization = backgroundAuthorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private record Due(
            long id, UUID projectUuid, UUID configuredPublicationUuid, long creatorUserId,
            String cronExpression, String timeZone, String conflictPolicy, String misfirePolicy,
            String publicationPolicy, OffsetDateTime dueAt, OffsetDateTime endsAt) {
    }

    @Transactional
    void tryFire(UUID scheduleUuid) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<Due> due = jdbc.sql("""
                        select z.id, p.uuid project_uuid, configured.uuid configured_publication_uuid, z.olusturan_kullanici_id,
                               z.cron_ifadesi, z.zaman_dilimi, z.cakisma_politikasi, z.kacirma_politikasi,
                               z.yayin_gorunum_kodu, z.sonraki_tetikleme_zamani, z.bitis_zamani
                          from akis.zamanlama z
                          join akis.proje p on p.id = z.proje_id
                          join akis.yayin configured on configured.id = z.yayin_id
                          join akis.senaryo configured_scenario on configured_scenario.id = configured.senaryo_id
                          join akis.ortam configured_environment on configured_environment.id = configured.ortam_id
                         where z.uuid = :schedule and z.durum_kodu = 'AKTIF'
                           and z.sonraki_tetikleme_zamani <= :now
                           and z.sonraki_tetikleme_zamani <= :fireDeadline
                           and z.arsivlenme_zamani is null
                         for update of z
                """)
                .param("schedule", scheduleUuid)
                .param("now", now)
                .param("fireDeadline", now.plus(FIRE_WINDOW))
                .query((r, n) -> new Due(
                        r.getLong("id"), r.getObject("project_uuid", UUID.class),
                        r.getObject("configured_publication_uuid", UUID.class),
                        r.getLong("olusturan_kullanici_id"), r.getString("cron_ifadesi"), r.getString("zaman_dilimi"),
                        r.getString("cakisma_politikasi"), r.getString("kacirma_politikasi"),
                        r.getString("yayin_gorunum_kodu"), r.getObject("sonraki_tetikleme_zamani", OffsetDateTime.class),
                        r.getObject("bitis_zamani", OffsetDateTime.class)))
                .optional();
        if (due.isEmpty()) return;
        Due d = due.get();

        if (d.endsAt() != null && !d.endsAt().toInstant().isAfter(clock.instant())) {
            suspendExpiredWindow(scheduleUuid);
            auditDecision(scheduleUuid, d, "WINDOW_EXPIRED", null);
            return;
        }

        Actor actor = store.findActiveActor(d.creatorUserId()).orElse(null);
        if (actor == null) {
            LOG.warn("Schedule {} skipped a fire: owning user {} is no longer active.", scheduleUuid, d.creatorUserId());
            advance(scheduleUuid, d, false);
            auditDecision(scheduleUuid, d, "ACTOR_INACTIVE", null);
            return;
        }

        PublicationContext publication = resolvePublication(d);
        if ("PINNED".equals(d.publicationPolicy())
                && (publication == null || !"AKTIF".equals(publication.publicationStatus()))) {
            LOG.warn("Schedule {} auto-suspended: pinned publication {} is no longer active/approved.",
                    scheduleUuid, d.configuredPublicationUuid());
            suspendForInvalidPinnedPublication(scheduleUuid);
            auditDecision(scheduleUuid, d, "PINNED_SUSPENDED", null);
            return;
        }
        if (publication == null) {
            LOG.warn("Schedule {} skipped a fire: publication is not available.", scheduleUuid);
            advance(scheduleUuid, d, false);
            auditDecision(scheduleUuid, d, "PUBLICATION_MISSING", null);
            return;
        }

        if ("QUEUE".equals(d.conflictPolicy())
                && pendingQueueRequests(d.id()) >= MAX_PENDING_QUEUE_REQUESTS) {
            LOG.warn("Schedule {} auto-suspended: pending queue limit reached.", scheduleUuid);
            suspendForQueueLimit(scheduleUuid);
            auditDecision(scheduleUuid, d, "QUEUE_LIMIT_SUSPENDED", publication);
            return;
        }

        if ("SKIP".equals(d.conflictPolicy()) && hasActiveRun(d.id())) {
            LOG.debug("Schedule {} skipped a fire: an earlier run is still active (SKIP overlap policy).", d.id());
            advance(scheduleUuid, d, false);
            auditDecision(scheduleUuid, d, "OVERLAP_SKIPPED", publication);
            return;
        }

        boolean overdue = Duration.between(d.dueAt(), now).compareTo(MISSED_THRESHOLD) > 0;
        boolean shouldFire = !overdue || "RUN_ONCE".equals(d.misfirePolicy());
        boolean fired = shouldFire && fire(d, publication, actor);

        advance(scheduleUuid, d, fired);
        auditDecision(scheduleUuid, d, fired ? "FIRED" : shouldFire ? "PUBLICATION_INACTIVE" : "MISFIRE_SKIPPED", publication);
    }

    private void auditDecision(UUID scheduleUuid, Due due, String outcome, PublicationContext publication) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("scheduledFor", due.dueAt().toString());
        detail.put("outcome", outcome);
        detail.put("configuredPublicationUuid", due.configuredPublicationUuid().toString());
        if (publication != null) detail.put("resolvedPublicationUuid", publication.publicationUuid().toString());
        jdbc.sql("""
                insert into akis.denetim_olayi(
                    proje_id, dis_nesne_uuid, korelasyon_kodu, aktor_turu,
                    eylem_kodu, sonuc, olay_zamani, ayrinti)
                select z.proje_id, :scheduleUuid, :correlation, 'SISTEM',
                       'ZAMANLAMA_TETIKLEME', 'BASARILI', clock_timestamp(), cast(:detail as jsonb)
                  from akis.zamanlama z where z.id = :scheduleId
                """)
                .param("scheduleUuid", scheduleUuid)
                .param("correlation", "SCHEDULE:" + due.id() + ":" + due.dueAt().toInstant())
                .param("detail", detail.toString())
                .param("scheduleId", due.id())
                .update();
    }

    private PublicationContext resolvePublication(Due d) {
        if ("PINNED".equals(d.publicationPolicy())) {
            return store.lockPublication(d.projectUuid(), d.configuredPublicationUuid()).orElse(null);
        }
        return jdbc.sql("""
                        select p.id as project_id, y.id as publication_id,
                               y.uuid as publication_uuid, y.durum as durum_kodu,
                               o.risk as risk_kodu,
                               y.fiziksel_manifesto ->> 'releaseHash' as release_hash, s.plan_ozeti,
                               y.fiziksel_manifesto
                          from akis.yayin y
                          join akis.proje p on p.id = y.proje_id
                          join akis.ortam o on o.id = y.ortam_id
                          join akis.senaryo s on s.id = y.senaryo_id
                          join akis.tanim_surumu sv on sv.id = s.tanim_surumu_id
                         where p.uuid = :projectUuid
                           and p.arsivlenme_zamani is null
                           and y.durum = 'AKTIF'
                           and sv.tanim_id = (
                               select anchor_version.tanim_id
                                 from akis.yayin anchor_publication
                                 join akis.senaryo anchor_scenario on anchor_scenario.id = anchor_publication.senaryo_id
                                 join akis.tanim_surumu anchor_version on anchor_version.id = anchor_scenario.tanim_surumu_id
                                where anchor_publication.uuid = :configuredPublicationUuid
                                  and anchor_publication.proje_id = p.id)
                           and y.ortam_id = (select ortam_id from akis.yayin where uuid = :configuredPublicationUuid)
                         order by sv.surum_no desc, s.surum_no desc, y.yayin_no desc, y.id desc
                         limit 1
                         for update of y
                        """)
                .param("projectUuid", d.projectUuid())
                .param("configuredPublicationUuid", d.configuredPublicationUuid())
                .query((r, n) -> new PublicationContext(
                        r.getLong("project_id"), r.getLong("publication_id"),
                        r.getObject("publication_uuid", UUID.class),
                        r.getString("durum_kodu"), r.getString("risk_kodu"),
                        r.getString("release_hash"), r.getString("plan_ozeti"),
                        json(r.getString("fiziksel_manifesto"))))
                .optional().orElse(null);
    }

    /**
     * A PINNED schedule whose exact publication is gone or no longer AKTIF must never fall back to
     * an older run or to "latest active" instead — that would silently execute something the caller
     * never approved. Instead it is safely parked in ASKIDA with an explicit, durable reason, so a
     * human has to look at it and either re-pin or switch policy before it fires again.
     */
    private void suspendForInvalidPinnedPublication(UUID scheduleUuid) {
        jdbc.sql("""
                        update akis.zamanlama
                           set durum_kodu = 'ASKIDA', sonraki_tetikleme_zamani = null,
                               son_hata_mesaji = 'Sabitlenmiş (PINNED) yayın artık aktif/onaylı değil; zamanlama güvenli şekilde askıya alındı.',
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                         where uuid = :schedule and durum_kodu = 'AKTIF'
                        """)
                .param("schedule", scheduleUuid).update();
    }

    private void suspendExpiredWindow(UUID scheduleUuid) {
        jdbc.sql("""
                        update akis.zamanlama
                           set durum_kodu = 'ASKIDA', sonraki_tetikleme_zamani = null,
                               son_hata_mesaji = 'Zamanlama tarih penceresi sona erdi.',
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                         where uuid = :schedule and durum_kodu = 'AKTIF'
                        """)
                .param("schedule", scheduleUuid).update();
    }

    private void advance(UUID scheduleUuid, Due d, boolean fired) {
        CronExpression cron = CronExpression.parse(d.cronExpression());
        Instant now = clock.instant();
        OffsetDateTime next = ScheduleCalculator.nextFireTime(cron, java.time.ZoneId.of(d.timeZone()), now);
        boolean expired = d.endsAt() != null && !next.toInstant().isBefore(d.endsAt().toInstant());
        jdbc.sql("""
                        update akis.zamanlama
                           set sonraki_tetikleme_zamani = :next, durum_kodu = :status,
                               son_hata_mesaji = case when :expired then 'Zamanlama tarih penceresi sona erdi.' else son_hata_mesaji end,
                               son_tetikleme_zamani = case when :fired then :dueAt else son_tetikleme_zamani end,
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                         where uuid = :schedule
                        """)
                .param("next", expired ? null : next).param("status", expired ? "ASKIDA" : "AKTIF")
                .param("expired", expired).param("fired", fired).param("dueAt", d.dueAt()).param("schedule", scheduleUuid)
                .update();
    }

    private boolean hasActiveRun(long scheduleId) {
        Boolean active = jdbc.sql("""
                        select exists (
                            select 1 from akis.is_talebi it
                            join akis.calistirma c on c.is_talebi_id = it.id
                                and c.deneme_no = (select max(c2.deneme_no) from akis.calistirma c2 where c2.is_talebi_id = it.id)
                            join akis.calistirma_durumu cd on cd.calistirma_id = c.id
                           where it.zamanlama_id = :scheduleId
                             and cd.durum not in ('BASARILI', 'BASARISIZ', 'IPTAL', 'SONUCU_BILINMIYOR')
                        )
                        """)
                .param("scheduleId", scheduleId).query(Boolean.class).single();
        return Boolean.TRUE.equals(active);
    }

    private long pendingQueueRequests(long scheduleId) {
        return jdbc.sql("""
                select count(*) from akis.is_talebi request
                left join lateral (
                    select state.durum
                      from akis.calistirma run
                      join akis.calistirma_durumu state on state.calistirma_id = run.id
                     where run.is_talebi_id = request.id
                     order by run.deneme_no desc
                     limit 1
                ) latest on true
                 where request.zamanlama_id = :scheduleId
                   and (latest.durum is null or latest.durum not in
                        ('BASARILI', 'BASARISIZ', 'IPTAL', 'SONUCU_BILINMIYOR'))
                """).param("scheduleId", scheduleId).query(Long.class).single();
    }

    private void suspendForQueueLimit(UUID scheduleUuid) {
        jdbc.sql("""
                update akis.zamanlama
                   set durum_kodu = 'ASKIDA', sonraki_tetikleme_zamani = null,
                       son_hata_mesaji = 'Bekleyen zamanlama işi sınırı (100) doldu; zamanlama askıya alındı.',
                       guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                 where uuid = :schedule and durum_kodu = 'AKTIF'
                """).param("schedule", scheduleUuid).update();
    }

    /** @return whether a run was actually created. */
    private boolean fire(Due d, PublicationContext publication, Actor actor) {
        if (!"AKTIF".equals(publication.publicationStatus())) {
            LOG.warn("Schedule {} skipped a fire: publication {} is not active.", d.id(), publication.publicationUuid());
            return false;
        }
        String requestHash = sha256("ZAMANLAMA:" + d.id() + ":" + d.dueAt());
        backgroundAuthorization.runAs(actor, () ->
                store.createQueuedRun(
                        publication, actor, requestHash, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        null, d.id(), d.dueAt()));
        return true;
    }

    private JsonNode json(String value) {
        try {
            return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value);
        }
        catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("Invalid JSON manifest: " + exception.getMessage(), exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

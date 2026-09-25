package tr.com.innova.akis.execution;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;

/**
 * Re-checks and fires one due schedule (V049) in its own short transaction, called per row by
 * {@link ScheduleDueScanner}. Kept as a separate {@code @Transactional} bean rather than a method on the poller
 * itself: {@code @Scheduled} methods run outside a Spring AOP proxy, so a transactional method the poller called on
 * itself directly would silently run without a transaction.
 */
@Service
class ScheduleFireService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleFireService.class);
    /** A due time older than this is treated as missed (the poller itself was down), not an on-time fire. */
    private static final Duration MISSED_THRESHOLD = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final ExecutionStore store;

    ScheduleFireService(JdbcClient jdbc, ExecutionStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    private record Due(
            long id, UUID projectUuid, UUID publicationUuid, long creatorUserId,
            String cronExpression, String timeZone, String conflictPolicy, String misfirePolicy,
            OffsetDateTime dueAt) {
    }

    @Transactional
    void tryFire(UUID scheduleUuid) {
        Optional<Due> due = jdbc.sql("""
                        select z.id, p.uuid project_uuid, latest.uuid publication_uuid, z.olusturan_kullanici_id,
                               z.cron_ifadesi, z.zaman_dilimi, z.cakisma_politikasi, z.kacirma_politikasi,
                               z.sonraki_tetikleme_zamani
                          from akis.zamanlama z
                          join akis.proje p on p.id = z.proje_id
                          join akis.yayin configured on configured.id = z.yayin_id
                          join lateral (
                              select candidate.uuid
                                from akis.yayin candidate
                               where candidate.proje_id = configured.proje_id
                                 and candidate.senaryo_id = configured.senaryo_id
                                 and candidate.ortam_id = configured.ortam_id
                                 and candidate.durum = 'AKTIF'
                               order by candidate.yayin_no desc, candidate.id desc
                               limit 1
                          ) latest on true
                         where z.uuid = :schedule and z.durum_kodu = 'AKTIF' and z.sonraki_tetikleme_zamani <= now()
                         for update of z
                        """)
                .param("schedule", scheduleUuid)
                .query((r, n) -> new Due(
                        r.getLong("id"), r.getObject("project_uuid", UUID.class), r.getObject("publication_uuid", UUID.class),
                        r.getLong("olusturan_kullanici_id"), r.getString("cron_ifadesi"), r.getString("zaman_dilimi"),
                        r.getString("cakisma_politikasi"), r.getString("kacirma_politikasi"),
                        r.getObject("sonraki_tetikleme_zamani", OffsetDateTime.class)))
                .optional();
        if (due.isEmpty()) return; // already handled this tick (another instance) or paused/deleted since listing
        Due d = due.get();
        if ("SKIP".equals(d.conflictPolicy()) && hasActiveRun(d.id())) return; // retried next tick, unchanged

        boolean overdue = Duration.between(d.dueAt(), OffsetDateTime.now()).compareTo(MISSED_THRESHOLD) > 0;
        boolean shouldFire = !overdue || "RUN_ONCE".equals(d.misfirePolicy());
        boolean fired = shouldFire && fire(d);

        CronExpression cron = CronExpression.parse(d.cronExpression());
        OffsetDateTime next = ScheduleService.nextFireTime(cron, d.timeZone());
        jdbc.sql("""
                        update akis.zamanlama
                           set sonraki_tetikleme_zamani = :next,
                               son_tetikleme_zamani = case when :fired then :dueAt else son_tetikleme_zamani end,
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                         where id = :id
                        """)
                .param("next", next).param("fired", fired).param("dueAt", d.dueAt()).param("id", d.id())
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

    /** @return whether a run was actually created. */
    private boolean fire(Due d) {
        PublicationContext publication = store.lockPublication(d.projectUuid(), d.publicationUuid()).orElse(null);
        if (publication == null || !"AKTIF".equals(publication.publicationStatus())) {
            LOG.warn("Schedule {} skipped a fire: publication is not active.", d.id());
            return false;
        }
        Actor actor = store.findActiveActor(d.creatorUserId()).orElse(null);
        if (actor == null) {
            LOG.warn("Schedule {} skipped a fire: owning user is no longer active.", d.id());
            return false;
        }
        String requestHash = sha256("ZAMANLAMA:" + d.id() + ":" + d.dueAt());
        store.createQueuedRun(
                publication, actor, requestHash, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, d.id(), d.dueAt());
        return true;
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

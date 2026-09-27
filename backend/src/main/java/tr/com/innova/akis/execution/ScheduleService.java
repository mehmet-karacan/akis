package tr.com.innova.akis.execution;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.PRODUCTION_RUN;
import static tr.com.innova.akis.security.PermissionCodes.RUN_START;
import static tr.com.innova.akis.security.PermissionCodes.SCHEDULE_READ;
import static tr.com.innova.akis.security.PermissionCodes.SCHEDULE_WRITE;

/**
 * Cron-driven schedules that fire a publication's runs automatically (V049). Every write requires the caller to
 * already hold {@code RUN_START} (and {@code PRODUCTION_RUN} when the target environment is production-risk); that
 * check happens once here, in the authenticated HTTP request that creates or resumes the schedule — the due-scan
 * poller ({@link ScheduleDueScanner}) that actually fires it later runs on a background thread with no session to
 * re-check, exactly like a cron job's permissions are fixed by whoever installed the crontab entry.
 */
@Service
public class ScheduleService {

    private static final String PRODUCTION_RISK = "URETIM";

    public enum Status { AKTIF, ASKIDA }
    public enum ConflictPolicy { SKIP, QUEUE }
    public enum MisfirePolicy { SKIP, RUN_ONCE }

    /**
     * publicationUuid is the recorded/configured target (what was pinned, or the anchor a
     * LATEST_ACTIVE schedule was created against); resolvedPublicationUuid/resolvedVersionNumber
     * is what would actually fire right now under the current policy, computed the same way
     * {@link ScheduleFireService} resolves it, but read-only. They differ deliberately: for
     * LATEST_ACTIVE the resolved side tracks newer active versions; for PINNED it is null once
     * the pinned publication stops being active/approved, at which point lastErrorMessage explains
     * why the schedule was auto-suspended instead of silently running something else.
     */
    public record View(
            UUID uuid, String kod, String ad, UUID publicationUuid, String cronExpression, String timeZone,
            Status status, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy,
            UUID resolvedPublicationUuid, Integer resolvedVersionNumber, String lastErrorMessage,
            OffsetDateTime nextFireTime, OffsetDateTime lastFireTime,
            OffsetDateTime startsAt, OffsetDateTime endsAt, long version) {
    }

    public record Preview(
            String cronExpression, String normalizedExpression, String timeZone,
            OffsetDateTime serverTime, boolean syntaxValid, boolean exhausted,
            String description, List<String> descriptionParts,
            List<OffsetDateTime> nextOccurrences, List<String> errors) {
    }

    public record TriggerEvent(
            long id, OffsetDateTime scheduledFor, String outcome, OffsetDateTime occurredAt,
            UUID configuredPublicationUuid, UUID resolvedPublicationUuid) {
    }

    private record Scope(long projectId, long publicationId, String publicationStatus, String environmentRisk) {
    }

    private final JdbcClient jdbc;
    private final RunActorResolver actorResolver;
    private final AuthorizationService authorization;
    private final Clock clock;

    @Autowired
    ScheduleService(JdbcClient jdbc, RunActorResolver actorResolver, AuthorizationService authorization) {
        this(jdbc, actorResolver, authorization, Clock.systemUTC());
    }

    ScheduleService(JdbcClient jdbc, RunActorResolver actorResolver, AuthorizationService authorization, Clock clock) {
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_READ);
        return jdbc.sql(SELECT + " and p.uuid = :project order by z.olusturulma_zamani desc")
                .param("project", projectUuid).query(this::map).list();
    }

    @Transactional(readOnly = true)
    public View get(UUID projectUuid, UUID scheduleUuid) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_READ);
        return jdbc.sql(SELECT + " and p.uuid = :project and z.uuid = :schedule")
                .param("project", projectUuid).param("schedule", scheduleUuid)
                .query(this::map).optional().orElseThrow(() -> notFound());
    }

    @Transactional(readOnly = true)
    public List<TriggerEvent> triggerEvents(UUID projectUuid, UUID scheduleUuid) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_READ);
        Boolean exists = jdbc.sql("""
                select exists(select 1 from akis.zamanlama z join akis.proje p on p.id = z.proje_id
                               where p.uuid = :project and z.uuid = :schedule)
                """).param("project", projectUuid).param("schedule", scheduleUuid)
                .query(Boolean.class).single();
        if (!Boolean.TRUE.equals(exists)) throw notFound();
        return jdbc.sql("""
                select a.id, (a.ayrinti->>'scheduledFor')::timestamptz as scheduled_for,
                       a.ayrinti->>'outcome' as outcome, a.olay_zamani,
                       (a.ayrinti->>'configuredPublicationUuid')::uuid as configured_publication_uuid,
                       (a.ayrinti->>'resolvedPublicationUuid')::uuid as resolved_publication_uuid
                  from akis.denetim_olayi a
                  join akis.zamanlama z on z.uuid = a.dis_nesne_uuid and z.proje_id = a.proje_id
                  join akis.proje p on p.id = z.proje_id
                 where p.uuid = :project and z.uuid = :schedule
                   and a.eylem_kodu = 'ZAMANLAMA_TETIKLEME'
                 order by a.id desc limit 100
                """).param("project", projectUuid).param("schedule", scheduleUuid)
                .query((r, index) -> new TriggerEvent(r.getLong("id"),
                        r.getObject("scheduled_for", OffsetDateTime.class), r.getString("outcome"),
                        r.getObject("olay_zamani", OffsetDateTime.class),
                        r.getObject("configured_publication_uuid", UUID.class),
                        r.getObject("resolved_publication_uuid", UUID.class))).list();
    }

    @Transactional
    public View create(
            UUID projectUuid, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy) {
        return create(projectUuid, kod, ad, publicationUuid, cronExpression, timeZone,
                conflictPolicy, misfirePolicy, publicationPolicy, Status.AKTIF);
    }

    @Transactional
    public View create(
            UUID projectUuid, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy, Status desiredStatus) {
        return create(projectUuid, kod, ad, publicationUuid, cronExpression, timeZone,
                conflictPolicy, misfirePolicy, publicationPolicy, desiredStatus, null, null);
    }

    @Transactional
    public View create(
            UUID projectUuid, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy, Status desiredStatus, Instant startsAt, Instant endsAt) {
        var access = requireWriteAccess(projectUuid, publicationUuid);
        CronExpression cron = ScheduleCalculator.parseCron(cronExpression);
        ZoneId zone = ScheduleCalculator.parseZone(timeZone);
        requireKod(kod);
        if (ad == null || ad.isBlank()) throw validation("Ad gereklidir.");
        ConflictPolicy conflict = conflictPolicy == null ? ConflictPolicy.SKIP : conflictPolicy;
        MisfirePolicy misfire = misfirePolicy == null ? MisfirePolicy.SKIP : misfirePolicy;
        SchedulePublicationPolicy policy = publicationPolicy == null ? SchedulePublicationPolicy.LATEST_ACTIVE : publicationPolicy;
        Status status = desiredStatus == null ? Status.ASKIDA : desiredStatus;
        requirePinnedTargetActiveWhenDesiredAktif(status, policy, access.scope());
        OffsetDateTime candidate = nextFireTimeInWindow(cron, zone, clock.instant(), startsAt, endsAt);
        OffsetDateTime next = status == Status.AKTIF ? candidate : null;
        UUID scheduleUuid = UUID.randomUUID();
        jdbc.sql("""
                        insert into akis.zamanlama(
                            proje_id, yayin_id, kod, ad, olusturan_kullanici_id,
                            cron_ifadesi, zaman_dilimi, cakisma_politikasi, kacirma_politikasi,
                            yayin_gorunum_kodu, durum_kodu, sonraki_tetikleme_zamani,
                            baslangic_zamani, bitis_zamani, uuid)
                        select p.id, y.id, :kod, :ad, :actorId, :cron, :zone, :conflict, :misfire, :policy, :status, :next,
                               :startsAt, :endsAt, :uuid
                          from akis.proje p join akis.yayin y on y.proje_id = p.id
                         where p.uuid = :project and y.uuid = :publication and p.arsivlenme_zamani is null
                        """)
                .param("kod", kod).param("ad", ad).param("actorId", access.actor().id())
                .param("cron", cronExpression).param("zone", zone.getId())
                .param("conflict", conflict.name()).param("misfire", misfire.name())
                .param("policy", policy.name()).param("status", status.name()).param("next", next)
                .param("startsAt", utcOffset(startsAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("endsAt", utcOffset(endsAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("uuid", scheduleUuid).param("project", projectUuid).param("publication", publicationUuid)
                .update();
        return get(projectUuid, scheduleUuid);
    }

    @Transactional
    public View update(UUID projectUuid, UUID scheduleUuid, long expectedVersion, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy) {
        return update(projectUuid, scheduleUuid, expectedVersion, kod, ad, publicationUuid,
                cronExpression, timeZone, conflictPolicy, misfirePolicy, publicationPolicy, null);
    }

    @Transactional
    public View update(UUID projectUuid, UUID scheduleUuid, long expectedVersion, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy, Status desiredStatus) {
        var current = get(projectUuid, scheduleUuid);
        return update(projectUuid, scheduleUuid, expectedVersion, kod, ad, publicationUuid, cronExpression, timeZone,
                conflictPolicy, misfirePolicy, publicationPolicy, desiredStatus,
                current.startsAt() == null ? null : current.startsAt().toInstant(),
                current.endsAt() == null ? null : current.endsAt().toInstant());
    }

    @Transactional
    public View update(UUID projectUuid, UUID scheduleUuid, long expectedVersion, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy, Status desiredStatus, Instant startsAt, Instant endsAt) {
        var current = get(projectUuid, scheduleUuid);
        var access = requireWriteAccess(projectUuid, publicationUuid);
        if (current.version() != expectedVersion) throw staleVersion();
        requireKod(kod);
        if (ad == null || ad.isBlank()) throw validation("Ad gereklidir.");
        CronExpression cron = ScheduleCalculator.parseCron(cronExpression);
        ZoneId zone = ScheduleCalculator.parseZone(timeZone);
        ConflictPolicy conflict = conflictPolicy == null ? ConflictPolicy.SKIP : conflictPolicy;
        MisfirePolicy misfire = misfirePolicy == null ? MisfirePolicy.SKIP : misfirePolicy;
        SchedulePublicationPolicy policy = publicationPolicy == null ? SchedulePublicationPolicy.LATEST_ACTIVE : publicationPolicy;
        Status status = desiredStatus == null ? current.status() : desiredStatus;
        requirePinnedTargetActiveWhenDesiredAktif(status, policy, access.scope());
        OffsetDateTime candidate = nextFireTimeInWindow(cron, zone, clock.instant(), startsAt, endsAt);
        OffsetDateTime next = status == Status.AKTIF ? candidate : null;
        int rows = jdbc.sql("""
                        update akis.zamanlama set yayin_id = (select y.id from akis.yayin y join akis.proje p on p.id = y.proje_id where y.uuid = :publication and p.uuid = :project and p.arsivlenme_zamani is null),
                               kod = :kod, ad = :ad, cron_ifadesi = :cron, zaman_dilimi = :zone,
                               cakisma_politikasi = :conflict, kacirma_politikasi = :misfire, yayin_gorunum_kodu = :policy,
                               durum_kodu = :status, baslangic_zamani = :startsAt, bitis_zamani = :endsAt,
                               son_hata_mesaji = case when :status = 'AKTIF' then null else son_hata_mesaji end,
                               sonraki_tetikleme_zamani = :next, guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                          where uuid = :schedule and arsivlenme_zamani is null and versiyon_no = :expectedVersion
                        """)
                .param("project", projectUuid).param("publication", publicationUuid).param("schedule", scheduleUuid)
                .param("kod", kod).param("ad", ad).param("cron", cronExpression).param("zone", zone.getId())
                .param("conflict", conflict.name()).param("misfire", misfire.name()).param("policy", policy.name()).param("status", status.name())
                .param("startsAt", utcOffset(startsAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("endsAt", utcOffset(endsAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("next", next).param("expectedVersion", expectedVersion).update();
        if (rows == 0) {
            long nowVersion = jdbc.sql("select versiyon_no from akis.zamanlama where uuid = :schedule")
                    .param("schedule", scheduleUuid).query(Long.class).optional().orElse(-1L);
            if (nowVersion != expectedVersion) throw staleVersion();
            throw notFound();
        }
        return get(projectUuid, scheduleUuid);
    }

    /** Pausing always succeeds (it only narrows what the poller may do); resuming re-validates run authorization. */
    @Transactional
    public View pause(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_WRITE);
        int rows = jdbc.sql("""
                        update akis.zamanlama set durum_kodu = 'ASKIDA', sonraki_tetikleme_zamani = null,
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                          where uuid = :schedule
                            and proje_id = (select p.id from akis.proje p where p.uuid = :project and p.arsivlenme_zamani is null)
                            and arsivlenme_zamani is null and versiyon_no = :expectedVersion
                        """)
                .param("project", projectUuid).param("schedule", scheduleUuid).param("expectedVersion", expectedVersion).update();
        if (rows == 0) {
            requireVersion(projectUuid, scheduleUuid, expectedVersion);
        }
        return get(projectUuid, scheduleUuid);
    }

    @Transactional
    public View resume(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        var current = get(projectUuid, scheduleUuid);
        var access = requireWriteAccess(projectUuid, current.publicationUuid());
        if (current.version() != expectedVersion) throw staleVersion();
        requirePinnedTargetActiveWhenDesiredAktif(Status.AKTIF, current.publicationPolicy(), access.scope());
        CronExpression cron = ScheduleCalculator.parseCron(current.cronExpression());
        OffsetDateTime next = nextFireTimeInWindow(cron, ScheduleCalculator.parseZone(current.timeZone()), clock.instant(),
                current.startsAt() == null ? null : current.startsAt().toInstant(),
                current.endsAt() == null ? null : current.endsAt().toInstant());
        int rows = jdbc.sql("""
                        update akis.zamanlama set durum_kodu = 'AKTIF', sonraki_tetikleme_zamani = :next,
                               son_hata_mesaji = null,
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                          where uuid = :schedule
                            and proje_id = (select p.id from akis.proje p where p.uuid = :project and p.arsivlenme_zamani is null)
                            and arsivlenme_zamani is null and versiyon_no = :expectedVersion
                """)
                .param("next", next).param("project", projectUuid).param("schedule", scheduleUuid)
                .param("expectedVersion", expectedVersion).update();
        if (rows == 0) {
            requireVersion(projectUuid, scheduleUuid, expectedVersion);
        }
        return get(projectUuid, scheduleUuid);
    }

    @Transactional
    public void delete(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_WRITE);
        int rows = jdbc.sql("""
                        update akis.zamanlama set arsivlenme_zamani = current_timestamp,
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                          where uuid = :schedule
                            and proje_id = (select p.id from akis.proje p where p.uuid = :project and p.arsivlenme_zamani is null)
                            and arsivlenme_zamani is null and versiyon_no = :expectedVersion
                        """)
                .param("project", projectUuid).param("schedule", scheduleUuid).param("expectedVersion", expectedVersion).update();
        if (rows == 0) {
            requireVersion(projectUuid, scheduleUuid, expectedVersion);
        }
    }

    @Transactional(readOnly = true)
    public Preview preview(String cronExpression, String timeZone) {
        return preview(cronExpression, timeZone, null, null);
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID projectUuid, String cronExpression, String timeZone,
            Instant startsAt, Instant endsAt) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_READ);
        return preview(cronExpression, timeZone, startsAt, endsAt);
    }

    @Transactional(readOnly = true)
    public Preview preview(String cronExpression, String timeZone, Instant startsAt, Instant endsAt) {
        String normalized = cronExpression == null ? "" : cronExpression.trim();
        List<String> errors = new ArrayList<>();
        CronExpression cron = null;
        ZoneId zone;
        try {
            cron = ScheduleCalculator.parseCron(normalized);
        }
        catch (ApiException exception) {
            errors.add(exception.getMessage());
        }
        try {
            zone = ScheduleCalculator.parseZone(timeZone);
        }
        catch (ApiException exception) {
            errors.add(exception.getMessage());
            zone = ZoneId.of(ScheduleCalculator.DEFAULT_TIME_ZONE);
        }
        Instant now = clock.instant();
        boolean explicitStartNowOrLater = startsAt != null && !startsAt.isBefore(now);
        Instant lowerBound = explicitStartNowOrLater ? startsAt : now;
        Instant from = explicitStartNowOrLater ? startsAt.minusNanos(1) : now;
        if (endsAt != null && !endsAt.isAfter(lowerBound)) {
            errors.add("Bitiş zamanı başlangıç zamanından sonra olmalıdır.");
        }
        List<OffsetDateTime> next = errors.isEmpty() && cron != null
                ? ScheduleCalculator.nextOccurrences(cron, zone, 3, from).stream()
                        .filter(value -> endsAt == null || value.toInstant().isBefore(endsAt))
                        .toList()
                : List.of();
        boolean exhausted = next.size() < 3;
        List<String> descriptionParts = List.of("Cron", "sunucu saati", zone.getId());
        String description = String.join(" · ", descriptionParts);
        return new Preview(normalized, normalized, zone.getId(), OffsetDateTime.now(clock), errors.isEmpty(),
                exhausted, description, descriptionParts, next, List.copyOf(errors));
    }

    static OffsetDateTime nextFireTime(CronExpression cron, ZoneId zone) {
        return ScheduleCalculator.nextFireTime(cron, zone);
    }

    private static OffsetDateTime nextFireTime(CronExpression cron, ZoneId zone, Instant from) {
        return ScheduleCalculator.nextFireTime(cron, zone, from);
    }

    private static OffsetDateTime nextFireTimeInWindow(CronExpression cron, ZoneId zone, Instant now,
            Instant startsAt, Instant endsAt) {
        boolean explicitStartNowOrLater = startsAt != null && !startsAt.isBefore(now);
        Instant lowerBound = explicitStartNowOrLater ? startsAt : now;
        Instant from = explicitStartNowOrLater ? startsAt.minusNanos(1) : now;
        if (endsAt != null && !endsAt.isAfter(lowerBound)) {
            throw validation("Bitiş zamanı gelecekte ve başlangıç zamanından sonra olmalıdır.");
        }
        OffsetDateTime next = nextFireTime(cron, zone, from);
        if (endsAt != null && !next.toInstant().isBefore(endsAt)) {
            throw validation("Seçilen tarih penceresinde gelecek çalışma zamanı yok.");
        }
        return next;
    }

    private static OffsetDateTime utcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private record WriteAccess(ExecutionModels.Actor actor, Scope scope) {
    }

    private WriteAccess requireWriteAccess(UUID projectUuid, UUID publicationUuid) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_WRITE);
        authorization.requireProjectPermission(projectUuid, RUN_START);
        Scope scope = jdbc.sql("""
                        select p.id project_id, y.id publication_id, y.durum publication_status, o.risk environment_risk
                          from akis.proje p join akis.yayin y on y.proje_id = p.id
                          join akis.ortam o on o.id = y.ortam_id
                         where p.uuid = :project and y.uuid = :publication and p.arsivlenme_zamani is null
                        """)
                .param("project", projectUuid).param("publication", publicationUuid)
                .query((r, n) -> new Scope(r.getLong("project_id"), r.getLong("publication_id"),
                        r.getString("publication_status"), r.getString("environment_risk")))
                .optional().orElseThrow(() -> notFound());
        if (PRODUCTION_RISK.equals(scope.environmentRisk())) authorization.requireProjectPermission(projectUuid, PRODUCTION_RUN);
        return new WriteAccess(actorResolver.currentActor(), scope);
    }

    /**
     * PINNED must never let an AKTIF schedule point at a publication that is not currently
     * AKTIF: that is exactly the "silently run something else, or run nothing while looking
     * healthy" gap this policy exists to close. Fail closed at the write boundary instead of
     * waiting for the next fire attempt to discover it.
     */
    private void requirePinnedTargetActiveWhenDesiredAktif(
            Status status, SchedulePublicationPolicy policy, Scope scope) {
        if (status == Status.AKTIF && policy == SchedulePublicationPolicy.PINNED
                && !"AKTIF".equals(scope.publicationStatus())) {
            throw validation("Sabitlenmiş (PINNED) yayın artık aktif değil; zamanlama AKTIF olamaz.");
        }
    }

    private void requireVersion(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        if (get(projectUuid, scheduleUuid).version() != expectedVersion) throw staleVersion();
    }

    private static void requireKod(String kod) {
        if (kod == null || !kod.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw validation("Kod büyük harf, rakam ve alt çizgi içermeli, bir harfle başlamalıdır.");
        }
    }

    private View map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        String policy = r.getString("yayin_gorunum_kodu");
        return new View(
                r.getObject("uuid", UUID.class), r.getString("kod"), r.getString("ad"),
                r.getObject("publication_uuid", UUID.class), r.getString("cron_ifadesi"), r.getString("zaman_dilimi"),
                Status.valueOf(r.getString("durum_kodu")), ConflictPolicy.valueOf(r.getString("cakisma_politikasi")),
                MisfirePolicy.valueOf(r.getString("kacirma_politikasi")),
                policy == null ? SchedulePublicationPolicy.LATEST_ACTIVE : SchedulePublicationPolicy.valueOf(policy),
                r.getObject("resolved_publication_uuid", UUID.class),
                r.getObject("resolved_version_number", Integer.class),
                r.getString("son_hata_mesaji"),
                r.getObject("sonraki_tetikleme_zamani", OffsetDateTime.class),
                r.getObject("son_tetikleme_zamani", OffsetDateTime.class),
                r.getObject("baslangic_zamani", OffsetDateTime.class),
                r.getObject("bitis_zamani", OffsetDateTime.class), r.getLong("versiyon_no"));
    }

    private static final String SELECT = """
            select z.uuid, z.kod, z.ad, y.uuid as publication_uuid, z.cron_ifadesi, z.zaman_dilimi,
                   z.durum_kodu, z.cakisma_politikasi, z.kacirma_politikasi, z.yayin_gorunum_kodu,
                   z.sonraki_tetikleme_zamani, z.son_tetikleme_zamani, z.son_hata_mesaji, z.versiyon_no,
                   z.baslangic_zamani, z.bitis_zamani,
                   resolved.publication_uuid as resolved_publication_uuid,
                   resolved.version_number as resolved_version_number
              from akis.zamanlama z
              join akis.proje p on p.id = z.proje_id
              join akis.yayin y on y.id = z.yayin_id
              join akis.senaryo cs on cs.id = y.senaryo_id
              join akis.tanim_surumu cv on cv.id = cs.tanim_surumu_id
              left join lateral (
                  select ry.uuid as publication_uuid, rv.surum_no as version_number
                    from akis.yayin ry
                    join akis.senaryo rs on rs.id = ry.senaryo_id
                    join akis.tanim_surumu rv on rv.id = rs.tanim_surumu_id
                   where ry.durum = 'AKTIF'
                     and (
                         (z.yayin_gorunum_kodu = 'PINNED' and ry.id = y.id)
                      or (z.yayin_gorunum_kodu <> 'PINNED' and rv.tanim_id = cv.tanim_id and ry.ortam_id = y.ortam_id)
                     )
                   order by rv.surum_no desc, rs.surum_no desc, ry.yayin_no desc, ry.id desc
                   limit 1
              ) resolved on true
             where z.arsivlenme_zamani is null
            """;

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SCHEDULE_VALIDATION_FAILED", message);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Zamanlama veya yayın bulunamadı.");
    }

    private static ApiException staleVersion() {
        return new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Zamanlama değişmiş; yeniden yükleyin.");
    }
}

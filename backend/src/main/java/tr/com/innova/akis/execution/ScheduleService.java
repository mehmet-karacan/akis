package tr.com.innova.akis.execution;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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

    public record View(
            UUID uuid, String kod, String ad, UUID publicationUuid, String cronExpression, String timeZone,
            Status status, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy,
            OffsetDateTime nextFireTime, OffsetDateTime lastFireTime, long version) {
    }

    private record Scope(long projectId, long publicationId, String publicationStatus, String environmentRisk) {
    }

    private final JdbcClient jdbc;
    private final RunActorResolver actorResolver;
    private final AuthorizationService authorization;

    ScheduleService(JdbcClient jdbc, RunActorResolver actorResolver, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_READ);
        return jdbc.sql(SELECT + " where p.uuid = :project order by z.olusturulma_zamani desc")
                .param("project", projectUuid).query(this::map).list();
    }

    @Transactional(readOnly = true)
    public View get(UUID projectUuid, UUID scheduleUuid) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_READ);
        return jdbc.sql(SELECT + " where p.uuid = :project and z.uuid = :schedule")
                .param("project", projectUuid).param("schedule", scheduleUuid)
                .query(this::map).optional().orElseThrow(() -> notFound());
    }

    @Transactional
    public View create(
            UUID projectUuid, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy) {
        var actor = requireWriteAccess(projectUuid, publicationUuid);
        CronExpression cron = requireValidCron(cronExpression);
        ZoneId zone = requireValidZone(timeZone);
        requireKod(kod);
        if (ad == null || ad.isBlank()) throw validation("Ad gereklidir.");
        UUID scheduleUuid = UUID.randomUUID();
        jdbc.sql("""
                        insert into akis.zamanlama(
                            proje_id, yayin_id, kod, ad, olusturan_kullanici_id,
                            cron_ifadesi, zaman_dilimi, cakisma_politikasi, kacirma_politikasi, uuid)
                        select p.id, y.id, :kod, :ad, :actorId, :cron, :zone, :conflict, :misfire, :uuid
                          from akis.proje p join akis.yayin y on y.proje_id = p.id
                         where p.uuid = :project and y.uuid = :publication
                        """)
                .param("kod", kod).param("ad", ad).param("actorId", actor.id())
                .param("cron", cronExpression).param("zone", zone.getId())
                .param("conflict", conflictPolicy.name()).param("misfire", misfirePolicy.name())
                .param("uuid", scheduleUuid).param("project", projectUuid).param("publication", publicationUuid)
                .update();
        return get(projectUuid, scheduleUuid);
    }

    @Transactional
    public View update(UUID projectUuid, UUID scheduleUuid, long expectedVersion, String kod, String ad, UUID publicationUuid,
            String cronExpression, String timeZone, ConflictPolicy conflictPolicy, MisfirePolicy misfirePolicy) {
        var current = get(projectUuid, scheduleUuid);
        requireWriteAccess(projectUuid, publicationUuid);
        if (current.version() != expectedVersion) throw staleVersion();
        requireKod(kod);
        if (ad == null || ad.isBlank()) throw validation("Ad gereklidir.");
        CronExpression cron = requireValidCron(cronExpression);
        ZoneId zone = requireValidZone(timeZone);
        OffsetDateTime next = current.status() == Status.AKTIF ? nextFireTime(cron, zone.getId()) : null;
        jdbc.sql("""
                        update akis.zamanlama set yayin_id = (select y.id from akis.yayin y join akis.proje p on p.id = y.proje_id where y.uuid = :publication and p.uuid = :project),
                               kod = :kod, ad = :ad, cron_ifadesi = :cron, zaman_dilimi = :zone,
                               cakisma_politikasi = :conflict, kacirma_politikasi = :misfire,
                               sonraki_tetikleme_zamani = :next, guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                         where uuid = :schedule
                        """)
                .param("project", projectUuid).param("publication", publicationUuid).param("schedule", scheduleUuid)
                .param("kod", kod).param("ad", ad).param("cron", cronExpression).param("zone", zone.getId())
                .param("conflict", (conflictPolicy == null ? ConflictPolicy.SKIP : conflictPolicy).name())
                .param("misfire", (misfirePolicy == null ? MisfirePolicy.SKIP : misfirePolicy).name()).param("next", next).update();
        return get(projectUuid, scheduleUuid);
    }

    /** Pausing always succeeds (it only narrows what the poller may do); resuming re-validates run authorization. */
    @Transactional
    public View pause(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_WRITE);
        requireVersion(projectUuid, scheduleUuid, expectedVersion);
        jdbc.sql("""
                        update akis.zamanlama set durum_kodu = 'ASKIDA', sonraki_tetikleme_zamani = null,
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                         where uuid = :schedule
                        """)
                .param("schedule", scheduleUuid).update();
        return get(projectUuid, scheduleUuid);
    }

    @Transactional
    public View resume(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        var current = get(projectUuid, scheduleUuid);
        requireWriteAccess(projectUuid, current.publicationUuid());
        if (current.version() != expectedVersion) throw staleVersion();
        CronExpression cron = requireValidCron(current.cronExpression());
        OffsetDateTime next = nextFireTime(cron, current.timeZone());
        jdbc.sql("""
                        update akis.zamanlama set durum_kodu = 'AKTIF', sonraki_tetikleme_zamani = :next,
                               guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
                         where uuid = :schedule
                        """)
                .param("next", next).param("schedule", scheduleUuid).update();
        return get(projectUuid, scheduleUuid);
    }

    @Transactional
    public void delete(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        authorization.requireProjectPermission(projectUuid, SCHEDULE_WRITE);
        requireVersion(projectUuid, scheduleUuid, expectedVersion);
        jdbc.sql("delete from akis.zamanlama where uuid = :schedule")
                .param("schedule", scheduleUuid).update();
    }

    static OffsetDateTime nextFireTime(CronExpression cron, String timeZone) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timeZone));
        ZonedDateTime next = cron.next(now);
        if (next == null) throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "SCHEDULE_VALIDATION_FAILED", "Cron ifadesi için sonraki tetikleme zamanı hesaplanamadı.");
        return next.toOffsetDateTime();
    }

    private ExecutionModels.Actor requireWriteAccess(UUID projectUuid, UUID publicationUuid) {
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
        return actorResolver.currentActor();
    }

    private void requireVersion(UUID projectUuid, UUID scheduleUuid, long expectedVersion) {
        if (get(projectUuid, scheduleUuid).version() != expectedVersion) throw staleVersion();
    }

    private static CronExpression requireValidCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) throw validation("Cron ifadesi gereklidir.");
        try {
            return CronExpression.parse(cronExpression.trim());
        }
        catch (IllegalArgumentException invalid) {
            throw validation("Cron ifadesi geçersiz: " + invalid.getMessage());
        }
    }

    private static ZoneId requireValidZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) throw validation("Zaman dilimi gereklidir.");
        try {
            return ZoneId.of(timeZone.trim());
        }
        catch (RuntimeException invalid) {
            throw validation("Zaman dilimi geçersiz: " + timeZone);
        }
    }

    private static void requireKod(String kod) {
        if (kod == null || !kod.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw validation("Kod büyük harf, rakam ve alt çizgi içermeli, bir harfle başlamalıdır.");
        }
    }

    private View map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new View(
                r.getObject("uuid", UUID.class), r.getString("kod"), r.getString("ad"),
                r.getObject("publication_uuid", UUID.class), r.getString("cron_ifadesi"), r.getString("zaman_dilimi"),
                Status.valueOf(r.getString("durum_kodu")), ConflictPolicy.valueOf(r.getString("cakisma_politikasi")),
                MisfirePolicy.valueOf(r.getString("kacirma_politikasi")),
                r.getObject("sonraki_tetikleme_zamani", OffsetDateTime.class),
                r.getObject("son_tetikleme_zamani", OffsetDateTime.class), r.getLong("versiyon_no"));
    }

    private static final String SELECT = """
            select z.uuid, z.kod, z.ad, y.uuid as publication_uuid, z.cron_ifadesi, z.zaman_dilimi,
                   z.durum_kodu, z.cakisma_politikasi, z.kacirma_politikasi,
                   z.sonraki_tetikleme_zamani, z.son_tetikleme_zamani, z.versiyon_no
              from akis.zamanlama z
              join akis.proje p on p.id = z.proje_id
              join akis.yayin y on y.id = z.yayin_id
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

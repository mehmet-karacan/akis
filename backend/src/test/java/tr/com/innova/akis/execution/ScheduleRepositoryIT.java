package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.ApplicationUserPrincipal;
import tr.com.innova.akis.security.AuthorizationService;

class ScheduleRepositoryIT {
    private static JdbcClient jdbc;
    private static JdbcExecutionStore store;
    private static ScheduleService service;
    private static UUID projectUuid;
    private static UUID publicationUuid;
    private static long actorId;
    private static long projectId;

    @BeforeAll
    static void connect() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        boolean dbReady = url != null && !url.isBlank() && url.matches(".*(/akis_schedule_test_[0-9]+)(?:\\?.*)?$");
        Assumptions.assumeTrue(dbReady, "Generated schedule DB required; skipping integration test.");
        String username = required("SPRING_DATASOURCE_USERNAME");
        String password = required("SPRING_DATASOURCE_PASSWORD");

        jdbc = JdbcClient.create(new DriverManagerDataSource(url, username, password));
        ObjectMapper mapper = new ObjectMapper();
        store = new JdbcExecutionStore(jdbc, mapper);
        AuthorizationService authorization = new AuthorizationService(null) {
            @Override
            public void requireProjectPermission(UUID projectUuid, String permissionCode) {
            }
        };
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new ApplicationUserPrincipal(1L, UUID.randomUUID(), "schedule-test", "Schedule Test", "{noop}test", true),
                        null, List.of()));

        projectUuid = jdbc.sql("insert into akis.proje(kod,ad) values ('SCHEDULE_IT','Schedule IT') returning uuid").query(UUID.class).single();
        projectId = jdbc.sql("select id from akis.proje where uuid=:u").param("u", projectUuid).query(Long.class).single();
        actorId = jdbc.sql("insert into akis.kullanici(gorunen_ad,kullanici_kodu,ad,durum,parola,parola_degistirilme_zamani) values ('Scheduler','scheduler','Scheduler','AKTIF','{argon2}test',current_timestamp) returning id").query(Long.class).single();
        RunActorResolver actorResolver = new TestRunActorResolver(store, actorId);
        service = new ScheduleService(jdbc, actorResolver, authorization);
        long ortam = jdbc.sql("insert into akis.ortam(kod,ad) values ('DEV_SCHEDULE','Development') returning id").query(Long.class).single();
        long klasor = jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id").param("p", projectId).query(Long.class).single();
        long tanim = jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','LOAD','Load') returning id").param("p", projectId).param("f", klasor).query(Long.class).single();
        long surum = jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values (:p,:t,1,1,:h,'{\"tasks\":[]}') returning id").param("p", projectId).param("t", tanim).param("h", "a".repeat(64)).query(Long.class).single();
        long dogrulama = jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values (:p,:v,:h,'GECTI','{}') returning id").param("p", projectId).param("v", surum).param("h", "a".repeat(64)).query(Long.class).single();
        long senaryo = jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values (:p,:v,:d,1,2,:h,'{}') returning id").param("p", projectId).param("v", surum).param("d", dogrulama).param("h", "b".repeat(64)).query(Long.class).single();
        publicationUuid = jdbc.sql("insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani) values (:p,:s,:o,1,'AKTIF','ready',cast(:m as jsonb),current_timestamp) returning uuid").param("p", projectId).param("s", senaryo).param("o", ortam).param("m", "{\"releaseHash\":\"" + "c".repeat(64) + "\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}").query(UUID.class).single();
    }

    @Test
    void createStoresDefaultEuropeIstanbulAndNextFireTime() {
        ScheduleService.View view = service.create(
                projectUuid, "HOURLY", "Hourly", publicationUuid,
                "0 0 * * * *", null, ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, null);

        assertEquals("Europe/Istanbul", view.timeZone());
        assertNotNull(view.nextFireTime());
        assertEquals(SchedulePublicationPolicy.LATEST_ACTIVE, view.publicationPolicy());

        long count = jdbc.sql("select count(*) from akis.zamanlama where uuid = :uuid and zaman_dilimi = 'Europe/Istanbul' and sonraki_tetikleme_zamani is not null")
                .param("uuid", view.uuid()).query(Long.class).single();
        assertEquals(1L, count);
    }

    @Test
    void previewReturnsThreeOccurrencesFromServer() {
        ScheduleService.Preview preview = service.preview("0 0 3 * * *", "Europe/Istanbul");

        assertEquals(3, preview.nextOccurrences().size());
        assertEquals("Europe/Istanbul", preview.timeZone());
    }

    @Test
    void dateWindowPersistsAndBoundsNextFire() {
        Instant start = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant end = start.plus(Duration.ofDays(1));
        ScheduleService.View view = service.create(
                projectUuid, "WINDOW_BOUNDED", "Bounded", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.LATEST_ACTIVE,
                ScheduleService.Status.AKTIF, start, end);

        assertEquals(start, view.startsAt().toInstant());
        assertEquals(end, view.endsAt().toInstant());
        assertTrue(view.nextFireTime().toInstant().isAfter(start));
        assertTrue(view.nextFireTime().toInstant().isBefore(end));
        assertEquals(start, jdbc.sql("select baslangic_zamani from akis.zamanlama where uuid=:uuid")
                .param("uuid", view.uuid()).query(java.time.OffsetDateTime.class).single().toInstant());
    }

    @Test
    void softDeletePreservesHistory() {
        ScheduleService.View view = service.create(
                projectUuid, "SOFT", "Soft delete", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, null);

        service.delete(projectUuid, view.uuid(), view.version());

        Long archivedCount = jdbc.sql("select count(*) from akis.zamanlama where uuid = :uuid and arsivlenme_zamani is not null")
                .param("uuid", view.uuid()).query(Long.class).single();
        Long visibleCount = jdbc.sql("select count(*) from akis.zamanlama where uuid = :uuid and arsivlenme_zamani is null")
                .param("uuid", view.uuid()).query(Long.class).single();
        assertEquals(1L, archivedCount);
        assertEquals(0L, visibleCount);
    }

    @Test
    void staleVersionOnUpdateIsRejected() {
        ScheduleService.View view = service.create(
                projectUuid, "STALE", "Stale", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, null);

        ApiException error = assertThrows(ApiException.class, () -> service.update(
                projectUuid, view.uuid(), view.version() - 1, "STALE", "Stale", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, null));

        assertEquals("STALE_VERSION", error.code());
    }

    @Test
    void dueScannerFireWindowExcludesFarFutureOccurrences() {
        // Create a schedule whose next fire time is deliberately far in the future.
        ScheduleService.View view = service.create(
                projectUuid, "WINDOW", "Window", publicationUuid,
                "0 0 1 1 1 ?", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, null);

        // The schedule should exist but not be returned by the bounded due query.
        Long dueCount = jdbc.sql("""
                        select count(*) from akis.zamanlama
                         where uuid = :uuid and durum_kodu = 'AKTIF'
                           and sonraki_tetikleme_zamani <= now()
                           and sonraki_tetikleme_zamani <= now() + cast(:window as interval)
                           and arsivlenme_zamani is null
                        """)
                .param("uuid", view.uuid()).param("window", "2 minutes").query(Long.class).single();
        assertEquals(0L, dueCount);
    }

    @Test
    void createRejectsPinnedActiveScheduleWhenPublicationIsNotActive() {
        long scenarioId = jdbc.sql("select senaryo_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        long environmentId = jdbc.sql("select ortam_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        UUID inactivePublication = jdbc.sql("""
                insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto)
                values (:project,:scenario,:environment,3,'ONAY_BEKLIYOR','ready','{}'::jsonb)
                returning uuid
                """).param("project", projectId).param("scenario", scenarioId).param("environment", environmentId)
                .query(UUID.class).single();

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                projectUuid, "PINNED_CREATE_REJECT", "Pinned create reject", inactivePublication,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.PINNED, ScheduleService.Status.AKTIF));

        assertEquals("SCHEDULE_VALIDATION_FAILED", error.code());
        Long count = jdbc.sql("select count(*) from akis.zamanlama where kod = 'PINNED_CREATE_REJECT'")
                .query(Long.class).single();
        assertEquals(0L, count, "A schedule must not be created when its PINNED target is not active");
    }

    @Test
    void resumeRejectsPinnedScheduleWhenPublicationBecameInactive() {
        long scenarioId = jdbc.sql("select senaryo_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        long environmentId = jdbc.sql("select ortam_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        UUID dedicatedPublication = jdbc.sql("""
                insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani)
                values (:project,:scenario,:environment,4,'AKTIF','ready',cast(:m as jsonb),current_timestamp)
                returning uuid
                """).param("project", projectId).param("scenario", scenarioId).param("environment", environmentId)
                .param("m", "{\"releaseHash\":\"" + "d".repeat(64) + "\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}")
                .query(UUID.class).single();

        ScheduleService.View created = service.create(
                projectUuid, "PINNED_RESUME_REJECT", "Pinned resume reject", dedicatedPublication,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.PINNED, ScheduleService.Status.ASKIDA);
        // The pinned target becomes invalid after the (paused) schedule was created against it.
        jdbc.sql("update akis.yayin set durum = 'IPTAL' where uuid = :publication")
                .param("publication", dedicatedPublication).update();

        ApiException error = assertThrows(ApiException.class,
                () -> service.resume(projectUuid, created.uuid(), created.version()));

        assertEquals("SCHEDULE_VALIDATION_FAILED", error.code());
        String status = jdbc.sql("select durum_kodu from akis.zamanlama where uuid = :uuid")
                .param("uuid", created.uuid()).query(String.class).single();
        assertEquals("ASKIDA", status, "A rejected resume must leave the schedule paused, not AKTIF");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
}

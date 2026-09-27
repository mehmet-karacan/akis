package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.security.ApplicationUserPrincipal;
import tr.com.innova.akis.security.AuthorizationService;

/**
 * Two simulated workers try to fire the same schedule occurrence at the same time. The database-level
 * {@code select ... for update of zamanlama} and the unique index on {@code is_talebi(zamanlama_id, planlanan_zaman)}
 * together guarantee exactly one run per occurrence.
 */
class ScheduleTwoWorkerUniquenessTest {

    private static JdbcClient jdbc;
    private static DriverManagerDataSource dataSource;
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

        dataSource = new DriverManagerDataSource(url, username, password);
        jdbc = JdbcClient.create(dataSource);
        if (!Boolean.TRUE.equals(jdbc.sql("select to_regclass('akis.zamanlama') is not null")
                .query(Boolean.class).single())) {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/akis")
                    .defaultSchema("akis").schemas("akis").createSchemas(true)
                    .validateMigrationNaming(true).load().migrate();
        }
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

        projectUuid = jdbc.sql("insert into akis.proje(kod,ad) values ('SCHEDULE_UNIQUE','Schedule Uniqueness') returning uuid").query(UUID.class).single();
        projectId = jdbc.sql("select id from akis.proje where uuid=:u").param("u", projectUuid).query(Long.class).single();
        actorId = jdbc.sql("insert into akis.kullanici(gorunen_ad,kullanici_kodu,ad,durum,parola,parola_degistirilme_zamani) values ('Scheduler Unique','scheduler_unique','Scheduler','AKTIF','{argon2}test',current_timestamp) returning id").query(Long.class).single();
        RunActorResolver actorResolver = new TestRunActorResolver(store, actorId);
        service = new ScheduleService(jdbc, actorResolver, authorization);
        long ortam = jdbc.sql("insert into akis.ortam(kod,ad) values ('DEV_UNIQUE','Development') returning id").query(Long.class).single();
        long klasor = jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id").param("p", projectId).query(Long.class).single();
        long tanim = jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','LOAD','Load') returning id").param("p", projectId).param("f", klasor).query(Long.class).single();
        long surum = jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values (:p,:t,1,1,:h,'{\"tasks\":[]}') returning id").param("p", projectId).param("t", tanim).param("h", "a".repeat(64)).query(Long.class).single();
        long dogrulama = jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values (:p,:v,:h,'GECTI','{}') returning id").param("p", projectId).param("v", surum).param("h", "a".repeat(64)).query(Long.class).single();
        long senaryo = jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values (:p,:v,:d,1,2,:h,'{}') returning id").param("p", projectId).param("v", surum).param("d", dogrulama).param("h", "b".repeat(64)).query(Long.class).single();
        publicationUuid = jdbc.sql("insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani) values (:p,:s,:o,1,'AKTIF','ready',cast(:m as jsonb),current_timestamp) returning uuid").param("p", projectId).param("s", senaryo).param("o", ortam).param("m", "{\"releaseHash\":\"" + "c".repeat(64) + "\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}").query(UUID.class).single();
    }

    @Test
    void queueLimitSuspendsScheduleWithoutAddingAnotherRequest() {
        ScheduleService.View view = service.create(
                projectUuid, "QUEUE_LIMIT", "Queue limit", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.QUEUE,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.PINNED);
        long scheduleId = jdbc.sql("select id from akis.zamanlama where uuid = :uuid")
                .param("uuid", view.uuid()).query(Long.class).single();
        long publicationId = jdbc.sql("select id from akis.yayin where uuid = :uuid")
                .param("uuid", publicationUuid).query(Long.class).single();
        jdbc.sql("""
                insert into akis.is_talebi(
                    proje_id, yayin_id, zamanlama_id, planlanan_zaman, istek_ozeti)
                select :project, :publication, :schedule,
                       current_timestamp - (g * interval '1 minute'), repeat('f', 64)
                  from generate_series(1, 100) g
                """).param("project", projectId).param("publication", publicationId)
                .param("schedule", scheduleId).update();
        jdbc.sql("""
                update akis.zamanlama
                   set sonraki_tetikleme_zamani = current_timestamp - interval '1 minute'
                 where uuid = :uuid
                """).param("uuid", view.uuid()).update();

        ScheduleFireService fireService = new ScheduleFireService(
                jdbc, store, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> fireService.tryFire(view.uuid()));

        ScheduleService.View after = service.get(projectUuid, view.uuid());
        assertEquals(ScheduleService.Status.ASKIDA, after.status());
        assertNull(after.nextFireTime());
        assertTrue(after.lastErrorMessage().contains("100"));
        assertEquals(100L, jdbc.sql("""
                select count(*) from akis.is_talebi where zamanlama_id = :schedule
                """).param("schedule", scheduleId).query(Long.class).single());
        assertEquals("QUEUE_LIMIT_SUSPENDED", jdbc.sql("""
                select ayrinti->>'outcome' from akis.denetim_olayi
                 where dis_nesne_uuid = :schedule and eylem_kodu = 'ZAMANLAMA_TETIKLEME'
                """).param("schedule", view.uuid()).query(String.class).single());
    }

    @Test
    void overlapSkipAdvancesAndPersistsItsDecision() {
        ScheduleService.View view = service.create(
                projectUuid, "OVERLAP_AUDIT", "Overlap audit", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.PINNED);
        ScheduleFireService fireService = new ScheduleFireService(
                jdbc, store, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        jdbc.sql("update akis.zamanlama set sonraki_tetikleme_zamani = now() - interval '1 minute' where uuid = :uuid")
                .param("uuid", view.uuid()).update();
        transaction.executeWithoutResult(status -> fireService.tryFire(view.uuid()));
        jdbc.sql("update akis.zamanlama set sonraki_tetikleme_zamani = now() - interval '20 seconds' where uuid = :uuid")
                .param("uuid", view.uuid()).update();
        transaction.executeWithoutResult(status -> fireService.tryFire(view.uuid()));

        assertEquals(1L, jdbc.sql("""
                select count(*) from akis.is_talebi
                 where zamanlama_id = (select id from akis.zamanlama where uuid = :uuid)
                """).param("uuid", view.uuid()).query(Long.class).single());
        assertEquals(List.of("FIRED", "OVERLAP_SKIPPED"), jdbc.sql("""
                select ayrinti->>'outcome' from akis.denetim_olayi
                 where dis_nesne_uuid = :uuid and eylem_kodu = 'ZAMANLAMA_TETIKLEME'
                 order by id
                """).param("uuid", view.uuid()).query(String.class).list());
        assertTrue(service.get(projectUuid, view.uuid()).nextFireTime().isAfter(OffsetDateTime.now()));
        assertEquals("OVERLAP_SKIPPED", service.triggerEvents(projectUuid, view.uuid()).getFirst().outcome());
        ScheduleService.View current = service.get(projectUuid, view.uuid());
        service.delete(projectUuid, view.uuid(), current.version());
        assertEquals("FIRED", service.triggerEvents(projectUuid, view.uuid()).getLast().outcome());
    }

    @Test
    void concurrentFireAttemptsCreateExactlyOneRun() throws InterruptedException {
        ScheduleService.View view = service.create(
                projectUuid, "UNIQUE", "Unique", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, null);

        // Move next fire time into the past so the scanner sees it.
        jdbc.sql("update akis.zamanlama set sonraki_tetikleme_zamani = now() - interval '1 minute' where uuid = :uuid")
                .param("uuid", view.uuid()).update();

        ScheduleFireService fireService = new ScheduleFireService(jdbc, store, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger success = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        var executor = Executors.newFixedThreadPool(workers);
        for (int i = 0; i < workers; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    transaction.executeWithoutResult(status -> fireService.tryFire(view.uuid()));
                    success.incrementAndGet();
                }
                catch (Exception exception) {
                    failure.compareAndSet(null, exception);
                }
                finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertTrue(finished);
        assertTrue(failure.get() == null, () -> "Concurrent schedule fire failed: " + failure.get());

        long runCount = jdbc.sql("select count(*) from akis.calistirma c join akis.is_talebi j on j.id = c.is_talebi_id where j.zamanlama_id = (select id from akis.zamanlama where uuid = :uuid)")
                .param("uuid", view.uuid()).query(Long.class).single();
        assertEquals(1L, runCount, "Only one run must be created for a single schedule occurrence");
    }

    @Test
    void expiredWindowNeverStartsAJob() {
        ScheduleService.View view = service.create(projectUuid, "EXPIRED_WINDOW", "Expired window", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.LATEST_ACTIVE);
        jdbc.sql("""
                update akis.zamanlama
                   set sonraki_tetikleme_zamani = now() - interval '1 minute',
                       bitis_zamani = now() - interval '1 second'
                 where uuid = :uuid
                """).param("uuid", view.uuid()).update();

        var fireService = new ScheduleFireService(jdbc, store, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> fireService.tryFire(view.uuid()));

        assertEquals("ASKIDA", jdbc.sql("select durum_kodu from akis.zamanlama where uuid = :uuid")
                .param("uuid", view.uuid()).query(String.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.is_talebi where zamanlama_id = (select id from akis.zamanlama where uuid = :uuid)")
                .param("uuid", view.uuid()).query(Long.class).single());
    }

    @Test
    void lastInWindowOccurrenceSuspendsAfterItFires() {
        ScheduleService.View view = service.create(projectUuid, "FINAL_WINDOW_FIRE", "Final window fire", publicationUuid,
                "0 0 1 1 1 ?", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.LATEST_ACTIVE);
        jdbc.sql("""
                update akis.zamanlama
                   set sonraki_tetikleme_zamani = now() - interval '1 minute',
                       bitis_zamani = now() + interval '5 minutes'
                 where uuid = :uuid
                """).param("uuid", view.uuid()).update();

        var fireService = new ScheduleFireService(jdbc, store, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> fireService.tryFire(view.uuid()));

        assertEquals("ASKIDA", jdbc.sql("select durum_kodu from akis.zamanlama where uuid = :uuid")
                .param("uuid", view.uuid()).query(String.class).single());
        assertEquals(1L, jdbc.sql("select count(*) from akis.is_talebi where zamanlama_id = (select id from akis.zamanlama where uuid = :uuid)")
                .param("uuid", view.uuid()).query(Long.class).single());
        assertTrue(jdbc.sql("select sonraki_tetikleme_zamani is null from akis.zamanlama where uuid = :uuid")
                .param("uuid", view.uuid()).query(Boolean.class).single());
    }

    @Test
    void latestActiveFollowsNewDefinitionVersionWhilePinnedKeepsTheAnchor() {
        long definitionId = jdbc.sql("""
                select t.id from akis.tanim t
                join akis.tanim_surumu v on v.tanim_id = t.id
                join akis.senaryo s on s.tanim_surumu_id = v.id
                join akis.yayin y on y.senaryo_id = s.id
                where y.uuid = :publication
                """).param("publication", publicationUuid).query(Long.class).single();
        long environmentId = jdbc.sql("select ortam_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        int nextVersion = jdbc.sql("select coalesce(max(surum_no), 0) + 1 from akis.tanim_surumu where tanim_id = :definition")
                .param("definition", definitionId).query(Integer.class).single();
        String versionHash = "d".repeat(64);
        long versionId = jdbc.sql("""
                insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik)
                values (:project,:definition,:version,1,:hash,'{"tasks":[]}') returning id
                """).param("project", projectId).param("definition", definitionId)
                .param("version", nextVersion).param("hash", versionHash).query(Long.class).single();
        long validationId = jdbc.sql("""
                insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi)
                values (:project,:version,:hash,'GECTI','{}') returning id
                """).param("project", projectId).param("version", versionId)
                .param("hash", versionHash).query(Long.class).single();
        long scenarioId = jdbc.sql("""
                insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan)
                values (:project,:version,:validation,1,2,:hash,'{}') returning id
                """).param("project", projectId).param("version", versionId)
                .param("validation", validationId).param("hash", "e".repeat(64))
                .query(Long.class).single();
        UUID latestPublication = jdbc.sql("""
                insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani)
                values (:project,:scenario,:environment,1,'AKTIF','ready',cast(:manifest as jsonb),current_timestamp)
                returning uuid
                """).param("project", projectId).param("scenario", scenarioId)
                .param("environment", environmentId)
                .param("manifest", "{\"releaseHash\":\"" + "f".repeat(64) + "\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}")
                .query(UUID.class).single();

        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var fireService = new ScheduleFireService(jdbc, store, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        assertScheduledPublication(transaction, fireService, "FOLLOW_LATEST", SchedulePublicationPolicy.LATEST_ACTIVE, latestPublication);
        assertScheduledPublication(transaction, fireService, "KEEP_PINNED", SchedulePublicationPolicy.PINNED, publicationUuid);
    }

    @Test
    void pinnedPublicationNoLongerActiveAutoSuspendsScheduleWithExplicitError() {
        long scenarioId = jdbc.sql("select senaryo_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        long environmentId = jdbc.sql("select ortam_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        UUID dedicatedPublication = jdbc.sql("""
                insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani)
                values (:project,:scenario,:environment,2,'AKTIF','ready',cast(:manifest as jsonb),current_timestamp)
                returning uuid
                """).param("project", projectId).param("scenario", scenarioId).param("environment", environmentId)
                .param("manifest", "{\"releaseHash\":\"" + "9".repeat(64) + "\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}")
                .query(UUID.class).single();

        ScheduleService.View view = service.create(projectUuid, "PINNED_SUSPEND", "Pinned Suspend", dedicatedPublication,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.PINNED);
        jdbc.sql("update akis.zamanlama set sonraki_tetikleme_zamani = now() - interval '1 minute' where uuid = :uuid")
                .param("uuid", view.uuid()).update();
        // The pinned target becomes invalid after the schedule was created against it.
        jdbc.sql("update akis.yayin set durum = 'ASKIDA' where uuid = :publication")
                .param("publication", dedicatedPublication).update();

        var fireService = new ScheduleFireService(jdbc, store, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> fireService.tryFire(view.uuid()));

        long runCount = jdbc.sql("""
                select count(*) from akis.calistirma c join akis.is_talebi j on j.id = c.is_talebi_id
                 where j.zamanlama_id = (select id from akis.zamanlama where uuid = :uuid)
                """).param("uuid", view.uuid()).query(Long.class).single();
        assertEquals(0L, runCount, "An invalid pinned publication must never fire a run");

        ScheduleService.View after = service.get(projectUuid, view.uuid());
        assertEquals(ScheduleService.Status.ASKIDA, after.status(), "Schedule must be safely auto-suspended, not left retrying silently");
        assertTrue(after.lastErrorMessage() != null && !after.lastErrorMessage().isBlank(), "An explicit suspension reason must be recorded");
        assertEquals(null, after.nextFireTime());
        assertEquals(null, after.resolvedPublicationUuid(), "Resolved publication must be null once the pinned target is inactive");
    }

    @Test
    void resolvedPublicationSeparatesFromConfiguredWhenLatestActiveMovesToNewerVersion() {
        long definitionId = jdbc.sql("""
                select t.id from akis.tanim t
                join akis.tanim_surumu v on v.tanim_id = t.id
                join akis.senaryo s on s.tanim_surumu_id = v.id
                join akis.yayin y on y.senaryo_id = s.id
                where y.uuid = :publication
                """).param("publication", publicationUuid).query(Long.class).single();
        long environmentId = jdbc.sql("select ortam_id from akis.yayin where uuid = :publication")
                .param("publication", publicationUuid).query(Long.class).single();
        int nextVersion = jdbc.sql("select coalesce(max(surum_no), 0) + 1 from akis.tanim_surumu where tanim_id = :definition")
                .param("definition", definitionId).query(Integer.class).single();
        String versionHash = "1".repeat(64);
        long versionId = jdbc.sql("""
                insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik)
                values (:project,:definition,:version,1,:hash,'{"tasks":[]}') returning id
                """).param("project", projectId).param("definition", definitionId)
                .param("version", nextVersion).param("hash", versionHash).query(Long.class).single();
        long validationId = jdbc.sql("""
                insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi)
                values (:project,:version,:hash,'GECTI','{}') returning id
                """).param("project", projectId).param("version", versionId)
                .param("hash", versionHash).query(Long.class).single();
        long scenarioId = jdbc.sql("""
                insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan)
                values (:project,:version,:validation,1,2,:hash,'{}') returning id
                """).param("project", projectId).param("version", versionId)
                .param("validation", validationId).param("hash", "2".repeat(64))
                .query(Long.class).single();
        UUID newerPublication = jdbc.sql("""
                insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani)
                values (:project,:scenario,:environment,1,'AKTIF','ready',cast(:manifest as jsonb),current_timestamp)
                returning uuid
                """).param("project", projectId).param("scenario", scenarioId)
                .param("environment", environmentId)
                .param("manifest", "{\"releaseHash\":\"" + "3".repeat(64) + "\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}")
                .query(UUID.class).single();

        ScheduleService.View latest = service.create(projectUuid, "RESOLVE_LATEST", "Resolve Latest", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.LATEST_ACTIVE);
        ScheduleService.View pinned = service.create(projectUuid, "RESOLVE_PINNED", "Resolve Pinned", publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, SchedulePublicationPolicy.PINNED);

        ScheduleService.View latestView = service.get(projectUuid, latest.uuid());
        ScheduleService.View pinnedView = service.get(projectUuid, pinned.uuid());

        assertEquals(publicationUuid, latestView.publicationUuid(), "Configured anchor must stay the original publication");
        assertEquals(newerPublication, latestView.resolvedPublicationUuid(),
                "LATEST_ACTIVE must resolve to the newest active publication for the same definition+environment");
        assertEquals(nextVersion, latestView.resolvedVersionNumber());
        assertEquals(publicationUuid, pinnedView.publicationUuid());
        assertEquals(publicationUuid, pinnedView.resolvedPublicationUuid(), "PINNED resolves to itself while still active");
    }

    private void assertScheduledPublication(TransactionTemplate transaction, ScheduleFireService fireService,
            String code, SchedulePublicationPolicy policy, UUID expectedPublication) {
        ScheduleService.View view = service.create(projectUuid, code, code, publicationUuid,
                "0 0 * * * *", "UTC", ScheduleService.ConflictPolicy.SKIP,
                ScheduleService.MisfirePolicy.SKIP, policy);
        jdbc.sql("update akis.zamanlama set sonraki_tetikleme_zamani = now() - interval '1 minute' where uuid = :uuid")
                .param("uuid", view.uuid()).update();
        transaction.executeWithoutResult(status -> fireService.tryFire(view.uuid()));
        UUID selectedPublication = jdbc.sql("""
                select y.uuid from akis.is_talebi j
                join akis.yayin y on y.id = j.yayin_id
                join akis.zamanlama z on z.id = j.zamanlama_id
                where z.uuid = :schedule
                """).param("schedule", view.uuid()).query(UUID.class).single();
        assertEquals(expectedPublication, selectedPublication);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
}

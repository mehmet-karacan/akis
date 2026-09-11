package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;
import tr.com.innova.akis.execution.RunReconciliationPort.Conflict;
import tr.com.innova.akis.execution.RunReconciliationPort.MutationOutcome;
import tr.com.innova.akis.execution.RunReconciliationPort.NotPublished;
import tr.com.innova.akis.execution.RunReconciliationPort.PublishEvidence;
import tr.com.innova.akis.execution.RunReconciliationPort.Published;
import tr.com.innova.akis.execution.RunReconciliationPort.ReconciliationLeaseToken;

class JdbcRunReconciliationStoreIT {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String TARGET_HASH = "c".repeat(64);
    private static final String PUBLISH_KEY_HASH = "d".repeat(64);
    private static final String PAYLOAD_HASH = "e".repeat(64);
    private static final String RUNTIME_PLAN_HASH = "f".repeat(64);

    private static AnnotationConfigApplicationContext context;
    private static JdbcRunLeaseStore runLeases;
    private static JdbcRunReconciliationStore reconciliations;
    private static JdbcClient jdbc;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabaseContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        Flyway.configure()
                .dataSource(context.getBean(DataSource.class))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        runLeases = context.getBean(JdbcRunLeaseStore.class);
        reconciliations = context.getBean(JdbcRunReconciliationStore.class);
        jdbc = context.getBean(JdbcClient.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopDatabaseContext() {
        context.close();
    }

    @BeforeEach
    void createQueuedRunFixture() {
        jdbcTemplate.execute("""
                SET search_path TO entegrasyon, public;
                TRUNCATE TABLE proje, worker_profili RESTART IDENTITY CASCADE;
                INSERT INTO proje(kod, ad) VALUES ('RECON_IT', 'Reconciliation integration test');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod = 'RECON_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'MAPPING', 'RECON_MAP', 'Reconciliation mapping'
                  FROM proje p JOIN klasor k ON k.proje_id = p.id
                 WHERE p.kod = 'RECON_IT';
                INSERT INTO tanim_surumu(
                    tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 1, encode(sha256(convert_to('reconciliation-definition', 'UTF8')), 'hex'),
                       '{}'::jsonb
                  FROM tanim WHERE kod = 'RECON_MAP';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test' FROM proje WHERE kod = 'RECON_IT';
                INSERT INTO dogrulama(
                    tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'RECON_MAP'
                  JOIN ortam o ON o.proje_id = t.proje_id AND o.kod = 'TEST';
                INSERT INTO senaryo(
                    tanim_surumu_id, dogrulama_id, surum_no, plan_surumu, plan_ozeti, plan)
                SELECT d.tanim_surumu_id, d.id, 1, 1,
                       repeat('b', 64), '{}'::jsonb
                  FROM dogrulama d
                  JOIN tanim_surumu ts ON ts.id = d.tanim_surumu_id
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'RECON_MAP';
                INSERT INTO yayin(
                    proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
                    bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani)
                SELECT p.id, s.id, o.id, 1, 'AKTIF', repeat('d', 64),
                       jsonb_build_object(
                           'releaseHash', repeat('a', 64),
                           'runtimeCapability', 'ORACLE_TABLE_COPY_V1',
                           'runtimePlanHash', repeat('f', 64)), current_timestamp
                  FROM proje p
                  JOIN ortam o ON o.proje_id = p.id AND o.kod = 'TEST'
                  JOIN tanim t ON t.proje_id = p.id AND t.kod = 'RECON_MAP'
                  JOIN tanim_surumu ts ON ts.tanim_id = t.id
                  JOIN senaryo s ON s.tanim_surumu_id = ts.id
                 WHERE p.kod = 'RECON_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('RECON_IT_WORKER', '{}'::jsonb, 'Reconciliation worker');
                INSERT INTO is_talebi(
                    proje_id, yayin_id, istek_ozeti, is_turu, parametre)
                SELECT proje_id, id, repeat('e', 64), 'RUN', '{}'::jsonb
                  FROM yayin;
                INSERT INTO calistirma(
                    proje_id, is_talebi_id, deneme_no, yayin_ozeti,
                    plan_ozeti, baslatma_turu)
                SELECT proje_id, id, 1, repeat('a', 64), repeat('b', 64), 'ILK'
                  FROM is_talebi;
                INSERT INTO calistirma_durumu(
                    proje_id, calistirma_id, durum_kodu, son_olay_no)
                SELECT proje_id, id, 'BEKLIYOR', 1 FROM calistirma;
                INSERT INTO calistirma_olayi(
                    proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
                SELECT proje_id, id, 1, 'RUN_QUEUED', current_timestamp, '{}'::jsonb
                  FROM calistirma;
                """);
    }

    @Test
    void claimsIdempotentlyHeartbeatsAndRejectsAStaleToken() {
        AmbiguousRun ambiguous = ambiguousRun(false, "recon-original-claim");
        WorkerIdentity worker = worker("recon-worker-claim");

        ReconciliationLeaseToken claimed = reconciliations.claim(
                        ambiguous.run().runUuid(), worker, Duration.ofSeconds(60))
                .orElseThrow();
        assertEquals(ambiguous.run().generation() + 1, claimed.runGeneration());
        assertEquals(ambiguous.target().targetGeneration() + 1,
                claimed.targetGeneration());
        assertEquals(ambiguous.target().targetResourceUuid(),
                claimed.targetResourceUuid());
        assertNotNull(claimed.leaseDeadline());
        assertEquals(claimed, reconciliations.claim(
                        ambiguous.run().runUuid(), worker, Duration.ofSeconds(120))
                .orElseThrow());

        var heartbeat = reconciliations.heartbeat(claimed, Duration.ofSeconds(60));
        assertEquals(MutationOutcome.ACCEPTED, heartbeat.outcome());
        assertNotNull(heartbeat.refreshedToken());
        assertTrue(!heartbeat.refreshedToken().leaseDeadline()
                .isBefore(claimed.leaseDeadline()));

        OffsetDateTime deadlineBeforeStaleHeartbeat = runLeaseDeadline(claimed.runUuid());
        ReconciliationLeaseToken stale = new ReconciliationLeaseToken(
                claimed.runUuid(), claimed.workerReference(), claimed.runGeneration(),
                claimed.targetResourceUuid(), claimed.targetGeneration() + 1,
                claimed.leaseDeadline());
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                reconciliations.heartbeat(stale, Duration.ofSeconds(60)).outcome());
        assertEquals(deadlineBeforeStaleHeartbeat, runLeaseDeadline(claimed.runUuid()));
        assertTrue(reconciliations.claim(
                claimed.runUuid(), worker("other-worker"), Duration.ofSeconds(60)).isEmpty());
    }

    @Test
    void completesPublishedWithImmutableEvidenceAndBothTargetGenerations() {
        AmbiguousRun ambiguous = ambiguousRun(true, "recon-original-published");
        ReconciliationLeaseToken claimed = claim(
                ambiguous.run().runUuid(), "recon-worker-published");

        var result = reconciliations.complete(claimed, new Published(evidence()));

        assertEquals(MutationOutcome.ACCEPTED, result.outcome());
        assertEquals("BASARILI", runStatus(claimed.runUuid()));
        assertEquals("BOS", targetStatus(claimed.targetResourceUuid()));
        assertEquals(1, jdbc.sql("""
                        select count(*)
                          from entegrasyon.kontrol_noktasi kn
                          join entegrasyon.calistirma c on c.id = kn.calistirma_id
                         where c.uuid = :runUuid
                           and kn.hedef_nesil_no = :publishGeneration
                           and kn.mutabakat_hedef_nesil_no = :barrierGeneration
                           and (kn.imlec ->> 'reconciled')::boolean is true
                        """)
                .param("runUuid", claimed.runUuid())
                .param("publishGeneration", ambiguous.target().targetGeneration())
                .param("barrierGeneration", claimed.targetGeneration())
                .query(Integer.class)
                .single());
        assertEquals(MutationOutcome.ACCEPTED,
                reconciliations.complete(claimed, new Published(evidence())).outcome());
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                reconciliations.complete(claimed, new Published(new PublishEvidence(
                        RUNTIME_PLAN_HASH, PUBLISH_KEY_HASH, "9".repeat(64), 33, 1024)))
                        .outcome());
    }

    @Test
    void completesNotPublishedWithoutAcceptingEvidence() {
        AmbiguousRun ambiguous = ambiguousRun(false, "recon-original-not-published");
        ReconciliationLeaseToken claimed = claim(
                ambiguous.run().runUuid(), "recon-worker-not-published");

        var result = reconciliations.complete(claimed, new NotPublished());

        assertEquals(MutationOutcome.ACCEPTED, result.outcome());
        assertEquals("YENIDEN_DENENEBILIR", runStatus(claimed.runUuid()));
        assertEquals("BOS", targetStatus(claimed.targetResourceUuid()));
        assertEquals(0, checkpointCount(claimed.runUuid()));
        assertEquals(MutationOutcome.ACCEPTED,
                reconciliations.complete(claimed, new NotPublished()).outcome());
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                reconciliations.complete(claimed, new Conflict()).outcome());
    }

    @Test
    void completesConflictAndKeepsTheTargetSuspended() {
        AmbiguousRun ambiguous = ambiguousRun(true, "recon-original-conflict");
        ReconciliationLeaseToken claimed = claim(
                ambiguous.run().runUuid(), "recon-worker-conflict");

        var result = reconciliations.complete(claimed, new Conflict());

        assertEquals(MutationOutcome.ACCEPTED, result.outcome());
        assertEquals("MUDAHALE_GEREKLI", runStatus(claimed.runUuid()));
        assertEquals("ASKIDA", targetStatus(claimed.targetResourceUuid()));
        assertEquals(0, checkpointCount(claimed.runUuid()));
        assertEquals(MutationOutcome.ACCEPTED,
                reconciliations.complete(claimed, new Conflict()).outcome());
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                reconciliations.complete(claimed, new NotPublished()).outcome());
    }

    @Test
    void rejectsInvalidDurationsTokensAndPublishedEvidenceBeforeDatabaseMutation() {
        AmbiguousRun ambiguous = ambiguousRun(true, "recon-original-invalid");
        WorkerIdentity worker = worker("recon-worker-invalid");

        assertThrows(IllegalArgumentException.class, () -> reconciliations.claim(
                ambiguous.run().runUuid(), worker, Duration.ofSeconds(29)));
        assertThrows(IllegalArgumentException.class, () -> reconciliations.claim(
                ambiguous.run().runUuid(), worker, Duration.ofMillis(30_001)));

        ReconciliationLeaseToken claimed = reconciliations.claim(
                        ambiguous.run().runUuid(), worker, Duration.ofSeconds(300))
                .orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> reconciliations.complete(claimed, null));
        assertThrows(IllegalArgumentException.class,
                () -> reconciliations.complete(claimed, new Published(
                        new PublishEvidence("bad", PUBLISH_KEY_HASH, PAYLOAD_HASH, 33, 1024))));
        assertThrows(IllegalArgumentException.class, () -> reconciliations.heartbeat(
                new ReconciliationLeaseToken(
                        claimed.runUuid(), claimed.workerReference(), 0,
                        claimed.targetResourceUuid(), claimed.targetGeneration(),
                        OffsetDateTime.now()),
                Duration.ofSeconds(60)));
        assertEquals("MUTABAKAT", runStatus(claimed.runUuid()));
        assertEquals("ASKIDA", targetStatus(claimed.targetResourceUuid()));
    }

    private AmbiguousRun ambiguousRun(boolean publish, String workerReference) {
        ClaimedRun claimed = runLeases.claimForPreflight(
                        worker(workerReference), Duration.ofSeconds(60))
                .orElseThrow();
        TargetFenceToken target = runLeases.acquireTarget(
                claimed.token(), TARGET_HASH, 1);
        assertTrue(startWork(claimed.token(), target));
        if (publish) {
            assertTrue(startPublish(claimed.token(), target));
        }
        assertTrue(jdbc.sql("""
                        select entegrasyon.calistirma_sonucu_belirsiz_isaretle(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration)
                        """)
                .param("runUuid", claimed.token().runUuid())
                .param("workerReference", claimed.token().workerReference())
                .param("runGeneration", claimed.token().generation())
                .param("targetUuid", target.targetResourceUuid())
                .param("targetGeneration", target.targetGeneration())
                .query(Boolean.class)
                .single());
        return new AmbiguousRun(claimed.token(), target);
    }

    private ReconciliationLeaseToken claim(UUID runUuid, String workerReference) {
        return reconciliations.claim(
                        runUuid, worker(workerReference), Duration.ofSeconds(60))
                .orElseThrow();
    }

    private WorkerIdentity worker(String reference) {
        UUID profileUuid = jdbc.sql("""
                        select uuid from entegrasyon.worker_profili
                         where kod = 'RECON_IT_WORKER'
                        """)
                .query(UUID.class)
                .single();
        return new WorkerIdentity(reference, profileUuid);
    }

    private boolean startWork(RunLeaseToken run, TargetFenceToken target) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_calismaya_baslat(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration)
                        """)
                .param("runUuid", run.runUuid())
                .param("workerReference", run.workerReference())
                .param("runGeneration", run.generation())
                .param("targetUuid", target.targetResourceUuid())
                .param("targetGeneration", target.targetGeneration())
                .query(Boolean.class)
                .single();
    }

    private boolean startPublish(RunLeaseToken run, TargetFenceToken target) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_yayina_gec(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration, :runtimePlanHash,
                            :publishKeyHash, :payloadHash, 33, 1024)
                        """)
                .param("runUuid", run.runUuid())
                .param("workerReference", run.workerReference())
                .param("runGeneration", run.generation())
                .param("targetUuid", target.targetResourceUuid())
                .param("targetGeneration", target.targetGeneration())
                .param("runtimePlanHash", RUNTIME_PLAN_HASH)
                .param("publishKeyHash", PUBLISH_KEY_HASH)
                .param("payloadHash", PAYLOAD_HASH)
                .query(Boolean.class)
                .single();
    }

    private PublishEvidence evidence() {
        return new PublishEvidence(
                RUNTIME_PLAN_HASH, PUBLISH_KEY_HASH, PAYLOAD_HASH, 33, 1024);
    }

    private String runStatus(UUID runUuid) {
        return jdbc.sql("""
                        select cd.durum_kodu
                          from entegrasyon.calistirma_durumu cd
                          join entegrasyon.calistirma c on c.id = cd.calistirma_id
                         where c.uuid = :runUuid
                        """)
                .param("runUuid", runUuid)
                .query(String.class)
                .single();
    }

    private String targetStatus(UUID targetUuid) {
        return jdbc.sql("""
                        select durum_kodu from entegrasyon.hedef_kaynagi
                         where uuid = :targetUuid
                        """)
                .param("targetUuid", targetUuid)
                .query(String.class)
                .single();
    }

    private OffsetDateTime runLeaseDeadline(UUID runUuid) {
        return jdbc.sql("""
                        select cd.kiralama_bitis_zamani
                          from entegrasyon.calistirma_durumu cd
                          join entegrasyon.calistirma c on c.id = cd.calistirma_id
                         where c.uuid = :runUuid
                        """)
                .param("runUuid", runUuid)
                .query(OffsetDateTime.class)
                .single();
    }

    private int checkpointCount(UUID runUuid) {
        return jdbc.sql("""
                        select count(*)
                          from entegrasyon.kontrol_noktasi kn
                          join entegrasyon.calistirma c on c.id = kn.calistirma_id
                         where c.uuid = :runUuid
                        """)
                .param("runUuid", runUuid)
                .query(Integer.class)
                .single();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(IntegrationTestDatabase.requireIsolatedUrl(
                    requiredEnvironment("SPRING_DATASOURCE_URL")));
            dataSource.setUsername(requiredEnvironment("SPRING_DATASOURCE_USERNAME"));
            dataSource.setPassword(requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        JdbcRunLeaseStore jdbcRunLeaseStore(JdbcClient jdbcClient) {
            return new JdbcRunLeaseStore(jdbcClient);
        }

        @Bean
        JdbcRunReconciliationStore jdbcRunReconciliationStore(JdbcClient jdbcClient) {
            return new JdbcRunReconciliationStore(jdbcClient);
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required for the JDBC integration test.");
            }
            return value;
        }
    }

    private record AmbiguousRun(RunLeaseToken run, TargetFenceToken target) {
    }
}

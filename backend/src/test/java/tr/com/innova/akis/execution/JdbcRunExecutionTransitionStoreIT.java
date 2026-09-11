package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
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

import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.MutationOutcome;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.PublishIntentEvidence;
import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

class JdbcRunExecutionTransitionStoreIT {

    private static final String TARGET_HASH = "c".repeat(64);
    private static final String RUNTIME_PLAN_HASH = "f".repeat(64);
    private static final String PUBLISH_KEY_HASH = "d".repeat(64);
    private static final String PAYLOAD_HASH = "e".repeat(64);
    private static final PublishIntentEvidence EVIDENCE =
            new PublishIntentEvidence(
                    RUNTIME_PLAN_HASH, PUBLISH_KEY_HASH, PAYLOAD_HASH, 33, 1024);

    private static AnnotationConfigApplicationContext context;
    private static JdbcRunLeaseStore leases;
    private static JdbcRunExecutionTransitionStore transitions;
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
        leases = context.getBean(JdbcRunLeaseStore.class);
        transitions = context.getBean(JdbcRunExecutionTransitionStore.class);
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
                INSERT INTO proje(kod, ad) VALUES ('TRANSITION_IT', 'Transition IT');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod = 'TRANSITION_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'MAPPING', 'TRANSITION_MAP', 'Mapping'
                  FROM proje p JOIN klasor k ON k.proje_id = p.id
                 WHERE p.kod = 'TRANSITION_IT';
                INSERT INTO tanim_surumu(
                    tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 1,
                       encode(sha256(convert_to('transition-definition', 'UTF8')), 'hex'),
                       '{}'::jsonb
                  FROM tanim WHERE kod = 'TRANSITION_MAP';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test'
                  FROM proje WHERE kod = 'TRANSITION_IT';
                INSERT INTO dogrulama(
                    tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'TRANSITION_MAP'
                  JOIN ortam o ON o.proje_id = t.proje_id AND o.kod = 'TEST';
                INSERT INTO senaryo(
                    tanim_surumu_id, dogrulama_id, surum_no,
                    plan_surumu, plan_ozeti, plan)
                SELECT d.tanim_surumu_id, d.id, 1, 1, repeat('b', 64), '{}'::jsonb
                  FROM dogrulama d
                  JOIN tanim_surumu ts ON ts.id = d.tanim_surumu_id
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'TRANSITION_MAP';
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
                  JOIN tanim t ON t.proje_id = p.id AND t.kod = 'TRANSITION_MAP'
                  JOIN tanim_surumu ts ON ts.tanim_id = t.id
                  JOIN senaryo s ON s.tanim_surumu_id = ts.id
                 WHERE p.kod = 'TRANSITION_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('TRANSITION_IT_WORKER', '{}'::jsonb, 'Transition worker');
                INSERT INTO is_talebi(
                    proje_id, yayin_id, istek_ozeti, is_turu, parametre)
                SELECT proje_id, id,
                       encode(sha256(convert_to('transition-request-' || g, 'UTF8')), 'hex'),
                       'RUN', '{}'::jsonb
                  FROM yayin CROSS JOIN generate_series(1, 2) g;
                INSERT INTO calistirma(
                    proje_id, is_talebi_id, deneme_no,
                    yayin_ozeti, plan_ozeti, baslatma_turu)
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
    void exactRetriesAcknowledgePreflightPublishAndSuccess() {
        ActiveExecutionToken token = activeToken("transition-success");

        assertAccepted(transitions.completePreflight(token));
        assertAccepted(transitions.completePreflight(token));
        assertEquals("CALISIYOR", runStatus(token));
        assertEquals(2, count("calistirma_adimi", token));

        assertAccepted(transitions.beginPublish(token, EVIDENCE));
        assertAccepted(transitions.beginPublish(token, EVIDENCE));
        assertEquals("YAYINLANIYOR", runStatus(token));
        assertEquals(1, count("pilot_yayin_niyeti", token));

        assertAccepted(transitions.completeSuccessfully(token, EVIDENCE));
        assertAccepted(transitions.completeSuccessfully(token, EVIDENCE));
        assertEquals("BASARILI", runStatus(token));
        assertEquals("BOS", targetStatus(token));
        assertEquals(1, count("kontrol_noktasi", token));

        ActiveExecutionToken reused = activeToken("transition-reuse");
        assertEquals(token.target().targetResourceUuid(),
                reused.target().targetResourceUuid());
        assertEquals(token.target().targetGeneration() + 1,
                reused.target().targetGeneration());
        assertAccepted(transitions.completeSuccessfully(token, EVIDENCE));

        PublishIntentEvidence wrongCounts = new PublishIntentEvidence(
                RUNTIME_PLAN_HASH, PUBLISH_KEY_HASH, PAYLOAD_HASH, 34, 1024);
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                transitions.completeSuccessfully(token, wrongCounts).outcome());
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                transitions.completeSuccessfully(
                        tokenWithWorker(token, "forged-worker"), EVIDENCE).outcome());
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                transitions.completeSuccessfully(
                        tokenWithRunGeneration(token, token.run().generation() + 1),
                        EVIDENCE).outcome());
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                transitions.completeSuccessfully(
                        tokenWithTargetUuid(token, UUID.randomUUID()), EVIDENCE).outcome());
    }

    @Test
    void exactSafeFailureRetryUsesTerminalReadback() {
        ActiveExecutionToken token = activeToken("transition-safe-failure");
        assertAccepted(transitions.completePreflight(token));

        assertAccepted(transitions.failSafely(token, "PREFLIGHT_REJECTED"));
        assertAccepted(transitions.failSafely(token, "PREFLIGHT_REJECTED"));
        assertEquals("BASARISIZ", runStatus(token));
        assertEquals("BOS", targetStatus(token));
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                transitions.failSafely(token, "DIFFERENT_ERROR").outcome());
    }

    @Test
    void exactPreflightFailureRetryWorksBeforeTargetAcquisition() {
        RunLeaseToken token = claimedRun("transition-preflight-failure").token();

        assertAccepted(transitions.failPreflightSafely(token, "SCHEMA_DRIFT"));
        assertAccepted(transitions.failPreflightSafely(token, "SCHEMA_DRIFT"));
        assertEquals("BASARISIZ", runStatus(token));
        assertEquals(MutationOutcome.REJECTED_FAIL_CLOSED,
                transitions.failPreflightSafely(
                        new RunLeaseToken(
                                token.runUuid(), "forged-worker", token.generation(),
                                token.leaseDeadline()),
                        "SCHEMA_DRIFT").outcome());
    }

    @Test
    void exactUnknownRetryUsesTerminalReadback() {
        ActiveExecutionToken token = activeToken("transition-unknown");
        assertAccepted(transitions.completePreflight(token));
        assertAccepted(transitions.beginPublish(token, EVIDENCE));

        assertAccepted(transitions.markOutcomeUnknown(token));
        assertAccepted(transitions.markOutcomeUnknown(token));
        assertEquals("SONUC_BELIRSIZ", runStatus(token));
        assertEquals("ASKIDA", targetStatus(token));
    }

    private ActiveExecutionToken activeToken(String workerReference) {
        ClaimedRun claimed = claimedRun(workerReference);
        TargetFenceToken target = leases.acquireTarget(claimed.token(), TARGET_HASH, 1);
        return new ActiveExecutionToken(claimed.token(), target);
    }

    private ClaimedRun claimedRun(String workerReference) {
        UUID profileUuid = jdbc.sql("""
                        select uuid from entegrasyon.worker_profili
                         where kod = 'TRANSITION_IT_WORKER'
                        """)
                .query(UUID.class)
                .single();
        return leases.claimForPreflight(
                        new WorkerIdentity(workerReference, profileUuid),
                        Duration.ofSeconds(60))
                .orElseThrow();
    }

    private ActiveExecutionToken tokenWithWorker(
            ActiveExecutionToken token, String workerReference) {
        RunLeaseToken run = new RunLeaseToken(
                token.run().runUuid(), workerReference,
                token.run().generation(), token.run().leaseDeadline());
        TargetFenceToken target = new TargetFenceToken(
                token.target().runUuid(), workerReference,
                token.target().runGeneration(), token.target().targetResourceUuid(),
                token.target().targetGeneration(), token.target().canonicalTargetHash(),
                token.target().targetIdentityVersion());
        return new ActiveExecutionToken(run, target);
    }

    private ActiveExecutionToken tokenWithRunGeneration(
            ActiveExecutionToken token, long runGeneration) {
        RunLeaseToken run = new RunLeaseToken(
                token.run().runUuid(), token.run().workerReference(),
                runGeneration, token.run().leaseDeadline());
        TargetFenceToken target = new TargetFenceToken(
                token.target().runUuid(), token.target().workerReference(),
                runGeneration, token.target().targetResourceUuid(),
                token.target().targetGeneration(), token.target().canonicalTargetHash(),
                token.target().targetIdentityVersion());
        return new ActiveExecutionToken(run, target);
    }

    private ActiveExecutionToken tokenWithTargetUuid(
            ActiveExecutionToken token, UUID targetUuid) {
        TargetFenceToken target = new TargetFenceToken(
                token.target().runUuid(), token.target().workerReference(),
                token.target().runGeneration(), targetUuid,
                token.target().targetGeneration(), token.target().canonicalTargetHash(),
                token.target().targetIdentityVersion());
        return new ActiveExecutionToken(token.run(), target);
    }

    private String runStatus(ActiveExecutionToken token) {
        return runStatus(token.run());
    }

    private String runStatus(RunLeaseToken token) {
        return jdbc.sql("""
                        select cd.durum_kodu
                          from entegrasyon.calistirma_durumu cd
                          join entegrasyon.calistirma c on c.id = cd.calistirma_id
                         where c.uuid = :runUuid
                        """)
                .param("runUuid", token.runUuid())
                .query(String.class)
                .single();
    }

    private String targetStatus(ActiveExecutionToken token) {
        return jdbc.sql("""
                        select durum_kodu from entegrasyon.hedef_kaynagi
                         where uuid = :targetUuid
                        """)
                .param("targetUuid", token.target().targetResourceUuid())
                .query(String.class)
                .single();
    }

    private int count(String table, ActiveExecutionToken token) {
        String ownerColumn = table.equals("calistirma_adimi")
                || table.equals("pilot_yayin_niyeti")
                || table.equals("kontrol_noktasi")
                ? "calistirma_id"
                : "id";
        return jdbc.sql("select count(*) from entegrasyon." + table
                        + " where " + ownerColumn + " = ("
                        + "select id from entegrasyon.calistirma where uuid = :runUuid)")
                .param("runUuid", token.run().runUuid())
                .query(Integer.class)
                .single();
    }

    private void assertAccepted(RunExecutionTransitionPort.MutationResult result) {
        assertEquals(MutationOutcome.ACCEPTED, result.outcome());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(requiredEnvironment("SPRING_DATASOURCE_URL"));
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
        JdbcRunExecutionTransitionStore transitionStore(JdbcClient jdbcClient) {
            return new JdbcRunExecutionTransitionStore(jdbcClient);
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        name + " is required for the JDBC integration test.");
            }
            return value;
        }
    }
}

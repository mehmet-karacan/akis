package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatOutcome;
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatResult;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

class JdbcRunLeaseStoreIT {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String TARGET_HASH = "c".repeat(64);

    private static AnnotationConfigApplicationContext context;
    private static JdbcRunLeaseStore store;
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
        store = context.getBean(JdbcRunLeaseStore.class);
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
                INSERT INTO proje(kod, ad) VALUES ('LEASE_IT', 'Lease integration test');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod = 'LEASE_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'MAPPING', 'LEASE_MAP', 'Lease mapping'
                  FROM proje p JOIN klasor k ON k.proje_id = p.id
                 WHERE p.kod = 'LEASE_IT';
                INSERT INTO tanim_surumu(
                    tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 1, encode(sha256(convert_to('lease-definition', 'UTF8')), 'hex'),
                       '{}'::jsonb
                  FROM tanim WHERE kod = 'LEASE_MAP';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test' FROM proje WHERE kod = 'LEASE_IT';
                INSERT INTO dogrulama(
                    tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'LEASE_MAP'
                  JOIN ortam o ON o.proje_id = t.proje_id AND o.kod = 'TEST';
                INSERT INTO senaryo(
                    tanim_surumu_id, dogrulama_id, surum_no, plan_surumu, plan_ozeti, plan)
                SELECT d.tanim_surumu_id, d.id, 1, 1,
                       repeat('b', 64), '{}'::jsonb
                  FROM dogrulama d
                  JOIN tanim_surumu ts ON ts.id = d.tanim_surumu_id
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'LEASE_MAP';
                INSERT INTO yayin(
                    proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
                    bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani)
                SELECT p.id, s.id, o.id, 1, 'AKTIF', repeat('d', 64),
                       jsonb_build_object('releaseHash', repeat('a', 64)), current_timestamp
                  FROM proje p
                  JOIN ortam o ON o.proje_id = p.id AND o.kod = 'TEST'
                  JOIN tanim t ON t.proje_id = p.id AND t.kod = 'LEASE_MAP'
                  JOIN tanim_surumu ts ON ts.tanim_id = t.id
                  JOIN senaryo s ON s.tanim_surumu_id = ts.id
                 WHERE p.kod = 'LEASE_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('LEASE_IT_WORKER', '{}'::jsonb, 'Lease test worker');
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
    void realPostgresRoundTripsClaimTargetHeartbeatAndStaleRejection() {
        UUID profileUuid = jdbc.sql("""
                        select uuid from entegrasyon.worker_profili
                         where kod = 'LEASE_IT_WORKER'
                        """)
                .query(UUID.class)
                .single();

        ClaimedRun claimed = store.claimForPreflight(
                        new WorkerIdentity("lease-it-worker-1", profileUuid),
                        Duration.ofSeconds(60))
                .orElseThrow();

        assertEquals(RELEASE_HASH, claimed.releaseHash());
        assertEquals(PLAN_HASH, claimed.planHash());
        assertEquals(1, claimed.token().generation());
        assertNotNull(claimed.token().leaseDeadline());

        TargetFenceToken target = store.acquireTarget(claimed.token(), TARGET_HASH, 1);
        assertEquals(claimed.token().runUuid(), target.runUuid());
        assertEquals(claimed.token().generation(), target.runGeneration());
        assertEquals(1, target.targetGeneration());

        HeartbeatResult heartbeat = store.heartbeat(
                claimed.token(), Duration.ofSeconds(60));
        assertEquals(HeartbeatOutcome.ACCEPTED, heartbeat.outcome());
        assertNotNull(heartbeat.refreshedToken());
        assertTrue(heartbeat.refreshedToken().leaseDeadline()
                .isAfter(claimed.token().leaseDeadline()));

        OffsetDateTime targetDeadline = jdbc.sql("""
                        select kiralama_bitis_zamani
                          from entegrasyon.hedef_kaynagi
                         where uuid = :targetUuid
                        """)
                .param("targetUuid", target.targetResourceUuid())
                .query(OffsetDateTime.class)
                .single();
        assertEquals(heartbeat.refreshedToken().leaseDeadline(), targetDeadline);

        RunLeaseToken stale = new RunLeaseToken(
                claimed.token().runUuid(), claimed.token().workerReference(),
                claimed.token().generation() + 1, claimed.token().leaseDeadline());
        assertEquals(
                HeartbeatOutcome.REJECTED_FAIL_CLOSED,
                store.heartbeat(stale, Duration.ofSeconds(60)).outcome());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            String url = requiredEnvironment("SPRING_DATASOURCE_URL");
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(url);
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

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required for the JDBC integration test.");
            }
            return value;
        }
    }
}

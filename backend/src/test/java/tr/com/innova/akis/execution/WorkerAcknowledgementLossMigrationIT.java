package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WorkerAcknowledgementLossMigrationIT {

    private static final String TARGET_HASH = "c".repeat(64);
    private static final String RUNTIME_PLAN_HASH = "f".repeat(64);
    private static final String PUBLISH_KEY_HASH = "d".repeat(64);
    private static final String PAYLOAD_HASH = "e".repeat(64);

    private static AnnotationConfigApplicationContext context;
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
        jdbc = context.getBean(JdbcClient.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopDatabaseContext() {
        context.close();
    }

    @BeforeEach
    void createTwoQueuedRuns() {
        jdbcTemplate.execute("""
                SET search_path TO entegrasyon, public;
                TRUNCATE TABLE proje, worker_profili RESTART IDENTITY CASCADE;
                INSERT INTO proje(kod, ad) VALUES ('ACK_IT', 'Worker ACK integration test');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod = 'ACK_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'MAPPING', 'ACK_MAP', 'ACK mapping'
                  FROM proje p JOIN klasor k ON k.proje_id = p.id
                 WHERE p.kod = 'ACK_IT';
                INSERT INTO tanim_surumu(
                    tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 1,
                       encode(sha256(convert_to('ack-definition', 'UTF8')), 'hex'),
                       '{}'::jsonb
                  FROM tanim WHERE kod = 'ACK_MAP';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test' FROM proje WHERE kod = 'ACK_IT';
                INSERT INTO dogrulama(
                    tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'ACK_MAP'
                  JOIN ortam o ON o.proje_id = t.proje_id AND o.kod = 'TEST';
                INSERT INTO senaryo(
                    tanim_surumu_id, dogrulama_id, surum_no,
                    plan_surumu, plan_ozeti, plan)
                SELECT d.tanim_surumu_id, d.id, 1, 1, repeat('b', 64), '{}'::jsonb
                  FROM dogrulama d
                  JOIN tanim_surumu ts ON ts.id = d.tanim_surumu_id
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'ACK_MAP';
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
                  JOIN tanim t ON t.proje_id = p.id AND t.kod = 'ACK_MAP'
                  JOIN tanim_surumu ts ON ts.tanim_id = t.id
                  JOIN senaryo s ON s.tanim_surumu_id = ts.id
                 WHERE p.kod = 'ACK_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('ACK_IT_WORKER', '{}'::jsonb, 'ACK test worker');
                INSERT INTO is_talebi(
                    proje_id, yayin_id, istek_ozeti, is_turu, parametre)
                SELECT y.proje_id, y.id,
                       encode(sha256(convert_to('ack-request-' || g, 'UTF8')), 'hex'),
                       'RUN', '{}'::jsonb
                  FROM yayin y CROSS JOIN generate_series(1, 2) g;
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
    void claimTargetAndPreflightRetriesReturnTheExactCommittedAcknowledgement() {
        Claim firstClaim = claim("ack-worker");
        Claim retriedClaim = claim("ack-worker");

        assertEquals(firstClaim, retriedClaim);
        assertEquals(1, eventCount(firstClaim.runUuid(), "RUN_CLAIMED"));
        assertEquals(1, statusCount("BEKLIYOR"));
        assertEquals(1, statusCount("HAZIRLANIYOR"));

        Target firstTarget = acquire(firstClaim, TARGET_HASH);
        Target retriedTarget = acquire(firstClaim, TARGET_HASH);
        assertEquals(firstTarget, retriedTarget);
        assertEquals(1, eventCount(firstClaim.runUuid(), "TARGET_ACQUIRED"));
        assertThrows(DataAccessException.class,
                () -> acquire(firstClaim, "0".repeat(64)));

        assertTrue(completePreflight(firstClaim, firstTarget));
        assertTrue(completePreflight(firstClaim, firstTarget));
        assertEquals(1, eventCount(firstClaim.runUuid(), "PREFLIGHT_COMPLETED"));
        assertEquals(firstTarget.targetUuid().toString(), eventValue(
                firstClaim.runUuid(), "PREFLIGHT_COMPLETED", "targetResourceUuid"));
        assertEquals(Long.toString(firstClaim.generation()), eventValue(
                firstClaim.runUuid(), "PREFLIGHT_COMPLETED", "generation"));
    }

    @Test
    void safeFailureRetryRequiresExactTokenTargetEventAndPinnedEvidence() {
        Execution execution = publishingExecution("ack-safe-failure");

        assertTrue(failSafely(execution, "PREFLIGHT_REJECTED",
                execution.claim().workerReference(), execution.claim().generation()));
        assertTrue(failSafely(execution, "PREFLIGHT_REJECTED",
                execution.claim().workerReference(), execution.claim().generation()));
        assertFalse(failSafely(execution, "DIFFERENT_ERROR",
                execution.claim().workerReference(), execution.claim().generation()));
        assertFalse(failSafely(execution, "PREFLIGHT_REJECTED",
                "other-worker", execution.claim().generation()));
        assertFalse(failSafely(execution, "PREFLIGHT_REJECTED",
                execution.claim().workerReference(), execution.claim().generation() + 1));

        assertEquals("33", eventValue(
                execution.claim().runUuid(), "RUN_FAILED_SAFE", "rowCount"));
        assertEquals("1024", eventValue(
                execution.claim().runUuid(), "RUN_FAILED_SAFE", "byteCount"));
        assertEquals(execution.target().targetUuid().toString(), eventValue(
                execution.claim().runUuid(), "RUN_FAILED_SAFE", "targetResourceUuid"));
        assertEquals(1, eventCount(execution.claim().runUuid(), "RUN_FAILED_SAFE"));
    }

    @Test
    void outcomeUnknownRetryRequiresExactTokenTargetEventAndPinnedEvidence() {
        Execution execution = publishingExecution("ack-unknown");

        assertTrue(markUnknown(execution, execution.target().targetUuid(),
                execution.target().generation()));
        assertTrue(markUnknown(execution, execution.target().targetUuid(),
                execution.target().generation()));
        assertFalse(markUnknown(execution, UUID.randomUUID(),
                execution.target().generation()));
        assertFalse(markUnknown(execution, execution.target().targetUuid(),
                execution.target().generation() + 1));

        assertEquals("33", eventValue(
                execution.claim().runUuid(), "RESULT_UNCERTAIN", "rowCount"));
        assertEquals("1024", eventValue(
                execution.claim().runUuid(), "RESULT_UNCERTAIN", "byteCount"));
        assertEquals(Long.toString(execution.claim().generation()), eventValue(
                execution.claim().runUuid(), "RESULT_UNCERTAIN", "generation"));
        assertEquals(1, eventCount(execution.claim().runUuid(), "RESULT_UNCERTAIN"));
    }

    @Test
    void successRetryUsesExactIntentCheckpointEventAndSurvivesTargetReuse() {
        Execution execution = publishingExecution("ack-success");

        assertTrue(completeSuccessfully(execution, 33, 1024));
        assertTrue(completeSuccessfully(execution, 33, 1024));
        assertFalse(completeSuccessfully(execution, 34, 1024));
        assertEquals(1, eventCount(execution.claim().runUuid(), "RUN_SUCCEEDED"));
        assertEquals(1, checkpointCount(execution.claim().runUuid()));

        Claim nextClaim = claim("next-worker");
        Target reused = acquire(nextClaim, TARGET_HASH);
        assertEquals(execution.target().targetUuid(), reused.targetUuid());
        assertEquals(execution.target().generation() + 1, reused.generation());

        assertTrue(completeSuccessfully(execution, 33, 1024));
        assertEquals("33", eventValue(
                execution.claim().runUuid(), "RUN_SUCCEEDED", "rowCount"));
        assertEquals("1024", eventValue(
                execution.claim().runUuid(), "RUN_SUCCEEDED", "byteCount"));
    }

    private Execution publishingExecution(String workerReference) {
        Claim claim = claim(workerReference);
        Target target = acquire(claim, TARGET_HASH);
        assertTrue(completePreflight(claim, target));
        assertTrue(beginPublish(claim, target));
        return new Execution(claim, target);
    }

    private Claim claim(String workerReference) {
        return jdbc.sql("""
                        select calistirma_uuid, nesil_no, yayin_ozeti,
                               plan_ozeti, kiralama_bitis_zamani
                          from entegrasyon.calistirma_sahiplen(
                              :profileUuid, :workerReference, 60)
                        """)
                .param("profileUuid", workerProfileUuid())
                .param("workerReference", workerReference)
                .query((rs, rowNum) -> new Claim(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getLong("nesil_no"),
                        rs.getString("yayin_ozeti"),
                        rs.getString("plan_ozeti"),
                        rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class),
                        workerReference))
                .single();
    }

    private Target acquire(Claim claim, String targetHash) {
        return jdbc.sql("""
                        select hedef_kaynagi_uuid, hedef_nesil_no,
                               kiralama_bitis_zamani
                          from entegrasyon.hedef_kaynagi_sahiplen(
                              :runUuid, :workerReference, :generation,
                              :targetHash, 1)
                        """)
                .param("runUuid", claim.runUuid())
                .param("workerReference", claim.workerReference())
                .param("generation", claim.generation())
                .param("targetHash", targetHash)
                .query((rs, rowNum) -> new Target(
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no"),
                        rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class)))
                .single();
    }

    private boolean completePreflight(Claim claim, Target target) {
        return transition("calistirma_calismaya_baslat", claim, target);
    }

    private boolean transition(String function, Claim claim, Target target) {
        return jdbc.sql("select entegrasyon." + function + "("
                        + ":runUuid, :workerReference, :generation, "
                        + ":targetUuid, :targetGeneration)")
                .param("runUuid", claim.runUuid())
                .param("workerReference", claim.workerReference())
                .param("generation", claim.generation())
                .param("targetUuid", target.targetUuid())
                .param("targetGeneration", target.generation())
                .query(Boolean.class)
                .single();
    }

    private boolean beginPublish(Claim claim, Target target) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_yayina_gec(
                            :runUuid, :workerReference, :generation,
                            :targetUuid, :targetGeneration, :runtimePlanHash,
                            :publishKeyHash, :payloadHash, 33, 1024)
                        """)
                .param("runUuid", claim.runUuid())
                .param("workerReference", claim.workerReference())
                .param("generation", claim.generation())
                .param("targetUuid", target.targetUuid())
                .param("targetGeneration", target.generation())
                .param("runtimePlanHash", RUNTIME_PLAN_HASH)
                .param("publishKeyHash", PUBLISH_KEY_HASH)
                .param("payloadHash", PAYLOAD_HASH)
                .query(Boolean.class)
                .single();
    }

    private boolean failSafely(
            Execution execution, String errorCode, String workerReference, long generation) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_guvenli_hata_ile_sonlandir(
                            :runUuid, :workerReference, :generation,
                            :targetUuid, :targetGeneration, :errorCode)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", workerReference)
                .param("generation", generation)
                .param("targetUuid", execution.target().targetUuid())
                .param("targetGeneration", execution.target().generation())
                .param("errorCode", errorCode)
                .query(Boolean.class)
                .single();
    }

    private boolean markUnknown(Execution execution, UUID targetUuid, long targetGeneration) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_sonucu_belirsiz_isaretle(
                            :runUuid, :workerReference, :generation,
                            :targetUuid, :targetGeneration)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", execution.claim().workerReference())
                .param("generation", execution.claim().generation())
                .param("targetUuid", targetUuid)
                .param("targetGeneration", targetGeneration)
                .query(Boolean.class)
                .single();
    }

    private boolean completeSuccessfully(Execution execution, long rowCount, long byteCount) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_basarili_tamamla(
                            :runUuid, :workerReference, :generation,
                            :targetUuid, :targetGeneration, :runtimePlanHash,
                            :publishKeyHash, :payloadHash, :rowCount, :byteCount)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", execution.claim().workerReference())
                .param("generation", execution.claim().generation())
                .param("targetUuid", execution.target().targetUuid())
                .param("targetGeneration", execution.target().generation())
                .param("runtimePlanHash", RUNTIME_PLAN_HASH)
                .param("publishKeyHash", PUBLISH_KEY_HASH)
                .param("payloadHash", PAYLOAD_HASH)
                .param("rowCount", rowCount)
                .param("byteCount", byteCount)
                .query(Boolean.class)
                .single();
    }

    private UUID workerProfileUuid() {
        return jdbc.sql("""
                        select uuid from entegrasyon.worker_profili
                         where kod = 'ACK_IT_WORKER'
                        """)
                .query(UUID.class)
                .single();
    }

    private int eventCount(UUID runUuid, String type) {
        return jdbc.sql("""
                        select count(*) from entegrasyon.calistirma_olayi co
                          join entegrasyon.calistirma c on c.id = co.calistirma_id
                         where c.uuid = :runUuid and co.tur_kodu = :type
                        """)
                .param("runUuid", runUuid)
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private String eventValue(UUID runUuid, String type, String key) {
        return jdbc.sql("""
                        select co.veri ->> :key
                          from entegrasyon.calistirma_olayi co
                          join entegrasyon.calistirma c on c.id = co.calistirma_id
                         where c.uuid = :runUuid and co.tur_kodu = :type
                        """)
                .param("key", key)
                .param("runUuid", runUuid)
                .param("type", type)
                .query(String.class)
                .single();
    }

    private int statusCount(String status) {
        return jdbc.sql("""
                        select count(*) from entegrasyon.calistirma_durumu
                         where durum_kodu = :status
                        """)
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    private int checkpointCount(UUID runUuid) {
        return jdbc.sql("""
                        select count(*) from entegrasyon.kontrol_noktasi kn
                          join entegrasyon.calistirma c on c.id = kn.calistirma_id
                         where c.uuid = :runUuid
                        """)
                .param("runUuid", runUuid)
                .query(Integer.class)
                .single();
    }

    @Configuration(proxyBeanMethods = false)
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
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
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

    private record Claim(
            UUID runUuid,
            long generation,
            String releaseHash,
            String planHash,
            OffsetDateTime leaseDeadline,
            String workerReference) {
    }

    private record Target(UUID targetUuid, long generation, OffsetDateTime leaseDeadline) {
    }

    private record Execution(Claim claim, Target target) {
    }
}

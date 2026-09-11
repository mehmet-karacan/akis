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

class ProcedureStepMetadataMigrationIT {

    private static final String TARGET_HASH = "1".repeat(64);
    private static final String RUNTIME_PLAN_HASH = "2".repeat(64);
    private static final String OPERATION_KEY_HASH = "3".repeat(64);

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
    void createQueuedProcedureRun() {
        jdbcTemplate.execute("""
                SET search_path TO entegrasyon, public;
                TRUNCATE TABLE proje, worker_profili RESTART IDENTITY CASCADE;
                INSERT INTO proje(kod, ad) VALUES ('PROC_STEP_IT', 'Procedure step IT');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod = 'PROC_STEP_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'PROCEDURE', 'LOAD_TEST', 'Load test'
                  FROM proje p JOIN klasor k ON k.proje_id = p.id
                 WHERE p.kod = 'PROC_STEP_IT';
                INSERT INTO tanim_surumu(
                    tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 2, repeat('4', 64),
                       cast('{"tasks":[{"id":"READ_SOURCE","name":"Read source","type":"SQL","connectionRole":"SOURCE","riskClass":"READ_ONLY","onError":"STOP","timeoutSeconds":60,"command":"SELECT ID FROM TTBP.HAKEDIS_TIPI"},{"id":"INSERT_TARGET","name":"Insert target","type":"SQL","connectionRole":"TARGET","riskClass":"DML","onError":"STOP","timeoutSeconds":60,"command":"INSERT INTO INNOVA_ODI.STG_HAKEDIS_TIPI (ID) VALUES (:ID)"}]}' as jsonb)
                  FROM tanim WHERE kod = 'LOAD_TEST';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test' FROM proje WHERE kod = 'PROC_STEP_IT';
                INSERT INTO dogrulama(
                    tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'LOAD_TEST'
                  JOIN ortam o ON o.proje_id = t.proje_id AND o.kod = 'TEST';
                INSERT INTO senaryo(
                    tanim_surumu_id, dogrulama_id, surum_no,
                    plan_surumu, plan_ozeti, plan)
                SELECT d.tanim_surumu_id, d.id, 1, 2, repeat('5', 64),
                       jsonb_build_object(
                           'compiler', 'AKIS', 'compilerVersion', 2,
                           'executable', jsonb_build_object(
                               'kind', 'PROCEDURE', 'definition', ts.icerik),
                           'source', jsonb_build_object(
                               'contentHash', ts.icerik_ozeti,
                               'definitionType', 'PROCEDURE'))
                  FROM dogrulama d
                  JOIN tanim_surumu ts ON ts.id = d.tanim_surumu_id
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'LOAD_TEST';
                INSERT INTO yayin(
                    proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
                    bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani)
                SELECT p.id, s.id, o.id, 1, 'AKTIF', repeat('6', 64),
                       jsonb_build_object(
                           'releaseHash', repeat('7', 64),
                           'runtimeCapability', 'ORACLE_PROCEDURE_V1',
                           'runtimePlanHash', repeat('2', 64),
                           'bindings', jsonb_build_array(
                               jsonb_build_object(
                                   'nodeCode', 'READ_SOURCE',
                                   'definitionDataObjectUuid', gen_random_uuid(),
                                   'connectionVersionUuid', gen_random_uuid(),
                                   'schemaSnapshotUuid', gen_random_uuid(),
                                   'schemaSnapshotFingerprint', repeat('8', 64)),
                               jsonb_build_object(
                                   'nodeCode', 'INSERT_TARGET',
                                   'definitionDataObjectUuid', gen_random_uuid(),
                                   'connectionVersionUuid', gen_random_uuid(),
                                   'schemaSnapshotUuid', gen_random_uuid(),
                                   'schemaSnapshotFingerprint', repeat('9', 64)))),
                       current_timestamp
                  FROM proje p
                  JOIN ortam o ON o.proje_id = p.id AND o.kod = 'TEST'
                  JOIN tanim t ON t.proje_id = p.id AND t.kod = 'LOAD_TEST'
                  JOIN tanim_surumu ts ON ts.tanim_id = t.id
                  JOIN senaryo s ON s.tanim_surumu_id = ts.id
                 WHERE p.kod = 'PROC_STEP_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('PROC_STEP_WORKER', '{}'::jsonb, 'Procedure step worker');
                INSERT INTO is_talebi(
                    proje_id, yayin_id, istek_ozeti, is_turu, parametre)
                SELECT proje_id, id, repeat('a', 64), 'RUN', '{}'::jsonb FROM yayin;
                INSERT INTO calistirma(
                    proje_id, is_talebi_id, deneme_no,
                    yayin_ozeti, plan_ozeti, baslatma_turu)
                SELECT proje_id, id, 1, repeat('7', 64), repeat('5', 64), 'ILK'
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
    void procedureStartMaterializesPinnedTasksAndRetriesExactly() {
        Execution execution = execution("procedure-start");

        assertTrue(start(execution));
        assertTrue(start(execution));
        assertEquals(2, count("prosedur_adim_kaniti"));
        assertEquals(2, count("prosedur_adim_durumu"));
        assertEquals(1, eventCount("PROCEDURE_PREPARED"));
        assertEquals("CALISIYOR", runStatus());

        assertFalse(start(new Execution(
                execution.claim(), new Target(UUID.randomUUID(), execution.target().generation()))));
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        update entegrasyon.prosedur_adim_kaniti
                           set komut_ozeti = repeat('0', 64)
                        """).update());
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        update entegrasyon.prosedur_adim_durumu
                           set versiyon_no = versiyon_no + 1
                        """).update());
    }

    @Test
    void stepTransitionsAreOrderedAndExactAndMutationIntentIsImmutable() {
        Execution execution = execution("procedure-steps");
        assertTrue(start(execution));

        assertFalse(startStep(execution, "INSERT_TARGET", OPERATION_KEY_HASH));
        assertTrue(startStep(execution, "READ_SOURCE", null));
        assertTrue(startStep(execution, "READ_SOURCE", null));
        assertFalse(startStep(withForgedTarget(execution), "READ_SOURCE", null));
        assertFalse(startStep(execution, "READ_SOURCE", OPERATION_KEY_HASH));
        assertTrue(succeedStep(execution, "READ_SOURCE", 1, 16));
        assertTrue(succeedStep(execution, "READ_SOURCE", 1, 16));
        assertFalse(succeedStep(withForgedTarget(execution), "READ_SOURCE", 1, 16));
        assertFalse(succeedStep(execution, "READ_SOURCE", 2, 16));

        assertTrue(startStep(execution, "INSERT_TARGET", OPERATION_KEY_HASH));
        assertTrue(startStep(execution, "INSERT_TARGET", OPERATION_KEY_HASH));
        assertFalse(startStep(execution, "INSERT_TARGET", "0".repeat(64)));
        assertEquals(1, count("prosedur_adim_niyeti"));
        assertEquals(2, eventCount("PROCEDURE_STEP_STARTED"));
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        delete from entegrasyon.prosedur_adim_niyeti
                        """).update());
    }

    private Execution withForgedTarget(Execution execution) {
        return new Execution(
                execution.claim(),
                new Target(UUID.randomUUID(), execution.target().generation()));
    }

    private Execution execution(String workerReference) {
        Claim claim = claim(workerReference);
        return new Execution(claim, acquire(claim));
    }

    private Claim claim(String workerReference) {
        return jdbc.sql("""
                        select calistirma_uuid, nesil_no, kiralama_bitis_zamani
                          from entegrasyon.calistirma_sahiplen(
                              :profileUuid, :workerReference, 60)
                        """)
                .param("profileUuid", workerProfileUuid())
                .param("workerReference", workerReference)
                .query((rs, rowNum) -> new Claim(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getLong("nesil_no"),
                        rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class),
                        workerReference))
                .single();
    }

    private Target acquire(Claim claim) {
        return jdbc.sql("""
                        select hedef_kaynagi_uuid, hedef_nesil_no
                          from entegrasyon.hedef_kaynagi_sahiplen(
                              :runUuid, :workerReference, :generation,
                              :targetHash, 1)
                        """)
                .param("runUuid", claim.runUuid())
                .param("workerReference", claim.workerReference())
                .param("generation", claim.generation())
                .param("targetHash", TARGET_HASH)
                .query((rs, rowNum) -> new Target(
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no")))
                .single();
    }

    private boolean start(Execution execution) {
        return jdbc.sql("""
                        select entegrasyon.prosedur_calistirmayi_baslat(
                            :runUuid, :workerReference, :generation,
                            :targetUuid, :targetGeneration, :runtimePlanHash)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", execution.claim().workerReference())
                .param("generation", execution.claim().generation())
                .param("targetUuid", execution.target().uuid())
                .param("targetGeneration", execution.target().generation())
                .param("runtimePlanHash", RUNTIME_PLAN_HASH)
                .query(Boolean.class)
                .single();
    }

    private boolean startStep(Execution execution, String stepCode, String operationKeyHash) {
        return jdbc.sql("""
                        select entegrasyon.prosedur_adimini_baslat(
                            :runUuid, :workerReference, :generation,
                            :targetUuid, :targetGeneration, :stepCode,
                            :operationKeyHash)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", execution.claim().workerReference())
                .param("generation", execution.claim().generation())
                .param("targetUuid", execution.target().uuid())
                .param("targetGeneration", execution.target().generation())
                .param("stepCode", stepCode)
                .param("operationKeyHash", operationKeyHash)
                .query(Boolean.class)
                .single();
    }

    private boolean succeedStep(
            Execution execution, String stepCode, long rowCount, long byteCount) {
        return jdbc.sql("""
                        select entegrasyon.prosedur_adimini_basarili_tamamla(
                            :runUuid, :workerReference, :generation,
                            :targetUuid, :targetGeneration, :stepCode,
                            :rowCount, :byteCount)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", execution.claim().workerReference())
                .param("generation", execution.claim().generation())
                .param("targetUuid", execution.target().uuid())
                .param("targetGeneration", execution.target().generation())
                .param("stepCode", stepCode)
                .param("rowCount", rowCount)
                .param("byteCount", byteCount)
                .query(Boolean.class)
                .single();
    }

    private UUID workerProfileUuid() {
        return jdbc.sql("""
                        select uuid from entegrasyon.worker_profili
                         where kod = 'PROC_STEP_WORKER'
                        """).query(UUID.class).single();
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from entegrasyon." + table)
                .query(Integer.class).single();
    }

    private int eventCount(String type) {
        return jdbc.sql("""
                        select count(*) from entegrasyon.calistirma_olayi
                         where tur_kodu = :type
                        """).param("type", type).query(Integer.class).single();
    }

    private String runStatus() {
        return jdbc.sql("select durum_kodu from entegrasyon.calistirma_durumu")
                .query(String.class).single();
    }

    @Configuration(proxyBeanMethods = false)
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
                throw new IllegalStateException(name + " is required for the JDBC integration test.");
            }
            return value;
        }
    }

    private record Claim(
            UUID runUuid,
            long generation,
            OffsetDateTime leaseDeadline,
            String workerReference) {
    }

    private record Target(UUID uuid, long generation) {
    }

    private record Execution(Claim claim, Target target) {
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import tr.com.innova.akis.execution.ProcedureExecutionJournalPort.TaskEvidence;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ConnectionRole;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RiskClass;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskType;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tools.jackson.databind.ObjectMapper;

class JdbcProcedureExecutionJournalStoreIT {

    private static final String TARGET_HASH = "1".repeat(64);
    private static final String RUNTIME_PLAN_HASH = "2".repeat(64);
    private static final String READ_COMMAND = "SELECT ID FROM TTBP.HAKEDIS_TIPI";
    private static final String INSERT_COMMAND =
            "INSERT INTO INNOVA_ODI.STG_HAKEDIS_TIPI (ID) VALUES (:ID)";

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcProcedureExecutionJournalStore store;

    @BeforeAll
    static void startDatabaseContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        DataSource dataSource = context.getBean(DataSource.class);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = context.getBean(JdbcClient.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        store = new JdbcProcedureExecutionJournalStore(
                jdbc, new DataSourceTransactionManager(dataSource));
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
                INSERT INTO proje(kod, ad) VALUES ('PROC_JOURNAL_IT', 'Procedure journal IT');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod = 'PROC_JOURNAL_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'PROCEDURE', 'JOURNAL_LOAD', 'Journal load'
                  FROM proje p JOIN klasor k ON k.proje_id = p.id
                 WHERE p.kod = 'PROC_JOURNAL_IT';
                INSERT INTO tanim_surumu(tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 2, repeat('4', 64),
                       cast('{"tasks":[{"id":"READ_SOURCE","name":"Read source","type":"SQL","connectionRole":"SOURCE","riskClass":"READ_ONLY","onError":"CONTINUE","timeoutSeconds":60,"command":"SELECT ID FROM TTBP.HAKEDIS_TIPI"},{"id":"INSERT_TARGET","name":"Insert target","type":"SQL","connectionRole":"TARGET","riskClass":"DML","onError":"STOP","timeoutSeconds":60,"command":"INSERT INTO INNOVA_ODI.STG_HAKEDIS_TIPI (ID) VALUES (:ID)"}]}' as jsonb)
                  FROM tanim WHERE kod = 'JOURNAL_LOAD';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test' FROM proje WHERE kod = 'PROC_JOURNAL_IT';
                INSERT INTO dogrulama(tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'JOURNAL_LOAD'
                  JOIN ortam o ON o.proje_id = t.proje_id AND o.kod = 'TEST';
                INSERT INTO senaryo(tanim_surumu_id, dogrulama_id, surum_no, plan_surumu, plan_ozeti, plan)
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
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'JOURNAL_LOAD';
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
                  JOIN tanim t ON t.proje_id = p.id AND t.kod = 'JOURNAL_LOAD'
                  JOIN tanim_surumu ts ON ts.tanim_id = t.id
                  JOIN senaryo s ON s.tanim_surumu_id = ts.id
                 WHERE p.kod = 'PROC_JOURNAL_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('PROC_JOURNAL_WORKER', '{}'::jsonb, 'Procedure journal worker');
                INSERT INTO is_talebi(proje_id, yayin_id, istek_ozeti, is_turu, parametre)
                SELECT proje_id, id, repeat('b', 64), 'RUN', '{}'::jsonb FROM yayin;
                INSERT INTO calistirma(
                    proje_id, is_talebi_id, deneme_no,
                    yayin_ozeti, plan_ozeti, baslatma_turu)
                SELECT proje_id, id, 1, repeat('7', 64), repeat('5', 64), 'ILK'
                  FROM is_talebi;
                INSERT INTO calistirma_durumu(proje_id, calistirma_id, durum_kodu, son_olay_no)
                SELECT proje_id, id, 'BEKLIYOR', 1 FROM calistirma;
                INSERT INTO calistirma_olayi(
                    proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
                SELECT proje_id, id, 1, 'RUN_QUEUED', current_timestamp, '{}'::jsonb
                  FROM calistirma;
                """);
    }

    @Test
    void persistsExactRetryableStepAcksAndCompletesRun() {
        ActiveExecutionToken token = claim("journal-success");
        ProcedureRuntimePlan plan = plan();
        ProcedureExecutionJournalSession journal = store.forExecution(token, plan);
        TaskEvidence read = evidence(plan, 1);
        TaskEvidence insert = evidence(plan, 2);

        assertTrue(journal.prepareRun());
        assertTrue(journal.prepareRun());
        assertTrue(journal.started(read));
        assertTrue(journal.started(read));
        assertTrue(journal.failed(read, "SOURCE_TIMEOUT", true, true, true));
        assertTrue(journal.failed(read, "SOURCE_TIMEOUT", true, true, true));
        assertTrue(journal.started(insert));
        assertTrue(journal.started(insert));
        assertTrue(journal.succeeded(insert, 12, 96));
        assertTrue(journal.succeeded(insert, 12, 96));
        assertTrue(journal.completeRun());
        assertTrue(journal.completeRun());

        assertEquals("BASARILI", scalar("select durum_kodu from entegrasyon.calistirma_durumu"));
        assertEquals("HATA_DEVAM", stepStatus("READ_SOURCE"));
        assertEquals("BASARILI", stepStatus("INSERT_TARGET"));
        assertEquals(new ProcedureOperationKeyV1().create(token, insert), scalar("""
                select operasyon_anahtari_ozeti
                  from entegrasyon.prosedur_adim_niyeti
                """));
        assertEquals(1, integer("""
                select count(*) from entegrasyon.calistirma_olayi
                 where tur_kodu='PROCEDURE_RUN_SUCCEEDED'
                """));
    }

    @Test
    void rejectsForgedFenceAndReservesUnknownForMutatingIntent() {
        ActiveExecutionToken token = claim("journal-unknown");
        ProcedureRuntimePlan plan = plan();
        ProcedureExecutionJournalSession journal = store.forExecution(token, plan);
        TaskEvidence read = evidence(plan, 1);
        TaskEvidence insert = evidence(plan, 2);
        assertTrue(journal.prepareRun());
        assertThrows(IllegalArgumentException.class,
                () -> journal.started(new TaskEvidence(
                        RUNTIME_PLAN_HASH, 1, insert.task(), insert.binding())));
        Task forgedCommand = new Task(
                insert.task().id(), insert.task().name(), insert.task().type(),
                insert.task().connectionRole(), insert.task().riskClass(),
                insert.task().command(), "c".repeat(64),
                insert.task().requiresApproval(), insert.task().onError(),
                insert.task().timeoutSeconds(), insert.task().output(),
                insert.task().input(), insert.task().namedBinds());
        assertThrows(IllegalArgumentException.class,
                () -> journal.started(new TaskEvidence(
                        RUNTIME_PLAN_HASH, 2, forgedCommand, insert.binding())));
        assertThrows(IllegalArgumentException.class,
                () -> journal.started(new TaskEvidence(
                        "a".repeat(64), 2, insert.task(), insert.binding())));
        assertTrue(journal.started(read));
        assertTrue(journal.succeeded(read, 1, 8));
        assertTrue(journal.started(insert));

        ActiveExecutionToken forged = new ActiveExecutionToken(token.run(),
                new TargetFenceToken(
                        token.run().runUuid(), token.run().workerReference(),
                        token.run().generation(), UUID.randomUUID(),
                        token.target().targetGeneration(), TARGET_HASH, 1));
        assertFalse(store.forExecution(forged, plan).succeeded(insert, 1, 8));
        assertThrows(IllegalArgumentException.class,
                () -> journal.outcomeUnknown(read, "READ_ACK_LOST"));
        assertThrows(IllegalArgumentException.class,
                () -> journal.failed(insert, "ROLLBACK_UNKNOWN", true, false, false));

        assertTrue(journal.outcomeUnknown(insert, "COMMIT_ACK_LOST"));
        assertTrue(journal.outcomeUnknown(insert, "COMMIT_ACK_LOST"));
        assertEquals("SONUC_BELIRSIZ", scalar(
                "select durum_kodu from entegrasyon.calistirma_durumu"));
        assertEquals("ASKIDA", scalar(
                "select durum_kodu from entegrasyon.hedef_kaynagi"));
    }

    private ActiveExecutionToken claim(String workerReference) {
        UUID profileUuid = jdbc.sql("""
                select uuid from entegrasyon.worker_profili
                 where kod='PROC_JOURNAL_WORKER'
                """).query(UUID.class).single();
        RunLeaseToken run = jdbc.sql("""
                select calistirma_uuid, nesil_no, kiralama_bitis_zamani
                  from entegrasyon.calistirma_sahiplen(:profileUuid, :workerReference, 60)
                """)
                .param("profileUuid", profileUuid)
                .param("workerReference", workerReference)
                .query((rs, rowNum) -> new RunLeaseToken(
                        rs.getObject("calistirma_uuid", UUID.class),
                        workerReference, rs.getLong("nesil_no"),
                        rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class)))
                .single();
        TargetFenceToken target = jdbc.sql("""
                select hedef_kaynagi_uuid, hedef_nesil_no
                  from entegrasyon.hedef_kaynagi_sahiplen(
                      :runUuid, :workerReference, :generation, :targetHash, 1)
                """)
                .param("runUuid", run.runUuid())
                .param("workerReference", run.workerReference())
                .param("generation", run.generation())
                .param("targetHash", TARGET_HASH)
                .query((rs, rowNum) -> new TargetFenceToken(
                        run.runUuid(), run.workerReference(), run.generation(),
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no"), TARGET_HASH, 1))
                .single();
        return new ActiveExecutionToken(run, target);
    }

    private ProcedureRuntimePlan plan() {
        Task read = task(
                "READ_SOURCE", ConnectionRole.SOURCE,
                RiskClass.READ_ONLY, ErrorPolicy.CONTINUE, READ_COMMAND);
        Task insert = task(
                "INSERT_TARGET", ConnectionRole.TARGET,
                RiskClass.DML, ErrorPolicy.STOP, INSERT_COMMAND);
        Map<String, TaskBinding> bindings = new LinkedHashMap<>();
        bindings.put(read.id(), binding(read));
        bindings.put(insert.id(), binding(insert));
        return new ProcedureRuntimePlan(
                ProcedureRuntimePlan.CURRENT_VERSION, RUNTIME_PLAN_HASH,
                "7".repeat(64), "5".repeat(64), UUID.randomUUID(), UUID.randomUUID(),
                List.of(read, insert), bindings, new ObjectMapper().createObjectNode());
    }

    private Task task(
            String id,
            ConnectionRole role,
            RiskClass risk,
            ErrorPolicy policy,
            String command) {
        return new Task(
                id, id, TaskType.SQL, role, risk, command, sha256(command),
                false, policy, 60, null, null, List.of());
    }

    private TaskBinding binding(Task task) {
        return new TaskBinding(
                task.id(), task.connectionRole(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "8".repeat(64), task.connectionRole() + "|OWNER|OBJECT",
                "OWNER", "OBJECT", "TABLE");
    }

    private TaskEvidence evidence(ProcedureRuntimePlan plan, int index) {
        Task task = plan.tasks().get(index - 1);
        return new TaskEvidence(
                plan.runtimePlanHash(), index, task, plan.bindings().get(task.id()));
    }

    private String stepStatus(String code) {
        return jdbc.sql("""
                select pad.durum_kodu
                  from entegrasyon.prosedur_adim_durumu pad
                  join entegrasyon.calistirma_adimi ca
                    on ca.id=pad.calistirma_adimi_id
                 where ca.adim_kodu=:code
                """).param("code", code).query(String.class).single();
    }

    private String scalar(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private int integer(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
                throw new IllegalStateException(
                        name + " is required for the JDBC integration test.");
            }
            return value;
        }
    }
}

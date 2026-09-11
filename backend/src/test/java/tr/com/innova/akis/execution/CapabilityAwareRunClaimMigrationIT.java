package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.List;
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

class CapabilityAwareRunClaimMigrationIT {

    private static final String PILOT = "ORACLE_TABLE_COPY_V1";
    private static final String PROCEDURE = "ORACLE_PROCEDURE_V1";

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
    void createQueuedRuns() {
        jdbcTemplate.execute("""
                SET search_path TO entegrasyon, public;
                TRUNCATE TABLE proje, worker_profili RESTART IDENTITY CASCADE;
                INSERT INTO proje(kod, ad) VALUES ('CAP_CLAIM_IT', 'Capability claim IT');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod='CAP_CLAIM_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'MAPPING', 'CAPABILITY_JOB', 'Capability job'
                  FROM proje p JOIN klasor k ON k.proje_id=p.id
                 WHERE p.kod='CAP_CLAIM_IT';
                INSERT INTO tanim_surumu(tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 1, repeat('4',64), '{}'::jsonb
                  FROM tanim WHERE kod='CAPABILITY_JOB';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test' FROM proje WHERE kod='CAP_CLAIM_IT';
                INSERT INTO dogrulama(tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id=ts.tanim_id AND t.kod='CAPABILITY_JOB'
                  JOIN ortam o ON o.proje_id=t.proje_id AND o.kod='TEST';
                INSERT INTO senaryo(
                    tanim_surumu_id, dogrulama_id, surum_no,
                    plan_surumu, plan_ozeti, plan)
                SELECT d.tanim_surumu_id, d.id, 1, 1, repeat('5',64),
                       jsonb_build_object('executable', jsonb_build_object('kind','MAPPING'))
                  FROM dogrulama d
                  JOIN tanim_surumu ts ON ts.id=d.tanim_surumu_id
                  JOIN tanim t ON t.id=ts.tanim_id AND t.kod='CAPABILITY_JOB';
                INSERT INTO yayin(
                    proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
                    bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani)
                SELECT p.id, s.id, o.id, v.yayin_no, 'AKTIF', repeat('6',64),
                       jsonb_build_object(
                           'releaseHash', v.release_hash,
                           'runtimeCapability', v.capability),
                       current_timestamp
                  FROM proje p
                  JOIN ortam o ON o.proje_id=p.id AND o.kod='TEST'
                  JOIN tanim t ON t.proje_id=p.id AND t.kod='CAPABILITY_JOB'
                  JOIN tanim_surumu ts ON ts.tanim_id=t.id
                  JOIN senaryo s ON s.tanim_surumu_id=ts.id
                  CROSS JOIN (VALUES
                      (1, repeat('7',64), 'ORACLE_TABLE_COPY_V1'),
                      (2, repeat('8',64), 'ORACLE_PROCEDURE_V1'),
                      (3, repeat('9',64), 'UNKNOWN_RUNTIME_V1')
                  ) AS v(yayin_no, release_hash, capability)
                 WHERE p.kod='CAP_CLAIM_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('CAP_WORKER', '{}'::jsonb, 'Capability worker');
                INSERT INTO is_talebi(
                    proje_id, yayin_id, istek_ozeti, is_turu, oncelik, parametre)
                SELECT y.proje_id, y.id,
                       encode(sha256(convert_to(
                           y.fiziksel_manifesto ->> 'runtimeCapability', 'UTF8')), 'hex'),
                       'RUN',
                       CASE y.fiziksel_manifesto ->> 'runtimeCapability'
                           WHEN 'ORACLE_TABLE_COPY_V1' THEN 100
                           WHEN 'UNKNOWN_RUNTIME_V1' THEN 90
                           ELSE 50 END,
                       '{}'::jsonb
                  FROM yayin y;
                INSERT INTO calistirma(
                    proje_id, is_talebi_id, deneme_no,
                    yayin_ozeti, plan_ozeti, baslatma_turu)
                SELECT it.proje_id, it.id, 1, y.release_hash, s.plan_ozeti, 'ILK'
                  FROM is_talebi it
                  JOIN yayin y ON y.proje_id=it.proje_id AND y.id=it.yayin_id
                  JOIN senaryo s ON s.id=y.senaryo_id;
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
    void exactCapabilitySkipsHigherPriorityOtherRuntimeAndRetriesExactAck() {
        UUID expectedProcedure = queuedRunUuid(PROCEDURE);

        Claim first = claimExact("procedure-dispatcher", PROCEDURE);
        Claim retried = claimExact("procedure-dispatcher", PROCEDURE);

        assertEquals(expectedProcedure, first.runUuid());
        assertEquals(PROCEDURE, first.capability());
        assertEquals(first, retried);
        assertEquals(1, first.generation());
        assertEquals(1, eventCount(first.runUuid(), "RUN_CLAIMED"));
        assertEquals(PROCEDURE, eventCapability(first.runUuid()));
        assertThrows(DataAccessException.class,
                () -> claimExact("procedure-dispatcher", PILOT));
        assertEquals(2, waitingCount());
    }

    @Test
    void boundedAllowlistUsesExistingQueueOrderAndNeverClaimsUnknownManifest() {
        Claim first = claimBoth("unified-01");
        Claim second = claimBoth("unified-02");

        assertEquals(PILOT, first.capability());
        assertEquals(PROCEDURE, second.capability());
        assertNotEquals(first.runUuid(), second.runUuid());
        assertEquals(1, waitingCount());
        assertEquals("BEKLIYOR", statusForCapability("UNKNOWN_RUNTIME_V1"));
    }

    @Test
    void rejectsNullUnknownDuplicateAndUnboundedSelectors() {
        List<String> invalidCalls = List.of(
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        "cast(null as uuid), 'invalid-00', " +
                        "array['ORACLE_TABLE_COPY_V1'], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, null, array['ORACLE_TABLE_COPY_V1'], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, repeat('w',201), array['ORACLE_TABLE_COPY_V1'], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-01', null, 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-02', array[]::text[], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-03', array['ORACLE_TABLE_COPY_V1',null], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-04', array['ORACLE_TABLE_COPY_V1'," +
                        "'ORACLE_TABLE_COPY_V1'], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-05', array['UNKNOWN_RUNTIME_V1'], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-06', array['ORACLE_TABLE_COPY_V1'," +
                        "'ORACLE_PROCEDURE_V1','ORACLE_TABLE_COPY_V1'], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-07', array[['ORACLE_TABLE_COPY_V1']], 60)",
                "select calistirma_uuid from entegrasyon.calistirma_yeteneklerle_sahiplen(" +
                        ":profile, 'invalid-08', array['ORACLE_TABLE_COPY_V1'], null)");

        for (String sql : invalidCalls) {
            assertThrows(DataAccessException.class, () -> jdbc.sql(sql)
                    .param("profile", workerProfileUuid())
                    .query(UUID.class).list());
        }
        assertEquals(3, waitingCount());
    }

    @Test
    void originalPilotClaimFunctionRemainsCapabilityAgnosticAndRetryable() {
        LegacyClaim first = jdbc.sql("""
                select calistirma_uuid, nesil_no, kiralama_bitis_zamani
                  from entegrasyon.calistirma_sahiplen(:profile, 'legacy-worker', 60)
                """)
                .param("profile", workerProfileUuid())
                .query((rs, rowNum) -> new LegacyClaim(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getLong("nesil_no"),
                        rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class)))
                .single();
        LegacyClaim retried = jdbc.sql("""
                select calistirma_uuid, nesil_no, kiralama_bitis_zamani
                  from entegrasyon.calistirma_sahiplen(:profile, 'legacy-worker', 60)
                """)
                .param("profile", workerProfileUuid())
                .query((rs, rowNum) -> new LegacyClaim(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getLong("nesil_no"),
                        rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class)))
                .single();

        assertEquals(first, retried);
        assertEquals(queuedRunUuid(PILOT), first.runUuid());
        assertEquals(1, eventCount(first.runUuid(), "RUN_CLAIMED"));
    }

    private Claim claimExact(String workerReference, String capability) {
        return jdbc.sql("""
                select calistirma_uuid, nesil_no, yayin_ozeti, plan_ozeti,
                       kiralama_bitis_zamani, runtime_yetenegi
                  from entegrasyon.calistirma_yeteneklerle_sahiplen(
                      :profile, :workerReference, array[:capability]::text[], 60)
                """)
                .param("profile", workerProfileUuid())
                .param("workerReference", workerReference)
                .param("capability", capability)
                .query((rs, rowNum) -> claim(rs))
                .single();
    }

    private Claim claimBoth(String workerReference) {
        return jdbc.sql("""
                select calistirma_uuid, nesil_no, yayin_ozeti, plan_ozeti,
                       kiralama_bitis_zamani, runtime_yetenegi
                  from entegrasyon.calistirma_yeteneklerle_sahiplen(
                      :profile, :workerReference,
                      array['ORACLE_TABLE_COPY_V1','ORACLE_PROCEDURE_V1'], 60)
                """)
                .param("profile", workerProfileUuid())
                .param("workerReference", workerReference)
                .query((rs, rowNum) -> claim(rs))
                .single();
    }

    private Claim claim(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Claim(
                rs.getObject("calistirma_uuid", UUID.class),
                rs.getLong("nesil_no"), rs.getString("yayin_ozeti"),
                rs.getString("plan_ozeti"),
                rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class),
                rs.getString("runtime_yetenegi"));
    }

    private UUID workerProfileUuid() {
        return jdbc.sql("select uuid from entegrasyon.worker_profili where kod='CAP_WORKER'")
                .query(UUID.class).single();
    }

    private UUID queuedRunUuid(String capability) {
        return jdbc.sql("""
                select c.uuid
                  from entegrasyon.calistirma c
                  join entegrasyon.is_talebi it on it.id=c.is_talebi_id
                  join entegrasyon.yayin y on y.id=it.yayin_id
                 where y.fiziksel_manifesto ->> 'runtimeCapability'=:capability
                """).param("capability", capability).query(UUID.class).single();
    }

    private String statusForCapability(String capability) {
        return jdbc.sql("""
                select cd.durum_kodu
                  from entegrasyon.calistirma_durumu cd
                  join entegrasyon.calistirma c on c.id=cd.calistirma_id
                  join entegrasyon.is_talebi it on it.id=c.is_talebi_id
                  join entegrasyon.yayin y on y.id=it.yayin_id
                 where y.fiziksel_manifesto ->> 'runtimeCapability'=:capability
                """).param("capability", capability).query(String.class).single();
    }

    private int waitingCount() {
        return jdbc.sql("""
                select count(*) from entegrasyon.calistirma_durumu
                 where durum_kodu='BEKLIYOR'
                """).query(Integer.class).single();
    }

    private int eventCount(UUID runUuid, String eventType) {
        return jdbc.sql("""
                select count(*)
                  from entegrasyon.calistirma_olayi co
                  join entegrasyon.calistirma c on c.id=co.calistirma_id
                 where c.uuid=:runUuid and co.tur_kodu=:eventType
                """).param("runUuid", runUuid).param("eventType", eventType)
                .query(Integer.class).single();
    }

    private String eventCapability(UUID runUuid) {
        return jdbc.sql("""
                select co.veri ->> 'runtimeCapability'
                  from entegrasyon.calistirma_olayi co
                  join entegrasyon.calistirma c on c.id=co.calistirma_id
                 where c.uuid=:runUuid and co.tur_kodu='RUN_CLAIMED'
                """).param("runUuid", runUuid).query(String.class).single();
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

    private record Claim(
            UUID runUuid,
            long generation,
            String releaseHash,
            String planHash,
            OffsetDateTime leaseDeadline,
            String capability) {
    }

    private record LegacyClaim(
            UUID runUuid, long generation, OffsetDateTime leaseDeadline) {
    }
}

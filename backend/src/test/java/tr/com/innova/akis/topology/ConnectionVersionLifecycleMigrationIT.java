package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConnectionVersionLifecycleMigrationIT {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SECOND_FINGERPRINT = "b".repeat(64);
    private static AnnotationConfigApplicationContext context;
    private static DataSource dataSource;
    private static JdbcClient jdbc;

    private long projectId;
    private long connectionId;
    private long versionId;

    @BeforeAll
    static void startDatabaseContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        dataSource = context.getBean(DataSource.class);
        jdbc = context.getBean(JdbcClient.class);
    }

    @AfterAll
    static void stopDatabaseContext() {
        if (context != null) context.close();
    }

    @BeforeEach
    void migrateCleanDatabase() {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();
        seedVersion();
    }

    @Test
    void createsDraftLifecycleAndEnforcesEvidenceBackedTransitions() {
        assertEquals("DRAFT", lifecycleStatus());
        assertEquals(1L, lifecycleVersion());

        UUID firstTest = insertPassedTest(1, FINGERPRINT);
        assertThrows(DataAccessException.class, () -> updateLifecycle(
                "ACTIVE", 2, 1, FINGERPRINT, firstTest, false));

        updateLifecycle("TESTED", 2, 1, FINGERPRINT, firstTest, false);
        assertEquals("TESTED", lifecycleStatus());
        assertEquals(2L, lifecycleVersion());

        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        update entegrasyon.baglanti_surumu_yasam_dongusu
                           set durum_kodu = 'ACTIVE', durum_surumu = 3,
                               hedef_parmak_izi = :fingerprint,
                               aktiflestirilme_zamani = current_timestamp
                         where baglanti_surumu_id = :versionId
                        """).param("fingerprint", SECOND_FINGERPRINT)
                .param("versionId", versionId).update());

        updateLifecycle("ACTIVE", 3, 1, FINGERPRINT, firstTest, true);
        assertEquals("ACTIVE", lifecycleStatus());

        UUID secondTest = insertPassedTest(2, FINGERPRINT);
        updateLifecycle("ACTIVE", 4, 1, FINGERPRINT, secondTest, false);
        assertEquals(4L, lifecycleVersion());
    }

    @Test
    void keepsTestJournalAppendOnlyAndRejectsMalformedSuccess() {
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu_testi(
                            proje_id, baglanti_id, baglanti_surumu_id, deneme_no,
                            sonuc_kodu, baslama_zamani, tamamlanma_zamani, sure_ms)
                        values (:projectId, :connectionId, :versionId, 1,
                            'PASSED', current_timestamp, current_timestamp, 0)
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .param("versionId", versionId).update());

        UUID testUuid = insertPassedTest(1, FINGERPRINT);
        assertThrows(DataAccessException.class, () -> jdbc.sql(
                        "update entegrasyon.baglanti_surumu_testi set sure_ms = 1 where uuid = :uuid")
                .param("uuid", testUuid).update());
        assertThrows(DataAccessException.class, () -> jdbc.sql(
                        "delete from entegrasyon.baglanti_surumu_testi where uuid = :uuid")
                .param("uuid", testUuid).update());

        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu_testi(
                            proje_id, baglanti_id, baglanti_surumu_id, deneme_no,
                            sonuc_kodu, hata_kodu, database_product,
                            baslama_zamani, tamamlanma_zamani, sure_ms)
                        values (:projectId, :connectionId, :versionId, 2,
                            'FAILED', 'password=unsafe', 'Oracle',
                            current_timestamp, current_timestamp, 0)
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .param("versionId", versionId).update());
    }

    @Test
    void preservesV17VersionAndBackfillsItAsDraft() {
        Flyway flyway = flyway();
        flyway.clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "entegrasyon").target("17").load().migrate();

        long legacyProject = jdbc.sql(
                        "insert into entegrasyon.proje(kod, ad) values ('LIFECYCLE_UPGRADE', 'Lifecycle upgrade') returning id")
                .query(Long.class).single();
        long legacyConnection = jdbc.sql("""
                        insert into entegrasyon.baglanti(
                            proje_id, kod, veritabani_turu, durum_kodu, ad)
                        values (:projectId, 'SKY_LEGACY', 'ORACLE', 'AKTIF', 'SKY legacy')
                        returning id
                        """).param("projectId", legacyProject).query(Long.class).single();
        UUID legacyUuid = UUID.randomUUID();
        long legacyVersion = jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, surucu_referansi,
                            sunucu_adi, sid, port, uuid)
                        values (:projectId, :connectionId, 1, 'oracle.jdbc.OracleDriver',
                            'sky.invalid', 'TTBP2', 1907, :uuid)
                        returning id
                        """).param("projectId", legacyProject).param("connectionId", legacyConnection)
                .param("uuid", legacyUuid).query(Long.class).single();

        flyway.migrate();

        assertEquals(legacyUuid, jdbc.sql(
                        "select uuid from entegrasyon.baglanti_surumu where id = :id")
                .param("id", legacyVersion).query(UUID.class).single());
        assertEquals("DRAFT", jdbc.sql("""
                        select durum_kodu from entegrasyon.baglanti_surumu_yasam_dongusu
                         where baglanti_surumu_id = :id
                        """).param("id", legacyVersion).query(String.class).single());
    }

    private void seedVersion() {
        projectId = jdbc.sql(
                        "insert into entegrasyon.proje(kod, ad) values ('LIFECYCLE_IT', 'Lifecycle IT') returning id")
                .query(Long.class).single();
        connectionId = jdbc.sql("""
                        insert into entegrasyon.baglanti(proje_id, kod, veritabani_turu, ad)
                        values (:projectId, 'ORACLE_IT', 'ORACLE', 'Oracle IT') returning id
                        """).param("projectId", projectId).query(Long.class).single();
        versionId = jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, surucu_referansi,
                            sunucu_adi, servis_adi, port)
                        values (:projectId, :connectionId, 1, 'oracle.jdbc.OracleDriver',
                            'db.invalid', 'ORCL', 1521) returning id
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .query(Long.class).single();
    }

    private UUID insertPassedTest(int attempt, String fingerprint) {
        return jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu_testi(
                            proje_id, baglanti_id, baglanti_surumu_id, deneme_no,
                            sonuc_kodu, database_product, database_version,
                            database_major, database_minor, driver_name, driver_version,
                            hedef_kimlik_surumu, hedef_parmak_izi,
                            baslama_zamani, tamamlanma_zamani, sure_ms)
                        values (:projectId, :connectionId, :versionId, :attempt,
                            'PASSED', 'Oracle', '19.0', 19, 0, 'Oracle JDBC', '23',
                            1, :fingerprint, current_timestamp, current_timestamp, 0)
                        returning uuid
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .param("versionId", versionId).param("attempt", attempt)
                .param("fingerprint", fingerprint).query(UUID.class).single();
    }

    private void updateLifecycle(
            String status,
            long stateVersion,
            int identityVersion,
            String fingerprint,
            UUID testUuid,
            boolean activate) {
        String sql = """
                update entegrasyon.baglanti_surumu_yasam_dongusu
                   set durum_kodu = :status,
                       durum_surumu = :stateVersion,
                       hedef_kimlik_surumu = :identityVersion,
                       hedef_parmak_izi = :fingerprint,
                       son_basarili_test_uuid = :testUuid,
                       test_edilme_zamani = (
                           select tamamlanma_zamani
                             from entegrasyon.baglanti_surumu_testi
                            where uuid = :testUuid),
                       aktiflestirilme_zamani = case when :activate
                           then current_timestamp else aktiflestirilme_zamani end
                 where baglanti_surumu_id = :versionId
                """;
        jdbc.sql(sql).param("status", status).param("stateVersion", stateVersion)
                .param("identityVersion", identityVersion).param("fingerprint", fingerprint)
                .param("testUuid", testUuid).param("activate", activate)
                .param("versionId", versionId).update();
    }

    private String lifecycleStatus() {
        return jdbc.sql("""
                        select durum_kodu from entegrasyon.baglanti_surumu_yasam_dongusu
                         where baglanti_surumu_id = :id
                        """).param("id", versionId).query(String.class).single();
    }

    private long lifecycleVersion() {
        return jdbc.sql("""
                        select durum_surumu from entegrasyon.baglanti_surumu_yasam_dongusu
                         where baglanti_surumu_id = :id
                        """).param("id", versionId).query(Long.class).single();
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").schemas("public", "entegrasyon")
                .cleanDisabled(false).load();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setUrl(isolatedUrl(required("SPRING_DATASOURCE_URL")));
            source.setUsername(required("SPRING_DATASOURCE_USERNAME"));
            source.setPassword(required("SPRING_DATASOURCE_PASSWORD"));
            return source;
        }
        @Bean JdbcClient jdbcClient(DataSource source) { return JdbcClient.create(source); }
        private static String isolatedUrl(String url) {
            String database = url.split("\\?", 2)[0];
            database = database.substring(database.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (!database.endsWith("_it") && !database.endsWith("_test")) {
                throw new IllegalStateException("Lifecycle migration tests require an isolated database.");
            }
            return url;
        }
        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
            return value;
        }
    }
}

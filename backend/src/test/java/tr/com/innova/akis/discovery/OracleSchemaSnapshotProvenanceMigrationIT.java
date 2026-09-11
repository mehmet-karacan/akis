package tr.com.innova.akis.discovery;

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

class OracleSchemaSnapshotProvenanceMigrationIT {

    private static final String TARGET_FINGERPRINT = "a".repeat(64);
    private static final String SCHEMA_FINGERPRINT = "b".repeat(64);

    private static AnnotationConfigApplicationContext context;
    private static DataSource dataSource;
    private static JdbcClient jdbc;

    private Fixture fixture;

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
        fixture = seedFixture("PROVENANCE_IT");
    }

    @Test
    void acceptsOnlyMatchingPassedTestEvidenceAndIsAppendOnly() {
        insertEvidence(fixture, TARGET_FINGERPRINT);
        assertEquals(1, jdbc.sql("""
                        select count(*) from entegrasyon.sema_goruntusu_oracle_kaniti
                         where sema_goruntusu_id = :snapshotId
                        """).param("snapshotId", fixture.snapshotId()).query(Integer.class).single());

        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        update entegrasyon.sema_goruntusu_oracle_kaniti
                           set yakalama_sozlesmesi_surumu = 2
                         where sema_goruntusu_id = :snapshotId
                        """).param("snapshotId", fixture.snapshotId()).update());
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        delete from entegrasyon.sema_goruntusu_oracle_kaniti
                         where sema_goruntusu_id = :snapshotId
                        """).param("snapshotId", fixture.snapshotId()).update());
    }

    @Test
    void rejectsMismatchedFingerprintAndSnapshotVersion() {
        assertThrows(DataAccessException.class,
                () -> insertEvidence(fixture, "c".repeat(64)));

        long secondVersionId = jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, surucu_referansi,
                            sunucu_adi, servis_adi, port)
                        values (:projectId, :connectionId, 2, 'oracle.jdbc.OracleDriver',
                            'db.invalid', 'ORCL', 1521)
                        returning id
                        """).param("projectId", fixture.projectId())
                .param("connectionId", fixture.connectionId()).query(Long.class).single();
        UUID secondTestUuid = insertPassedTest(
                fixture.projectId(), fixture.connectionId(), secondVersionId, 1);
        Fixture mismatched = new Fixture(
                fixture.projectId(), fixture.connectionId(), secondVersionId,
                fixture.snapshotId(), secondTestUuid);
        assertThrows(DataAccessException.class,
                () -> insertEvidence(mismatched, TARGET_FINGERPRINT));
    }

    @Test
    void doesNotBackfillExistingSnapshotsDuringV19Upgrade() {
        Flyway flyway = flyway();
        flyway.clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "entegrasyon").target("19").load().migrate();
        Fixture legacy = seedFixture("PROVENANCE_UPGRADE");

        flyway.migrate();

        assertEquals(1, jdbc.sql("select count(*) from entegrasyon.sema_goruntusu where id = :id")
                .param("id", legacy.snapshotId()).query(Integer.class).single());
        assertEquals(0, jdbc.sql("""
                        select count(*) from entegrasyon.sema_goruntusu_oracle_kaniti
                         where sema_goruntusu_id = :id
                        """).param("id", legacy.snapshotId()).query(Integer.class).single());
    }

    private Fixture seedFixture(String projectCode) {
        long projectId = jdbc.sql("""
                        insert into entegrasyon.proje(kod, ad)
                        values (:code, :name) returning id
                        """).param("code", projectCode).param("name", projectCode)
                .query(Long.class).single();
        long connectionId = jdbc.sql("""
                        insert into entegrasyon.baglanti(
                            proje_id, kod, veritabani_turu, durum_kodu, ad)
                        values (:projectId, 'ORACLE', 'ORACLE', 'AKTIF', 'Oracle')
                        returning id
                        """).param("projectId", projectId).query(Long.class).single();
        long versionId = jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, surucu_referansi,
                            sunucu_adi, servis_adi, port)
                        values (:projectId, :connectionId, 1, 'oracle.jdbc.OracleDriver',
                            'db.invalid', 'ORCL', 1521)
                        returning id
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .query(Long.class).single();
        UUID testUuid = insertPassedTest(projectId, connectionId, versionId, 1);

        long logicalSchemaId = jdbc.sql("""
                        insert into entegrasyon.mantiksal_sema(proje_id, kod, ad)
                        values (:projectId, 'LOGICAL', 'Logical') returning id
                        """).param("projectId", projectId).query(Long.class).single();
        long modelId = jdbc.sql("""
                        insert into entegrasyon.model(proje_id, mantiksal_sema_id, kod, ad)
                        values (:projectId, :logicalSchemaId, 'MODEL', 'Model') returning id
                        """).param("projectId", projectId).param("logicalSchemaId", logicalSchemaId)
                .query(Long.class).single();
        long dataObjectId = jdbc.sql("""
                        insert into entegrasyon.veri_nesnesi(
                            proje_id, model_id, kod, nesne_referansi, tur_kodu, ad)
                        values (:projectId, :modelId, 'TABLE_A', 'APP.TABLE_A', 'TABLO', 'Table A')
                        returning id
                        """).param("projectId", projectId).param("modelId", modelId)
                .query(Long.class).single();
        long physicalSchemaId = jdbc.sql("""
                        insert into entegrasyon.fiziksel_sema(
                            proje_id, baglanti_id, kod, sema_referansi, ad)
                        values (:projectId, :connectionId, 'APP', 'APP', 'App') returning id
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .query(Long.class).single();
        long snapshotId = jdbc.sql("""
                        insert into entegrasyon.sema_goruntusu(
                            proje_id, veri_nesnesi_id, fiziksel_sema_id,
                            baglanti_surumu_id, parmak_izi, motor_surumu, kesif_zamani)
                        values (:projectId, :dataObjectId, :physicalSchemaId,
                            :versionId, :fingerprint, 'Oracle 19c', current_timestamp)
                        returning id
                        """).param("projectId", projectId).param("dataObjectId", dataObjectId)
                .param("physicalSchemaId", physicalSchemaId).param("versionId", versionId)
                .param("fingerprint", SCHEMA_FINGERPRINT).query(Long.class).single();
        return new Fixture(projectId, connectionId, versionId, snapshotId, testUuid);
    }

    private UUID insertPassedTest(
            long projectId, long connectionId, long versionId, int attemptNumber) {
        return jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu_testi(
                            proje_id, baglanti_id, baglanti_surumu_id, deneme_no,
                            sonuc_kodu, database_product, database_version,
                            database_major, database_minor, driver_name, driver_version,
                            hedef_kimlik_surumu, hedef_parmak_izi,
                            baslama_zamani, tamamlanma_zamani, sure_ms)
                        values (:projectId, :connectionId, :versionId, :attemptNumber,
                            'PASSED', 'Oracle', '19.0', 19, 0, 'Oracle JDBC', '23',
                            1, :fingerprint, current_timestamp, current_timestamp, 0)
                        returning uuid
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .param("versionId", versionId).param("attemptNumber", attemptNumber)
                .param("fingerprint", TARGET_FINGERPRINT).query(UUID.class).single();
    }

    private void insertEvidence(Fixture value, String fingerprint) {
        jdbc.sql("""
                        insert into entegrasyon.sema_goruntusu_oracle_kaniti(
                            sema_goruntusu_id, proje_id, baglanti_id, baglanti_surumu_id,
                            baglanti_surumu_testi_uuid, hedef_kimlik_surumu,
                            hedef_parmak_izi, yakalama_sozlesmesi_surumu)
                        values (:snapshotId, :projectId, :connectionId, :versionId,
                            :testUuid, 1, :fingerprint, 1)
                        """).param("snapshotId", value.snapshotId())
                .param("projectId", value.projectId()).param("connectionId", value.connectionId())
                .param("versionId", value.versionId()).param("testUuid", value.testUuid())
                .param("fingerprint", fingerprint).update();
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "entegrasyon").cleanDisabled(false).load();
    }

    private record Fixture(
            long projectId,
            long connectionId,
            long versionId,
            long snapshotId,
            UUID testUuid) {
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
                throw new IllegalStateException("Provenance migration tests require an isolated database.");
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

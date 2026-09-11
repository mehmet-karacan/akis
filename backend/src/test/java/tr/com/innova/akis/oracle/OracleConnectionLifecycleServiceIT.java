package tr.com.innova.akis.oracle;

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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.LifecycleRow;
import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.TestAttemptRow;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;

class OracleConnectionLifecycleServiceIT {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID CONNECTION_UUID = UUID.randomUUID();
    private static final UUID VERSION_UUID = UUID.randomUUID();
    private static final String FINGERPRINT = "a".repeat(64);

    private static DataSource dataSource;
    private static JdbcClient jdbc;
    private static OracleConnectionLifecycleRepository repository;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void configureDatabase() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(isolatedUrl(required("SPRING_DATASOURCE_URL")));
        source.setUsername(required("SPRING_DATASOURCE_USERNAME"));
        source.setPassword(required("SPRING_DATASOURCE_PASSWORD"));
        dataSource = source;
        jdbc = JdbcClient.create(source);
        repository = new OracleConnectionLifecycleRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterAll
    static void cleanDatabase() {
        if (dataSource != null) {
            flyway().clean();
        }
    }

    @BeforeEach
    void migrateAndSeed() {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();
        long projectId = jdbc.sql("""
                        insert into entegrasyon.proje(uuid, kod, ad)
                        values (:uuid, 'LIFECYCLE_SERVICE_IT', 'Lifecycle service IT')
                        returning id
                        """).param("uuid", PROJECT_UUID).query(Long.class).single();
        long connectionId = jdbc.sql("""
                        insert into entegrasyon.baglanti(
                            proje_id, uuid, kod, veritabani_turu, ad)
                        values (:projectId, :uuid, 'ORACLE_SERVICE_IT', 'ORACLE', 'Oracle service IT')
                        returning id
                        """).param("projectId", projectId).param("uuid", CONNECTION_UUID)
                .query(Long.class).single();
        jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, uuid, surum_no, surucu_referansi,
                            sunucu_adi, servis_adi, port)
                        values (:projectId, :connectionId, :uuid, 1, 'oracle.jdbc.OracleDriver',
                            'db.invalid', 'ORCL', 1521)
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .param("uuid", VERSION_UUID).update();
    }

    @Test
    void journalsTestsPinsTargetAndActivatesWithIdempotentRetry() {
        FakeDiscovery discovery = new FakeDiscovery(probe(FINGERPRINT));
        OracleConnectionLifecycleService service = service(discovery);

        TestAttemptRow passed = service.test(PROJECT_UUID, CONNECTION_UUID, VERSION_UUID);
        assertEquals("PASSED", passed.outcome());
        LifecycleRow tested = service.lifecycle(PROJECT_UUID, CONNECTION_UUID, VERSION_UUID);
        assertEquals("TESTED", tested.status());
        assertEquals(2L, tested.stateVersion());
        assertEquals(FINGERPRINT, tested.targetFingerprint());

        LifecycleRow active = service.activate(
                PROJECT_UUID, CONNECTION_UUID, VERSION_UUID, passed.uuid(), 2L);
        assertEquals("ACTIVE", active.status());
        assertEquals(3L, active.stateVersion());
        assertEquals(active, service.activate(
                PROJECT_UUID, CONNECTION_UUID, VERSION_UUID, passed.uuid(), 2L));

        TestAttemptRow retest = service.test(PROJECT_UUID, CONNECTION_UUID, VERSION_UUID);
        assertEquals("PASSED", retest.outcome());
        assertEquals(4L, service.lifecycle(
                PROJECT_UUID, CONNECTION_UUID, VERSION_UUID).stateVersion());
    }

    @Test
    void journalsSanitizedFailuresAndTargetMismatchWithoutChangingPinnedTarget() {
        FakeDiscovery discovery = new FakeDiscovery(probe(FINGERPRINT));
        OracleConnectionLifecycleService service = service(discovery);
        service.test(PROJECT_UUID, CONNECTION_UUID, VERSION_UUID);

        discovery.probe = probe("b".repeat(64));
        ApiException mismatch = assertThrows(ApiException.class,
                () -> service.test(PROJECT_UUID, CONNECTION_UUID, VERSION_UUID));
        assertEquals("ORACLE_TARGET_MISMATCH", mismatch.code());
        assertEquals(FINGERPRINT, service.lifecycle(
                PROJECT_UUID, CONNECTION_UUID, VERSION_UUID).targetFingerprint());

        discovery.failure = new ApiException(
                HttpStatus.BAD_GATEWAY, "ORACLE_CONNECTION_FAILED", "unsafe detail");
        assertThrows(ApiException.class,
                () -> service.test(PROJECT_UUID, CONNECTION_UUID, VERSION_UUID));
        var attempts = service.listTests(PROJECT_UUID, CONNECTION_UUID, VERSION_UUID, 10);
        assertEquals(3, attempts.size());
        assertEquals("FAILED", attempts.get(0).outcome());
        assertEquals("ORACLE_CONNECTION_FAILED", attempts.get(0).errorCode());
        assertEquals("TARGET_MISMATCH", attempts.get(1).outcome());
        assertEquals("ORACLE_TARGET_MISMATCH", attempts.get(1).errorCode());
    }

    private OracleConnectionLifecycleService service(FakeDiscovery discovery) {
        return new OracleConnectionLifecycleService(discovery, repository, transactions);
    }

    private ConnectionProbe probe(String fingerprint) {
        return new ConnectionProbe(
                "Oracle", "Oracle Database 19c", 19, 0,
                "Oracle JDBC", "23", 1, fingerprint);
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").schemas("public", "entegrasyon")
                .cleanDisabled(false).load();
    }

    private static String isolatedUrl(String url) {
        String database = url.split("\\?", 2)[0];
        database = database.substring(database.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!database.endsWith("_it") && !database.endsWith("_test")) {
            throw new IllegalStateException("Lifecycle service tests require an isolated database.");
        }
        return url;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }

    private static final class FakeDiscovery extends OracleDiscoveryService {
        private ConnectionProbe probe;
        private ApiException failure;

        private FakeDiscovery(ConnectionProbe probe) {
            super(null, null, null);
            this.probe = probe;
        }

        @Override
        public ConnectionProbe testConnection(
                UUID projectUuid, UUID connectionUuid, UUID connectionVersionUuid) {
            if (failure != null) {
                throw failure;
            }
            return probe;
        }
    }
}

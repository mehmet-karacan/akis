package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;

class CleanConnectionLifecycleRepositoryIT {

    private static OracleConnectionLifecycleRepository repository;
    private static UUID projectUuid;
    private static UUID connectionUuid;
    private static UUID versionUuid;

    @BeforeAll
    static void connectToCleanBaseline() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_connections_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean lifecycle test requires its generated test database.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        repository = new OracleConnectionLifecycleRepository(jdbc);
        projectUuid = UUID.randomUUID();
        connectionUuid = UUID.randomUUID();
        versionUuid = UUID.randomUUID();
        long projectId = jdbc.sql("insert into akis.proje(uuid, kod, ad) values (:uuid, 'LIFECYCLE_IT', 'Lifecycle IT') returning id")
                .param("uuid", projectUuid).query(Long.class).single();
        long connectionId = jdbc.sql("insert into akis.baglanti(proje_id, uuid, kod, ad, saglayici_turu) values (:projectId, :uuid, 'ORACLE_LC', 'Oracle Lifecycle', 'ORACLE') returning id")
                .param("projectId", projectId).param("uuid", connectionUuid).query(Long.class).single();
        jdbc.sql("""
                insert into akis.baglanti_surumu(
                    proje_id, baglanti_id, uuid, surum_no, baglanti_modu,
                    surucu_sinifi, sunucu_adi, port, servis_adi)
                values (:projectId, :connectionId, :uuid, 1, 'JDBC',
                    'oracle.jdbc.OracleDriver', 'db.example', 1521, 'ORCL')
                """).param("projectId", projectId).param("connectionId", connectionId)
                .param("uuid", versionUuid).update();
    }

    @Test
    void recordsTestEvidenceAndActivatesTheExactTestedRevision() {
        UUID testUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var probe = new ConnectionProbe(
                "Oracle Database", "19c", 19, 0, "Oracle JDBC", "23", 1, "a".repeat(64));

        var attempt = repository.insertSuccessfulAttempt(
                projectUuid, connectionUuid, versionUuid, testUuid, 1, "PASSED", probe, now, now, 0);
        repository.markTested(projectUuid, versionUuid, testUuid, probe, now);
        var tested = repository.findLifecycle(projectUuid, connectionUuid, versionUuid).orElseThrow();
        var active = repository.activate(
                projectUuid, connectionUuid, versionUuid, testUuid, tested.stateVersion());

        assertEquals("PASSED", attempt.outcome());
        assertEquals("ACTIVE", active.status());
        assertEquals(testUuid, active.latestSuccessfulTestUuid());
        assertEquals(1, repository.listAttempts(projectUuid, connectionUuid, versionUuid, 10).size());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.TableMetadata;
import tr.com.innova.akis.postgres.JdbcPostgresSchemaDiscovery;

class PostgresDiscoveryIntegrationTest {

    private static final Pattern ISOLATED_DATABASE = Pattern.compile(
            "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1):([0-9]{1,5})/(akis_bundle_test_[0-9]+)$");
    private static PostgreSQLContainer<?> postgres;
    private static boolean dockerAvailable;
    private static IsolatedFixture isolated;

    private record IsolatedFixture(String url, String host, int port, String database,
            String username, String password) {}

    @BeforeAll
    static void startContainer() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        Matcher match = ISOLATED_DATABASE.matcher(url == null ? "" : url);
        if (match.matches()) {
            String username = System.getenv("SPRING_DATASOURCE_USERNAME");
            String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalStateException("Isolated PostgreSQL test credentials are required.");
            }
            isolated = new IsolatedFixture(url, match.group(1), Integer.parseInt(match.group(2)),
                    match.group(3), username, password);
            return;
        }
        dockerAvailable = dockerAvailable();
        if (!dockerAvailable) {
            return;
        }
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("akis_discovery")
                .withUsername("akis")
                .withPassword("akis");
        postgres.start();
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void discoversTablesViewsAndMaterializedViews() throws Exception {
        assumeTrue(isolated != null || dockerAvailable, "NOT_RUN: Docker/Testcontainers not available");

        String jdbcUrl = isolated != null ? isolated.url() : postgres.getJdbcUrl();
        String username = isolated != null ? isolated.username() : postgres.getUsername();
        String password = isolated != null ? isolated.password() : postgres.getPassword();
        String host = isolated != null ? isolated.host() : postgres.getHost();
        String database = isolated != null ? isolated.database() : postgres.getDatabaseName();
        int port = isolated != null ? isolated.port() : postgres.getFirstMappedPort();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS sales");
            statement.execute("CREATE TABLE sales.customer (id bigint primary key, name varchar(100))");
            statement.execute("CREATE VIEW sales.customer_view AS SELECT id, name FROM sales.customer");
            statement.execute("CREATE MATERIALIZED VIEW sales.customer_mv AS SELECT id, name FROM sales.customer");
        }

        JdbcPostgresSchemaDiscovery discovery = new JdbcPostgresSchemaDiscovery(new ObjectMapper());
        ConnectionProfile unpinned = new ConnectionProfile(
                1L, 1L, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "POSTGRESQL", "JDBC", null, "org.postgresql.Driver",
                host, database, null,
                "DISABLED", port,
                new ObjectMapper().createObjectNode(), null, null, null,
                "ACTIVE", null, null);

        try (Credentials credentials = new Credentials(username, password.toCharArray())) {
            var probe = discovery.test(unpinned, credentials);
            ConnectionProfile profile = new ConnectionProfile(
                    unpinned.projectId(), unpinned.connectionId(), unpinned.connectionUuid(),
                    unpinned.connectionVersionUuid(), unpinned.databaseType(), unpinned.mode(),
                    unpinned.jndiName(), unpinned.driverReference(), unpinned.host(),
                    unpinned.serviceName(), unpinned.sid(), unpinned.tlsMode(), unpinned.port(),
                    unpinned.policy(), unpinned.secretProvider(), unpinned.secretReferencePath(),
                    unpinned.secretStatus(), "ACTIVE", probe.targetIdentityVersion(),
                    probe.targetFingerprint());
            DiscoveryResult result = discovery.discover(profile, credentials, "sales", null, 100);
            List<String> types = result.tables().stream().map(TableMetadata::type).sorted().toList();
            assertTrue(types.containsAll(List.of("TABLE", "VIEW", "MATERIALIZED_VIEW")), "Expected table, view and materialized view but got " + types);
            assertEquals(3, result.tables().size());
        }
    }
}

package tr.com.innova.akis.spike.oracle;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class OracleJdbcSpikeApplication implements CommandLineRunner {

    private static final String OBJECT_COUNT_SQL = """
            select
                (select count(*) from user_tables) as table_count,
                (select count(*) from user_views) as view_count
            from dual
            """;

    private final OracleProbeConfig config;

    public OracleJdbcSpikeApplication() {
        this.config = OracleProbeConfig.fromEnvironment(System.getenv());
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OracleJdbcSpikeApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);

        try (ConfigurableApplicationContext ignored = application.run(args)) {
            // The probe runs through CommandLineRunner and then exits.
        }
    }

    @Override
    public void run(String... args) throws Exception {
        probe(config.source());
        probe(config.target());
        System.out.println("oracle_probe status=SUCCESS");
    }

    private void probe(OracleEndpoint endpoint) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", endpoint.username());
        properties.setProperty("password", endpoint.password());
        properties.setProperty(
                "oracle.net.CONNECT_TIMEOUT",
                Integer.toString(config.connectTimeoutMs()));
        properties.setProperty(
                "oracle.jdbc.ReadTimeout",
                Integer.toString(config.readTimeoutMs()));

        try (Connection connection = DriverManager.getConnection(endpoint.url(), properties)) {
            connection.setReadOnly(true);

            DatabaseMetaData metadata = connection.getMetaData();
            int actualMajor = metadata.getDatabaseMajorVersion();
            if (actualMajor != config.expectedMajor()) {
                throw new IllegalStateException(
                        "Oracle " + endpoint.role() + " major version mismatch: expected "
                                + config.expectedMajor() + " but received " + actualMajor);
            }

            ObjectCounts counts = readObjectCounts(connection);
            System.out.printf(
                    "oracle_probe role=%s status=CONNECTED database=%s version=%s driver=%s "
                            + "driver_version=%s username=%s tables=%d views=%d%n",
                    safe(endpoint.role()),
                    safe(metadata.getDatabaseProductName()),
                    safe(metadata.getDatabaseProductVersion()),
                    safe(metadata.getDriverName()),
                    safe(metadata.getDriverVersion()),
                    safe(metadata.getUserName()),
                    counts.tables(),
                    counts.views());
        }
    }

    private ObjectCounts readObjectCounts(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(OBJECT_COUNT_SQL);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Oracle discovery count query returned no row.");
            }
            return new ObjectCounts(result.getLong("table_count"), result.getLong("view_count"));
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
    }

    private record ObjectCounts(long tables, long views) {
    }

    record OracleEndpoint(String role, String url, String username, String password) {
    }

    record OracleProbeConfig(
            int expectedMajor,
            int connectTimeoutMs,
            int readTimeoutMs,
            OracleEndpoint source,
            OracleEndpoint target) {

        static OracleProbeConfig fromEnvironment(Map<String, String> environment) {
            int expectedMajor = positiveInt(environment, "AKIS_ORACLE_EXPECTED_MAJOR");
            int connectTimeoutMs = positiveInt(environment, "AKIS_ORACLE_CONNECT_TIMEOUT_MS");
            int readTimeoutMs = positiveInt(environment, "AKIS_ORACLE_READ_TIMEOUT_MS");

            OracleEndpoint source = endpoint(environment, "SOURCE");
            OracleEndpoint target = endpoint(environment, "TARGET");
            return new OracleProbeConfig(
                    expectedMajor,
                    connectTimeoutMs,
                    readTimeoutMs,
                    source,
                    target);
        }

        private static OracleEndpoint endpoint(
                Map<String, String> environment,
                String role) {
            String prefix = "AKIS_ORACLE_" + role + "_";
            return new OracleEndpoint(
                    role.toLowerCase(),
                    required(environment, prefix + "URL"),
                    required(environment, prefix + "USERNAME"),
                    required(environment, prefix + "PASSWORD"));
        }

        private static int positiveInt(Map<String, String> environment, String name) {
            String value = required(environment, name);
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new IllegalArgumentException(name + " must be greater than zero.");
                }
                return parsed;
            }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " must be an integer.", exception);
            }
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Missing required local environment value: " + name);
            }
            return value;
        }
    }
}

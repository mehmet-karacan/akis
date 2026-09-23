package tr.com.innova.akis.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;

/**
 * PostgreSQL discovery + fingerprint stability (research report §35.1): a schema with PK/UK/FK/CHECK/index and every
 * mapped type discovers to the same fingerprint twice, a column change changes it, and the recorded engine version is
 * the technology-neutral constant rather than the server's exact build (so a point release never invalidates a pinned
 * snapshot). Runs against a real, ephemeral Testcontainers PostgreSQL when Docker is reachable; otherwise falls back to
 * the same {@code AKIS_POSTGRES_RUNTIME_*} fixture the other PostgreSQL ITs use, in a throwaway schema it drops after.
 */
class PostgresSchemaDiscoveryContainerIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PostgresSchemaDictionaryReader READER = new PostgresSchemaDictionaryReader(MAPPER);
    private static final String SCHEMA = "akis_fp_it_" + Long.toHexString(System.nanoTime());

    private static PostgreSQLContainer<?> container;
    private static Connection connection;

    @BeforeAll
    static void connect() throws Exception {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new PostgreSQLContainer<>("postgres:18-bookworm");
            container.start();
            connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        }
        else {
            String url = System.getenv("AKIS_POSTGRES_RUNTIME_URL");
            String username = System.getenv("AKIS_POSTGRES_RUNTIME_USERNAME");
            String password = System.getenv("AKIS_POSTGRES_RUNTIME_PASSWORD");
            assumeTrue(url != null && username != null && password != null,
                    "Neither Docker nor the PostgreSQL runtime fixture is available");
            connection = DriverManager.getConnection(url, username, password);
        }
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE SCHEMA " + SCHEMA);
            ddl.execute("""
                    CREATE TABLE %s.musteri (
                        id BIGINT GENERATED ALWAYS AS IDENTITY,
                        kod VARCHAR(20) NOT NULL,
                        ad VARCHAR(100),
                        bakiye NUMERIC(14,2) NOT NULL DEFAULT 0,
                        aktif BOOLEAN NOT NULL DEFAULT TRUE,
                        veri BYTEA,
                        olusturma TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT pk_musteri_fp PRIMARY KEY (id),
                        CONSTRAINT uq_musteri_fp_kod UNIQUE (kod),
                        CONSTRAINT ck_musteri_fp_bakiye CHECK (bakiye >= 0)
                    )
                    """.formatted(SCHEMA));
            ddl.execute("""
                    CREATE TABLE %s.siparis (
                        id BIGINT GENERATED ALWAYS AS IDENTITY,
                        musteri_id BIGINT NOT NULL,
                        tutar NUMERIC(14,2) NOT NULL,
                        CONSTRAINT pk_siparis_fp PRIMARY KEY (id),
                        CONSTRAINT fk_siparis_fp_musteri FOREIGN KEY (musteri_id) REFERENCES %s.musteri(id)
                    )
                    """.formatted(SCHEMA, SCHEMA));
            ddl.execute("CREATE INDEX ix_siparis_fp_musteri ON " + SCHEMA + ".siparis(musteri_id)");
        }
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (connection != null) {
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
            finally { connection.close(); }
        }
        if (container != null) container.stop();
    }

    @Test
    void discoveringTheSameTableTwiceProducesTheSameFingerprint() {
        SchemaFingerprintInput first = fingerprintInput(SCHEMA, "musteri");
        SchemaFingerprintInput second = fingerprintInput(SCHEMA, "musteri");
        assertEquals(fingerprint(first), fingerprint(second));
        assertEquals(PostgresSchemaSnapshotCodecV1.ENGINE_VERSION, first.engineVersion(),
                "engine version is the technology-neutral constant, not the server's exact build");
    }

    @Test
    void alteringAColumnChangesTheFingerprint() throws Exception {
        String before = fingerprint(fingerprintInput(SCHEMA, "musteri"));
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE " + SCHEMA + ".musteri ALTER COLUMN ad TYPE VARCHAR(200)");
        }
        String after = fingerprint(fingerprintInput(SCHEMA, "musteri"));
        assertNotEquals(before, after);
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE " + SCHEMA + ".musteri ALTER COLUMN ad TYPE VARCHAR(100)");
        }
    }

    @Test
    void capturesPrimaryUniqueForeignAndCheckConstraintsWithReadableDetails() throws Exception {
        var definition = READER.read(connection, SCHEMA, "siparis");
        var byType = definition.constraints().stream().collect(java.util.stream.Collectors.groupingBy(c -> c.type()));
        assertTrue(byType.containsKey("PK"));
        assertTrue(byType.containsKey("FK"));
        var fk = byType.get("FK").get(0);
        assertEquals("musteri", fk.details().path("referencedTable").asText());
        assertEquals(List.of("musteri_id"), fk.columnReferences());

        var customer = READER.read(connection, SCHEMA, "musteri");
        var customerByType = customer.constraints().stream().collect(java.util.stream.Collectors.groupingBy(c -> c.type()));
        assertTrue(customerByType.containsKey("UK"));
        assertTrue(customerByType.containsKey("CHECK"));
        assertEquals("CHECK ((bakiye >= (0)::numeric))", customerByType.get("CHECK").get(0).details().path("expression").asText(),
                "pg_get_constraintdef returns the full clause, unlike Oracle's bare SEARCH_CONDITION; both are stored as opaque evidence text");
    }

    private static SchemaFingerprintInput fingerprintInput(String schema, String table) {
        tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.SnapshotDefinition definition;
        try { definition = READER.read(connection, schema, table); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        List<Column> columns = definition.columns().stream().map(c -> new Column(
                c.reference(), c.producerType(), c.canonicalType(), c.ordinal(), c.precision(), c.scale(),
                c.length(), c.timePrecision(), c.nullable(), c.defaultExpression(), c.name())).toList();
        List<SchemaFingerprintInput.Constraint> constraints = definition.constraints().stream().map(c -> new SchemaFingerprintInput.Constraint(
                c.externalReference(), c.type(), c.enabled(), c.detailVersion(), c.details(), c.name(), c.columnReferences())).toList();
        return new SchemaFingerprintInput(definition.engineVersion(), definition.propertyVersion(), definition.properties(), columns, constraints);
    }

    private static String fingerprint(SchemaFingerprintInput input) {
        return new SchemaFingerprint(MAPPER).calculate(input);
    }
}

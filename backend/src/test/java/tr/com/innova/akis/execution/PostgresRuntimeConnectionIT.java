package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;

/** Real local PostgreSQL session acceptance; skipped unless an explicit fixture is configured. */
class PostgresRuntimeConnectionIT {
    private static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-000000000902");

    @Test
    void opensRealPostgresSessionAndReadsFixture() throws Exception {
        String url = System.getenv("AKIS_POSTGRES_RUNTIME_URL");
        String username = System.getenv("AKIS_POSTGRES_RUNTIME_USERNAME");
        String password = System.getenv("AKIS_POSTGRES_RUNTIME_PASSWORD");
        assumeTrue(url != null && username != null && password != null,
                "PostgreSQL runtime fixture is not configured");
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID(), VERSION, "org.postgresql.Driver", "127.0.0.1",
                "akis_metadata", null, 5432, "DISABLED", new ObjectMapper().createObjectNode()
                        .put("connectTimeoutMs", 12_000).put("readTimeoutMs", 34_000)
                        .put("networkTimeoutMs", 45_000).put("queryTimeoutSeconds", 17),
                "ENV", "AKIS_POSTGRES_RUNTIME_SECRET");
        RuntimeOracleConnectionProvider provider = new RuntimeOracleConnectionProvider(
                binding -> java.util.Optional.of(profile), new ObjectMapper(),
                ignored -> "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                (ignored, properties) -> DriverManager.getConnection(url, properties), Runnable::run);
        try (var session = provider.openSource(binding())) {
            try (var statement = session.connection().createStatement();
                 var result = statement.executeQuery("select 1")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    private DatasetBinding binding() {
        return new DatasetBinding("SOURCE_NODE", DatasetRole.SOURCE, DatabaseType.ORACLE,
                DataObjectType.TABLE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), VERSION, UUID.randomUUID(), 1, "a".repeat(64),
                "AKIS.STG_HAKEDIS_TIPI", "AKIS", "STG_HAKEDIS_TIPI");
    }
}

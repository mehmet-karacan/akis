package tr.com.innova.akis.oracle;

import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;

final class OracleDiscoveryTestFixtures {

    static final UUID PROJECT_UUID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID CONNECTION_UUID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID PHYSICAL_SCHEMA_UUID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private OracleDiscoveryTestFixtures() {
    }

    /** An active Oracle JDBC connection whose secret comes from the given provider. */
    static ConnectionProfile profile(
            long connectionId,
            String secretProvider,
            String secretReference,
            String secretStatus) {
        return profile(connectionId, secretProvider, secretReference, secretStatus, "ACTIVE");
    }

    static ConnectionProfile profile(
            long connectionId,
            String secretProvider,
            String secretReference,
            String secretStatus,
            String lifecycleStatus) {
        return new ConnectionProfile(
                11L,
                connectionId,
                CONNECTION_UUID,
                CONNECTION_UUID,
                "ORACLE",
                "JDBC",
                null,
                "oracle.jdbc.OracleDriver",
                "oracle-host.internal",
                "APPDB",
                null,
                "DISABLED",
                1521,
                new ObjectMapper().createObjectNode(),
                secretProvider,
                secretReference,
                secretStatus,
                lifecycleStatus,
                1L,
                null,
                null,
                null);
    }
}

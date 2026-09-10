package tr.com.innova.akis.oracle;

import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;

final class OracleDiscoveryTestFixtures {

    static final UUID PROJECT_UUID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID CONNECTION_UUID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID VERSION_UUID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID PHYSICAL_SCHEMA_UUID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private OracleDiscoveryTestFixtures() {
    }

    static ConnectionProfile profile(
            long connectionId,
            String secretProvider,
            String secretReference,
            String secretStatus) {
        return new ConnectionProfile(
                11L,
                connectionId,
                CONNECTION_UUID,
                VERSION_UUID,
                "ORACLE",
                "oracle.jdbc.OracleDriver",
                "oracle-host.internal",
                "APPDB",
                null,
                "DISABLED",
                1521,
                new ObjectMapper().createObjectNode(),
                secretProvider,
                secretReference,
                secretStatus);
    }
}

package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;

class JdbcOracleMetadataGatewayTest {

    @Test
    void missingDriverReturnsSafeServiceUnavailableErrorBeforeNetworkAccess() {
        String secret = "must-never-appear";
        ConnectionProfile normal = OracleDiscoveryTestFixtures.profile(
                7L, "ENV", "AKIS_ORACLE_TEST_CREDENTIAL", "AKTIF");
        ConnectionProfile missingDriver = new ConnectionProfile(
                normal.projectId(), normal.connectionId(), normal.connectionUuid(),
                normal.connectionVersionUuid(), normal.databaseType(),
                "missing.oracle.Driver", normal.host(), normal.serviceName(), normal.sid(),
                normal.tlsMode(), normal.port(), normal.policy(), normal.secretProvider(),
                normal.secretReferencePath(), normal.secretStatus());
        JdbcOracleMetadataGateway gateway = new JdbcOracleMetadataGateway();

        try (Credentials credentials = new Credentials("reader", secret.toCharArray())) {
            ApiException error = assertThrows(
                    ApiException.class,
                    () -> gateway.test(missingDriver, credentials));

            assertEquals("ORACLE_DRIVER_UNAVAILABLE", error.code());
            assertTrue(!error.getMessage().contains(secret));
        }
    }
}

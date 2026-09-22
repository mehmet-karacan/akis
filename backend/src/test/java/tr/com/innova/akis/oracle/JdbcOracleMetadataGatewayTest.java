package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;

class JdbcSchemaDiscoveryPortTest {

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
        JdbcSchemaDiscoveryPort gateway = new JdbcSchemaDiscoveryPort(new ObjectMapper());

        try (Credentials credentials = new Credentials("reader", secret.toCharArray())) {
            ApiException error = assertThrows(
                    ApiException.class,
                    () -> gateway.test(missingDriver, credentials));

            assertEquals("ORACLE_DRIVER_UNAVAILABLE", error.code());
            assertTrue(!error.getMessage().contains(secret));
        }
    }

    @Test
    void discoveryTargetReattestationRejectsAChangedDatabaseIdentity() {
        ConnectionProfile profile = OracleDiscoveryTestFixtures.profile(
                7L, "ENV", "AKIS_ORACLE_TEST_CREDENTIAL", "AKTIF");
        OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity actual =
                new OracleDatabaseIdentityFingerprintV1().canonicalize("OTHERDB", "PDB1");

        ApiException error = assertThrows(
                ApiException.class,
                () -> new JdbcSchemaDiscoveryPort(new ObjectMapper())
                        .verifyPinnedTargetIdentity(profile, actual));

        assertEquals("ORACLE_TARGET_IDENTITY_MISMATCH", error.code());
    }

    @Test
    void discoveryTargetReattestationAcceptsThePinnedDatabaseIdentity() {
        ConnectionProfile base = OracleDiscoveryTestFixtures.profile(
                7L, "ENV", "AKIS_ORACLE_TEST_CREDENTIAL", "AKTIF");
        OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity actual =
                new OracleDatabaseIdentityFingerprintV1().canonicalize("APPDB", "PDB1");
        ConnectionProfile pinned = new ConnectionProfile(
                base.projectId(), base.connectionId(), base.connectionUuid(),
                base.connectionVersionUuid(), base.databaseType(), base.mode(),
                base.jndiName(), base.driverReference(), base.host(), base.serviceName(),
                base.sid(), base.tlsMode(), base.port(), base.policy(),
                base.secretProvider(), base.secretReferencePath(), base.secretStatus(),
                "ACTIVE", actual.identityVersion(), actual.fingerprint());

        new JdbcSchemaDiscoveryPort(new ObjectMapper())
                .verifyPinnedTargetIdentity(pinned, actual);
    }
}

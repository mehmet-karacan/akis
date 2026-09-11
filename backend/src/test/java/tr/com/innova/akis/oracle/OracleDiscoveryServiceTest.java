package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.PhysicalSchemaProfile;

class OracleDiscoveryServiceTest {

    private static final String ENVIRONMENT_NAME = "AKIS_ORACLE_TEST_CREDENTIAL";
    private static final String PASSWORD = "only-in-process-environment";

    @Test
    void connectionTestAccepts19cAndWipesTheResolvedPassword() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = oracle19c();
        OracleDiscoveryService service = service(repository, gateway);

        ConnectionProbe result = service.testConnection(
                OracleDiscoveryTestFixtures.PROJECT_UUID,
                OracleDiscoveryTestFixtures.CONNECTION_UUID,
                OracleDiscoveryTestFixtures.VERSION_UUID);

        assertEquals(19, result.databaseMajorVersion());
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void connectionTestRejectsOtherDatabaseVersionsWithoutLeakingTheCredential() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = new ConnectionProbe(
                "Oracle", "Oracle Database 21c", 21, 0, "Oracle JDBC", "23");
        OracleDiscoveryService service = service(repository, gateway);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.testConnection(
                        OracleDiscoveryTestFixtures.PROJECT_UUID,
                        OracleDiscoveryTestFixtures.CONNECTION_UUID,
                        OracleDiscoveryTestFixtures.VERSION_UUID));

        assertEquals("ORACLE_VERSION_UNSUPPORTED", error.code());
        assertTrue(!error.getMessage().contains(PASSWORD));
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void jndiConnectionTestDoesNotResolveApplicationCredentials() {
        ConnectionProfile profile = new ConnectionProfile(
                11L, 7L, OracleDiscoveryTestFixtures.CONNECTION_UUID,
                OracleDiscoveryTestFixtures.VERSION_UUID, "ORACLE", "JNDI",
                "java:comp/env/jdbc/OracleMain", null, null, null, null,
                "DISABLED", 0, new ObjectMapper().createObjectNode(),
                null, null, null);
        StubRepository repository = new StubRepository(
                profile,
                new PhysicalSchemaProfile(
                        OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID,
                        7L, "APP_OWNER", "AKTIF"));
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = oracle19c();

        ConnectionProbe result = service(repository, gateway).testConnection(
                OracleDiscoveryTestFixtures.PROJECT_UUID,
                OracleDiscoveryTestFixtures.CONNECTION_UUID,
                OracleDiscoveryTestFixtures.VERSION_UUID);

        assertEquals(19, result.databaseMajorVersion());
        assertArrayEquals(new char[0], gateway.passwordReference);
    }

    @Test
    void discoveryRejectsPhysicalSchemaFromAnotherConnectionBeforeOpeningOracle() {
        StubRepository repository = repository(99L);
        CapturingGateway gateway = new CapturingGateway();
        OracleDiscoveryService service = service(repository, gateway);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.discover(
                        OracleDiscoveryTestFixtures.PROJECT_UUID,
                        OracleDiscoveryTestFixtures.CONNECTION_UUID,
                        OracleDiscoveryTestFixtures.VERSION_UUID,
                        OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID,
                        null,
                        100));

        assertEquals("VALIDATION_FAILED", error.code());
        assertEquals(0, gateway.discoveryCalls);
    }

    @Test
    void discoveryNormalizesOracleIdentifiersAndUsesOnlyMetadataGateway() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.discovery = new DiscoveryResult(
                "APP_OWNER", OffsetDateTime.now(ZoneOffset.UTC), false, List.of());
        OracleDiscoveryService service = service(repository, gateway);

        DiscoveryResult result = service.discover(
                OracleDiscoveryTestFixtures.PROJECT_UUID,
                OracleDiscoveryTestFixtures.CONNECTION_UUID,
                OracleDiscoveryTestFixtures.VERSION_UUID,
                OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID,
                "hakedis_tipi",
                50);

        assertEquals("APP_OWNER", result.owner());
        assertEquals("APP_OWNER", gateway.owner);
        assertEquals("HAKEDIS_TIPI", gateway.tableName);
        assertEquals(50, gateway.limit);
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    private OracleDiscoveryService service(
            StubRepository repository,
            CapturingGateway gateway) {
        ObjectMapper objectMapper = new ObjectMapper();
        EnvironmentCredentialResolver resolver = new EnvironmentCredentialResolver(
                objectMapper,
                Map.of(
                        ENVIRONMENT_NAME,
                        "{\"username\":\"reader\",\"password\":\"" + PASSWORD + "\"}")::get);
        return new OracleDiscoveryService(repository, resolver, gateway);
    }

    private StubRepository repository(long physicalConnectionId) {
        ConnectionProfile profile = OracleDiscoveryTestFixtures.profile(
                7L, "ENV", ENVIRONMENT_NAME, "AKTIF");
        PhysicalSchemaProfile physical = new PhysicalSchemaProfile(
                OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID,
                physicalConnectionId,
                "app_owner",
                "AKTIF");
        return new StubRepository(profile, physical);
    }

    private ConnectionProbe oracle19c() {
        return new ConnectionProbe(
                "Oracle", "Oracle Database 19c", 19, 0, "Oracle JDBC", "23");
    }

    private static final class StubRepository extends OracleDiscoveryRepository {
        private final ConnectionProfile profile;
        private final PhysicalSchemaProfile physicalSchema;

        private StubRepository(
                ConnectionProfile profile,
                PhysicalSchemaProfile physicalSchema) {
            super(null, null);
            this.profile = profile;
            this.physicalSchema = physicalSchema;
        }

        @Override
        Optional<ConnectionProfile> findConnectionProfile(
                UUID projectUuid,
                UUID connectionUuid,
                UUID connectionVersionUuid) {
            return Optional.of(profile);
        }

        @Override
        Optional<PhysicalSchemaProfile> findPhysicalSchema(
                long projectId,
                UUID physicalSchemaUuid) {
            return Optional.of(physicalSchema);
        }
    }

    private static final class CapturingGateway implements OracleMetadataGateway {
        private ConnectionProbe probe;
        private DiscoveryResult discovery;
        private char[] passwordReference;
        private int discoveryCalls;
        private String owner;
        private String tableName;
        private int limit;

        @Override
        public ConnectionProbe test(ConnectionProfile profile, Credentials credentials) {
            passwordReference = credentials.password();
            return probe;
        }

        @Override
        public DiscoveryResult discover(
                ConnectionProfile profile,
                Credentials credentials,
                String owner,
                String tableName,
                int limit) {
            discoveryCalls++;
            passwordReference = credentials.password();
            this.owner = owner;
            this.tableName = tableName;
            this.limit = limit;
            return discovery;
        }
    }
}

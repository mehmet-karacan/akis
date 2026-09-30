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
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DataObjectCaptureProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DraftConnection;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.GovernedSnapshotCapture;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.PhysicalSchemaProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.SnapshotCapture;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.TableMetadata;
import tr.com.innova.akis.security.ConnectionCredentialCipher;

class OracleDiscoveryServiceTest {

    private static final String ENVIRONMENT_NAME = "AKIS_ORACLE_TEST_CREDENTIAL";
    private static final String PASSWORD = "only-in-process-environment";
    private static final ConnectionCredentialCipher CIPHER = new ConnectionCredentialCipher("test-key");

    @Test
    void connectionTestAccepts19cAndWipesTheResolvedPassword() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = oracle19c();

        ConnectionProbe result = service(repository, gateway).testConnection(OracleDiscoveryTestFixtures.CONNECTION_UUID);

        assertEquals(19, result.databaseMajorVersion());
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void connectionTestDecryptsTheTableStoredCredential() {
        String encrypted = CIPHER.encrypt("{\"username\":\"reader\",\"password\":\"" + PASSWORD + "\"}");
        StubRepository repository = new StubRepository(
                OracleDiscoveryTestFixtures.profile(7L, "TABLO", encrypted, "AKTIF"), physical(7L), "TABLO");
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = oracle19c();

        service(repository, gateway).testConnection(OracleDiscoveryTestFixtures.CONNECTION_UUID);

        assertEquals("reader", gateway.username);
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void schemaListUsesTheStoredConnectionAndWipesTheResolvedPassword() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();

        List<String> result = service(repository, gateway).listSchemas(OracleDiscoveryTestFixtures.CONNECTION_UUID);

        assertEquals(List.of("APP_OWNER", "UPSTREAM_SCHEMA"), result);
        assertEquals(1, gateway.schemaListCalls);
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void draftConnectionTestUsesTransientFieldsWithoutRepositoryMetadata() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = oracle19c();

        ConnectionProbe result = service(repository, gateway).testDraftConnection(new DraftConnection(
                "ORACLE", "JDBC", null, "10.0.0.1", 1521, "ORCL", null, null, null,
                "reader", PASSWORD, 10000, 30000, 300));

        assertEquals(19, result.databaseMajorVersion());
        assertEquals("reader", gateway.username);
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void draftConnectionTestSkipsOracleVersionRuleForOtherProviders() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.technology = "POSTGRESQL";
        gateway.probe = new ConnectionProbe("PostgreSQL", "16.2", 16, 2, "PostgreSQL JDBC", "42");

        ConnectionProbe result = service(repository, gateway).testDraftConnection(new DraftConnection(
                "POSTGRESQL", "JDBC", null, "127.0.0.1", 5432, null, null, "akis", null,
                "akis_app", PASSWORD, null, null, null));

        assertEquals(16, result.databaseMajorVersion());
    }

    @Test
    void connectionTestRejectsOtherDatabaseVersionsWithoutLeakingTheCredential() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = new ConnectionProbe("Oracle", "Oracle Database 21c", 21, 0, "Oracle JDBC", "23");
        OracleDiscoveryService service = service(repository, gateway);

        ApiException error = assertThrows(ApiException.class,
                () -> service.testConnection(OracleDiscoveryTestFixtures.CONNECTION_UUID));

        assertEquals("ORACLE_VERSION_UNSUPPORTED", error.code());
        assertTrue(!error.getMessage().contains(PASSWORD));
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void jndiConnectionTestDoesNotResolveApplicationCredentials() {
        ConnectionProfile profile = new ConnectionProfile(
                11L, 7L, OracleDiscoveryTestFixtures.CONNECTION_UUID,
                OracleDiscoveryTestFixtures.CONNECTION_UUID, "ORACLE", "JNDI",
                "java:comp/env/jdbc/OracleMain", null, null, null, null,
                "DISABLED", 0, new ObjectMapper().createObjectNode(),
                null, null, null, "ACTIVE", 1L, null, null, null);
        StubRepository repository = new StubRepository(profile, physical(7L), "TABLO");
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = oracle19c();

        ConnectionProbe result = service(repository, gateway).testConnection(OracleDiscoveryTestFixtures.CONNECTION_UUID);

        assertEquals(19, result.databaseMajorVersion());
        assertArrayEquals(new char[0], gateway.passwordReference);
    }

    @Test
    void discoveryRejectsPhysicalSchemaFromAnotherConnectionBeforeOpeningOracle() {
        StubRepository repository = repository(99L);
        CapturingGateway gateway = new CapturingGateway();
        OracleDiscoveryService service = service(repository, gateway);

        ApiException error = assertThrows(ApiException.class, () -> service.discover(
                OracleDiscoveryTestFixtures.CONNECTION_UUID, OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID, null, 100));

        assertEquals("VALIDATION_FAILED", error.code());
        assertEquals(0, gateway.discoveryCalls);
    }

    @Test
    void discoveryRequiresAnActiveConnection() {
        StubRepository repository = new StubRepository(
                OracleDiscoveryTestFixtures.profile(7L, "ENV", ENVIRONMENT_NAME, "AKTIF", "DISABLED"), physical(7L), "TABLO");
        CapturingGateway gateway = new CapturingGateway();

        ApiException error = assertThrows(ApiException.class, () -> service(repository, gateway).discover(
                OracleDiscoveryTestFixtures.CONNECTION_UUID, OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID, null, 100));

        assertEquals("CONNECTION_DISABLED", error.code());
        assertEquals(0, gateway.discoveryCalls);
    }

    @Test
    void discoveryNormalizesOracleIdentifiersAndUsesOnlyMetadataGateway() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.discovery = new DiscoveryResult("APP_OWNER", OffsetDateTime.now(ZoneOffset.UTC), false, List.of());

        DiscoveryResult result = service(repository, gateway).discover(
                OracleDiscoveryTestFixtures.CONNECTION_UUID, OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID, "sample_table", 50);

        assertEquals("APP_OWNER", result.owner());
        assertEquals("APP_OWNER", gateway.owner);
        assertEquals("SAMPLE_TABLE", gateway.tableName);
        assertEquals(50, gateway.limit);
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void discoveryPassesSelectedObjectTypeToGatewayBeforeResultLimit() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.discovery = new DiscoveryResult("APP_OWNER", OffsetDateTime.now(ZoneOffset.UTC), false, List.of());
        service(repository(7L), gateway).discover(
                OracleDiscoveryTestFixtures.CONNECTION_UUID, OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID,
                null, 20, List.of("VIEW"));
        assertEquals(Set.of("VIEW"), gateway.types);
        assertEquals(20, gateway.limit);
    }

    @Test
    void discoveryRejectsUnsupportedTypeBeforeOpeningGateway() {
        CapturingGateway gateway = new CapturingGateway();
        ApiException error = assertThrows(ApiException.class, () -> service(repository(7L), gateway).discover(
                OracleDiscoveryTestFixtures.CONNECTION_UUID, OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID,
                null, 20, List.of("UNKNOWN")));
        assertEquals("VALIDATION_FAILED", error.code());
        assertEquals(0, gateway.discoveryCalls);
    }

    @Test
    void governedSnapshotCaptureDerivesTheTableFromTheBoundCatalogObject() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        gateway.probe = oracle19c();
        gateway.capture = new SnapshotCapture(
                OffsetDateTime.now(ZoneOffset.UTC),
                new OracleSchemaSnapshotCodecV1.SnapshotDefinition(
                        "ORACLE_19C", 1, new ObjectMapper().createObjectNode(), List.of(), List.of()));

        GovernedSnapshotCapture result = service(repository, gateway).captureSchemaSnapshot(
                OracleDiscoveryTestFixtures.PROJECT_UUID, OracleDiscoveryTestFixtures.CONNECTION_UUID,
                OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID, UUID.randomUUID());

        assertEquals("APP_OWNER", gateway.owner);
        assertEquals("SAMPLE_TABLE", gateway.tableName);
        assertEquals(1, gateway.captureCalls);
        assertEquals(1, result.targetIdentityVersion());
        assertArrayEquals(new char[PASSWORD.length()], gateway.passwordReference);
    }

    @Test
    void snapshotCaptureAcceptsTableLikeObjectTypes() {
        for (String unsupported : List.of("VIEW", "MATERIALIZED_VIEW", "SYNONYM")) {
            StubRepository repository = repository(7L, unsupported);
            CapturingGateway gateway = new CapturingGateway();
            gateway.probe = oracle19c();
            gateway.capture = new SnapshotCapture(
                    OffsetDateTime.now(ZoneOffset.UTC),
                    new OracleSchemaSnapshotCodecV1.SnapshotDefinition(
                            "ORACLE_19C", 1, new ObjectMapper().createObjectNode(), List.of(), List.of()));

            GovernedSnapshotCapture result = service(repository, gateway).captureSchemaSnapshot(
                    OracleDiscoveryTestFixtures.PROJECT_UUID, OracleDiscoveryTestFixtures.CONNECTION_UUID,
                    OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID, UUID.randomUUID());

            assertEquals("ORACLE_19C", result.capture().definition().engineVersion());
            assertEquals(1, gateway.captureCalls);
        }
    }

    @Test
    void discoveryPreservesProviderNeutralObjectTypesFromGateway() {
        StubRepository repository = repository(7L);
        CapturingGateway gateway = new CapturingGateway();
        TableMetadata view = new TableMetadata("APP_OWNER", "V_SALES", "VIEW", List.of(), List.of());
        TableMetadata materialized = new TableMetadata("APP_OWNER", "MV_SALES", "MATERIALIZED_VIEW", List.of(), List.of());
        TableMetadata synonym = new TableMetadata("APP_OWNER", "S_SALES", "SYNONYM", List.of(), List.of());
        gateway.discovery = new DiscoveryResult("APP_OWNER", OffsetDateTime.now(ZoneOffset.UTC), false, List.of(view, materialized, synonym));

        DiscoveryResult result = service(repository, gateway).discover(
                OracleDiscoveryTestFixtures.CONNECTION_UUID, OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID, null, 50);

        assertEquals(List.of("VIEW", "MATERIALIZED_VIEW", "SYNONYM"),
                result.tables().stream().map(TableMetadata::type).toList());
    }

    private OracleDiscoveryService service(StubRepository repository, CapturingGateway gateway) {
        ObjectMapper objectMapper = new ObjectMapper();
        EnvironmentCredentialResolver resolver = new EnvironmentCredentialResolver(
                objectMapper,
                Map.of(ENVIRONMENT_NAME, "{\"username\":\"reader\",\"password\":\"" + PASSWORD + "\"}")::get,
                CIPHER);
        return new OracleDiscoveryService(repository, resolver, gateway, objectMapper);
    }

    private StubRepository repository(long physicalConnectionId) {
        return repository(physicalConnectionId, "TABLO");
    }

    private StubRepository repository(long physicalConnectionId, String dataObjectType) {
        return new StubRepository(
                OracleDiscoveryTestFixtures.profile(7L, "ENV", ENVIRONMENT_NAME, "AKTIF"),
                physical(physicalConnectionId), dataObjectType);
    }

    private static PhysicalSchemaProfile physical(long connectionId) {
        return new PhysicalSchemaProfile(OracleDiscoveryTestFixtures.PHYSICAL_SCHEMA_UUID, connectionId, "app_owner", "AKTIF");
    }

    @Test
    void postgresqlIdentifierFoldsUnquotedNamesAndPreservesQuotedNames() {
        OracleDiscoveryService service = service(new StubRepository(
                OracleDiscoveryTestFixtures.profile(7L, "ENV", ENVIRONMENT_NAME, "AKTIF"),
                physical(7L), "TABLO"), new CapturingGateway());

        assertEquals("musteri_tablo", service.postgresIdentifier("MUSTERI_TABLO", "ad"));
        assertEquals("MusteriTablo", service.postgresIdentifier("\"MusteriTablo\"", "ad"));
        assertThrows(ApiException.class, () -> service.postgresIdentifier("musteri;drop", "ad"));
    }

    private ConnectionProbe oracle19c() {
        return new ConnectionProbe("Oracle", "Oracle Database 19c", 19, 0, "Oracle JDBC", "23", 1, "a".repeat(64));
    }

    private static final class StubRepository extends OracleDiscoveryRepository {
        private final ConnectionProfile profile;
        private final PhysicalSchemaProfile physicalSchema;
        private final DataObjectCaptureProfile dataObject;

        private StubRepository(ConnectionProfile profile, PhysicalSchemaProfile physicalSchema, String dataObjectType) {
            super(null, null);
            this.profile = profile;
            this.physicalSchema = physicalSchema;
            this.dataObject = new DataObjectCaptureProfile(UUID.randomUUID(), "sample_table", dataObjectType, "AKTIF");
        }

        @Override
        Optional<ConnectionProfile> findConnectionProfile(UUID connectionUuid) {
            return Optional.of(profile);
        }

        @Override
        Optional<PhysicalSchemaProfile> findPhysicalSchema(UUID physicalSchemaUuid) {
            return Optional.of(physicalSchema);
        }

        @Override
        Optional<Long> findProjectId(UUID projectUuid) {
            return Optional.of(11L);
        }

        @Override
        Optional<DataObjectCaptureProfile> findDataObjectCaptureProfile(long projectId, UUID dataObjectUuid, UUID physicalSchemaUuid) {
            return Optional.of(dataObject);
        }
    }

    private static final class CapturingGateway implements SchemaDiscoveryPort {
        private String technology = "ORACLE";
        @Override public String technology() { return technology; }
        private ConnectionProbe probe;
        private DiscoveryResult discovery;
        private char[] passwordReference;
        private String username;
        private int discoveryCalls;
        private String owner;
        private String tableName;
        private int limit;
        private Set<String> types;
        private int captureCalls;
        private int schemaListCalls;
        private SnapshotCapture capture;

        private void remember(Credentials credentials) {
            passwordReference = credentials.password();
            username = credentials.username();
        }

        @Override
        public ConnectionProbe test(ConnectionProfile profile, Credentials credentials) {
            remember(credentials);
            return probe;
        }

        @Override
        public List<String> listSchemas(ConnectionProfile profile, Credentials credentials) {
            schemaListCalls++;
            remember(credentials);
            return List.of("APP_OWNER", "UPSTREAM_SCHEMA");
        }

        @Override
        public DiscoveryResult discover(ConnectionProfile profile, Credentials credentials, String owner, String tableName, int limit, Set<String> types) {
            discoveryCalls++;
            remember(credentials);
            this.owner = owner;
            this.tableName = tableName;
            this.limit = limit;
            this.types = types;
            return discovery;
        }

        @Override
        public SnapshotCapture captureSnapshot(ConnectionProfile profile, Credentials credentials, String owner, String tableName) {
            captureCalls++;
            remember(credentials);
            this.owner = owner;
            this.tableName = tableName;
            return capture;
        }
    }
}

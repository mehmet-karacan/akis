package tr.com.innova.akis.oracle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

/** Connection probing and metadata discovery against the global topology. */
@Service
public class OracleDiscoveryService {

    private static final Set<String> ALLOWED_DRIVERS = Set.of(
            "oracle.jdbc.OracleDriver", "org.postgresql.Driver",
            "com.mysql.cj.jdbc.Driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$#.-]{1,128}");
    private static final Pattern JNDI_NAME = Pattern.compile("java:comp/env/jdbc/[A-Za-z0-9_.-]{1,180}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private static final Pattern POSTGRES_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");

    private final OracleDiscoveryRepository repository;
    private final EnvironmentCredentialResolver credentialResolver;
    /** Discovery adapters by provider code; a connection whose provider has no adapter cannot be browsed. */
    private final Map<String, SchemaDiscoveryPort> gateways;
    private final ObjectMapper objectMapper;
    private final tr.com.innova.akis.discovery.JdbcSchemaSnapshotStore snapshotStore;
    private final tr.com.innova.akis.topology.TargetProvisioningPolicyService provisioningPolicies;

    @org.springframework.beans.factory.annotation.Autowired
    public OracleDiscoveryService(
            OracleDiscoveryRepository repository,
            EnvironmentCredentialResolver credentialResolver,
            List<SchemaDiscoveryPort> gateways,
            ObjectMapper objectMapper,
            tr.com.innova.akis.discovery.JdbcSchemaSnapshotStore snapshotStore,
            tr.com.innova.akis.topology.TargetProvisioningPolicyService provisioningPolicies) {
        this.repository = repository;
        this.credentialResolver = credentialResolver;
        Map<String, SchemaDiscoveryPort> byTechnology = new LinkedHashMap<>();
        for (SchemaDiscoveryPort gateway : gateways) byTechnology.put(gateway.technology(), gateway);
        this.gateways = Map.copyOf(byTechnology);
        this.objectMapper = objectMapper;
        this.snapshotStore = snapshotStore;
        this.provisioningPolicies = provisioningPolicies;
    }

    OracleDiscoveryService(
            OracleDiscoveryRepository repository,
            EnvironmentCredentialResolver credentialResolver,
            SchemaDiscoveryPort gateway,
            ObjectMapper objectMapper) {
        this(repository, credentialResolver, List.of(gateway), objectMapper, null, null);
    }

    private SchemaDiscoveryPort gateway(ConnectionProfile profile) {
        SchemaDiscoveryPort gateway = gateways.get(profile.databaseType());
        if (gateway == null) {
            throw validation("Bu sağlayıcı için şema keşfi desteklenmiyor: " + profile.databaseType());
        }
        return gateway;
    }

    public ConnectionProbe testConnection(UUID connectionUuid) {
        ConnectionProfile profile = validatedProfile(repository.findConnectionProfile(connectionUuid)
                .orElseThrow(() -> notFound("Bağlantı bulunamadı.")));
        try (Credentials credentials = credentials(profile)) {
            return probe(profile, credentials);
        }
    }

    public ConnectionProbe testDraftConnection(DraftConnection draft) {
        String mode = draft.mode() == null ? "JDBC" : draft.mode().trim().toUpperCase(Locale.ROOT);
        String databaseType = draft.databaseType() == null ? "" : draft.databaseType().trim().toUpperCase(Locale.ROOT);
        boolean oracle = "ORACLE".equals(databaseType);
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("connectTimeoutMs", draft.connectTimeoutMs() == null ? 10000 : draft.connectTimeoutMs());
        policy.put("readTimeoutMs", draft.readTimeoutMs() == null ? 60000 : draft.readTimeoutMs());
        policy.put("networkTimeoutMs", draft.readTimeoutMs() == null ? 60000 : draft.readTimeoutMs());
        policy.put("queryTimeoutSeconds", draft.queryTimeoutSeconds() == null ? 60 : draft.queryTimeoutSeconds());
        UUID none = new UUID(0L, 0L);
        ConnectionProfile profile = validatedProfile(new ConnectionProfile(
                0L, 0L, none, none, databaseType, mode, draft.jndiName(),
                "JDBC".equals(mode) ? driverFor(databaseType, draft.driverReference()) : null,
                draft.host(), oracle ? draft.serviceName() : draft.databaseName(), oracle ? draft.sid() : null,
                "DISABLED", draft.port() == null ? 0 : draft.port(), policy,
                null, null, null));
        try (Credentials credentials = "JNDI".equals(mode)
                ? new Credentials("", new char[0])
                : new Credentials(nullSafe(draft.username()), nullSafe(draft.password()).toCharArray())) {
            return probe(profile, credentials);
        }
    }

    private ConnectionProbe probe(ConnectionProfile profile, Credentials credentials) {
        ConnectionProbe probe = gateway(profile).test(profile, credentials);
        if ("ORACLE".equals(profile.databaseType())) {
            requireOracle19c(probe);
            requireTargetIdentity(probe);
        }
        return probe;
    }

    public DiscoveryResult discover(UUID connectionUuid, UUID physicalSchemaUuid, String tableName, int limit) {
        ConnectionProfile profile = discoverableProfile(connectionUuid);
        PhysicalSchemaProfile physicalSchema = physicalSchema(profile, physicalSchemaUuid);
        if (limit < 1 || limit > 200) {
            throw validation("Keşif tablo limiti 1-200 aralığında olmalıdır.");
        }
        String owner = identifier(profile, physicalSchema.schemaReference(), "Fiziksel şema referansı");
        String normalizedTableName = tableName == null || tableName.isBlank() ? null : identifier(profile, tableName, "Tablo adı");
        try (Credentials credentials = credentials(profile)) {
            return gateway(profile).discover(profile, credentials, owner, normalizedTableName, limit);
        }
    }

    /** Reads the authoritative Oracle table DDL through DBMS_METADATA.GET_DDL. */
    public DdlResult getTableDdl(UUID connectionUuid, UUID physicalSchemaUuid, String tableName) {
        ConnectionProfile profile = discoverableProfile(connectionUuid);
        if (!"ORACLE".equals(profile.databaseType())) {
            throw validation("DBMS_METADATA yalnız Oracle bağlantılarında kullanılabilir.");
        }
        PhysicalSchemaProfile physicalSchema = physicalSchema(profile, physicalSchemaUuid);
        String owner = identifier(profile, physicalSchema.schemaReference(), "Fiziksel şema referansı");
        String normalizedTableName = identifier(profile, tableName, "Tablo adı");
        try (Credentials credentials = credentials(profile);
                java.sql.Connection connection = DiscoveryConnections.open(profile, credentials);
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "select dbms_metadata.get_ddl('TABLE', ?, ?) from dual")) {
            statement.setString(1, normalizedTableName);
            statement.setString(2, owner);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw notFound("Oracle DDL sonucu bulunamadı.");
                }
                Clob ddl = result.getClob(1);
                if (ddl == null) {
                    throw notFound("Oracle DDL sonucu boş döndü.");
                }
                return new DdlResult(owner, normalizedTableName, readClob(ddl));
            }
        }
        catch (SQLException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ORACLE_DDL_READ_FAILED",
                    "Oracle DBMS_METADATA.GET_DDL çağrısı başarısız oldu: " + exception.getMessage());
        }
    }

    public record DdlResult(String owner, String table, String ddl) { }

    public tr.com.innova.akis.postgres.OracleDdlToPostgresConverter.Result convertTableDdl(
            UUID connectionUuid, UUID physicalSchemaUuid, String tableName,
            String targetSchema, String targetTable) {
        DdlResult source = getTableDdl(connectionUuid, physicalSchemaUuid, tableName);
        return tr.com.innova.akis.postgres.OracleDdlToPostgresConverter.convert(source.ddl(), targetSchema, targetTable);
    }

    /** DDL preview/execution for a PostgreSQL target table, from a pinned Oracle snapshot; governed by V048 policy. */
    public record ProvisionResult(String schema, String table, String ddl, java.util.List<String> skippedColumns,
            tr.com.innova.akis.topology.TargetProvisioningPolicyService.Policy policy, boolean executed) { }

    public ProvisionResult provisionTarget(UUID projectUuid, UUID connectionUuid, UUID physicalSchemaUuid,
            UUID sourceSnapshotUuid, String targetTable, boolean execute) {
        long projectId = repository.findProjectId(projectUuid).orElseThrow(() -> notFound("Proje bulunamadı."));
        ConnectionProfile profile = discoverableProfile(connectionUuid);
        if (!"POSTGRESQL".equals(profile.databaseType()))
            throw validation("Hedef sağlama yalnız PostgreSQL bağlantılarında kullanılabilir.");
        PhysicalSchemaProfile physicalSchema = physicalSchema(profile, physicalSchemaUuid);
        var policyView = provisioningPolicies.get(projectUuid, physicalSchemaUuid);
        if (policyView.policy() == tr.com.innova.akis.topology.TargetProvisioningPolicyService.Policy.DDL_DISABLED)
            throw new tr.com.innova.akis.metadata.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "TARGET_PROVISIONING_DISABLED", "Bu fiziksel şemada hedef sağlama kapalı.");
        if (execute && policyView.policy() != tr.com.innova.akis.topology.TargetProvisioningPolicyService.Policy.DDL_AUTO_CREATE)
            throw new tr.com.innova.akis.metadata.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "TARGET_PROVISIONING_NOT_AUTO", "Bu fiziksel şemada yalnız DDL metni üretilebilir; otomatik çalıştırma kapalı.");
        var snapshot = snapshotStore.find(projectId, sourceSnapshotUuid)
                .orElseThrow(() -> notFound("Kaynak şema görüntüsü bulunamadı."));
        if (!snapshot.engineVersion().startsWith("ORACLE"))
            throw validation("Hedef sağlama yalnız Oracle kaynak şema görüntüsünden yapılabilir.");
        var plan = tr.com.innova.akis.postgres.PostgresSchemaProvisioner.plan(snapshot, physicalSchema.schemaReference(), targetTable);
        boolean executed = false;
        if (execute) {
            try (Credentials credentials = credentials(profile);
                    java.sql.Connection connection = DiscoveryConnections.open(profile, credentials)) {
                connection.setReadOnly(false);
                connection.setAutoCommit(true);
                tr.com.innova.akis.postgres.PostgresSchemaProvisioner.execute(connection, plan);
                executed = true;
            }
            catch (java.sql.SQLException exception) {
                throw DiscoveryConnections.connectionFailed();
            }
        }
        return new ProvisionResult(plan.schema(), plan.table(), plan.ddl(), plan.skippedColumns(), policyView.policy(), executed);
    }

    public List<String> listSchemas(UUID connectionUuid) {
        ConnectionProfile profile = discoverableProfile(connectionUuid);
        try (Credentials credentials = credentials(profile)) {
            return gateway(profile).listSchemas(profile, credentials);
        }
    }

    /** Captures a governed snapshot; target identity evidence comes from a live probe at capture time. */
    GovernedSnapshotCapture captureSchemaSnapshot(
            UUID projectUuid, UUID connectionUuid, UUID physicalSchemaUuid, UUID dataObjectUuid) {
        long projectId = repository.findProjectId(projectUuid).orElseThrow(() -> notFound("Proje bulunamadı."));
        ConnectionProfile profile = discoverableProfile(connectionUuid);
        PhysicalSchemaProfile physicalSchema = physicalSchema(profile, physicalSchemaUuid);
        DataObjectCaptureProfile dataObject = repository.findDataObjectCaptureProfile(
                        projectId, dataObjectUuid, physicalSchemaUuid)
                .orElseThrow(() -> validation("Veri nesnesi fiziksel şema eşlemesiyle uyuşmuyor."));
        if (!"AKTIF".equals(dataObject.status()) || !"TABLO".equals(dataObject.objectType())) {
            throw validation("Şema görüntüsü yalnız aktif tablo veri nesnesi için alınabilir.");
        }
        String tableName = identifier(profile, dataObject.objectReference(), "Veri nesnesi referansı");
        ConnectionProbe probe;
        SnapshotCapture capture;
        try (Credentials credentials = credentials(profile)) {
            probe = probe(profile, credentials);
            capture = gateway(profile).captureSnapshot(
                    profile, credentials,
                    identifier(profile, physicalSchema.schemaReference(), "Fiziksel şema referansı"),
                    tableName);
        }
        return new GovernedSnapshotCapture(
                projectUuid, connectionUuid, connectionUuid,
                physicalSchemaUuid, dataObjectUuid, 1L,
                null, probe.targetIdentityVersion(), probe.targetFingerprint(), capture,
                profile.databaseType(), probe);
    }

    private PhysicalSchemaProfile physicalSchema(ConnectionProfile profile, UUID physicalSchemaUuid) {
        PhysicalSchemaProfile physicalSchema = repository.findPhysicalSchema(physicalSchemaUuid)
                .orElseThrow(() -> notFound("Fiziksel şema bulunamadı."));
        if (physicalSchema.connectionId() != profile.connectionId()) {
            throw validation("Fiziksel şema bu bağlantıya ait değil.");
        }
        if (!"AKTIF".equals(physicalSchema.status())) {
            throw validation("Fiziksel şema aktif olmalıdır.");
        }
        return physicalSchema;
    }

    private ConnectionProfile discoverableProfile(UUID connectionUuid) {
        ConnectionProfile profile = repository.findConnectionProfile(connectionUuid)
                .orElseThrow(() -> notFound("Bağlantı bulunamadı."));
        if (!gateways.containsKey(profile.databaseType())) {
            throw validation("Bu işlem yalnız Oracle ve PostgreSQL bağlantılarında kullanılabilir.");
        }
        if (!"ACTIVE".equals(profile.lifecycleStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONNECTION_DISABLED", "Bağlantı pasif durumda.");
        }
        return validatedProfile(profile);
    }

    private String readClob(Clob clob) throws SQLException {
        long length = clob.length();
        if (length > Integer.MAX_VALUE) {
            throw new SQLException("Oracle DDL çıktısı çok büyük.");
        }
        return clob.getSubString(1, (int) length);
    }

    private ConnectionProfile validatedProfile(ConnectionProfile profile) {
        if ("JNDI".equals(profile.mode())) {
            if (profile.jndiName() == null || !JNDI_NAME.matcher(profile.jndiName()).matches()) {
                throw validation("JNDI bağlantı profili geçersiz.");
            }
            return profile;
        }
        if (!"JDBC".equals(profile.mode())) {
            throw validation("Bağlantı modu geçersiz.");
        }
        if (profile.driverReference() == null || !ALLOWED_DRIVERS.contains(profile.driverReference())) {
            throw validation("JDBC sürücü referansı izin listesinde değil.");
        }
        if (profile.host() == null || !HOST.matcher(profile.host()).matches()) {
            throw validation("Sunucu adı geçersiz.");
        }
        if (profile.port() < 1 || profile.port() > 65535) {
            throw validation("Port geçersiz.");
        }
        if ("ORACLE".equals(profile.databaseType())) {
            boolean hasService = validDatabaseName(profile.serviceName());
            boolean hasSid = validDatabaseName(profile.sid());
            if (hasService == hasSid) {
                throw validation("Oracle bağlantısında servis adı veya SID alanlarından yalnız biri olmalıdır.");
            }
        }
        else if (!validDatabaseName(profile.serviceName())) {
            throw validation("Veritabanı adı geçersiz.");
        }
        return profile;
    }

    private Credentials credentials(ConnectionProfile profile) {
        return "JNDI".equals(profile.mode())
                ? new Credentials("", new char[0])
                : credentialResolver.resolve(profile);
    }

    static String driverFor(String databaseType, String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        return switch (databaseType) {
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> null;
        };
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private boolean validDatabaseName(String value) {
        return value != null && DATABASE_NAME.matcher(value).matches();
    }

    /** Oracle identifiers are upper-cased dictionary names; PostgreSQL names are case-sensitive and kept as given. */
    private String identifier(ConnectionProfile profile, String value, String field) {
        if ("POSTGRESQL".equals(profile.databaseType())) {
            String normalized = value == null ? "" : value.strip();
            if (!POSTGRES_IDENTIFIER.matcher(normalized).matches()) {
                throw validation(field + " geçersiz.");
            }
            // PostgreSQL unquoted identifiers are folded to lower case. Keep discovery,
            // provisioning and the physical-schema catalog on the same convention.
            return normalized.toLowerCase(Locale.ROOT);
        }
        return identifier(value, field);
    }

    private String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw validation(field + " geçerli bir Oracle tanımlayıcısı olmalıdır.");
        }
        return normalized;
    }

    private void requireOracle19c(ConnectionProbe probe) {
        if (probe.databaseProduct() == null
                || !probe.databaseProduct().toUpperCase(Locale.ROOT).contains("ORACLE")
                || probe.databaseMajorVersion() != 19) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ORACLE_VERSION_UNSUPPORTED",
                    "Bağlantı Oracle Database 19c ile uyumlu değil.");
        }
    }

    private void requireTargetIdentity(ConnectionProbe probe) {
        if (probe.targetIdentityVersion() != OracleDatabaseIdentityFingerprintV1.IDENTITY_VERSION
                || probe.targetFingerprint() == null
                || !probe.targetFingerprint().matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ORACLE_TARGET_IDENTITY_UNAVAILABLE",
                    "Oracle veritabanı hedef kimliği doğrulanamadı.");
        }
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

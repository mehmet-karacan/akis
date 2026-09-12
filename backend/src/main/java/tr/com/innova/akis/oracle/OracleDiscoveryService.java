package tr.com.innova.akis.oracle;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DataObjectCaptureProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.GovernedSnapshotCapture;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.PhysicalSchemaProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.SnapshotCapture;

@Service
public class OracleDiscoveryService {

    private static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$#.-]{1,128}");
    private static final Pattern JNDI_NAME = Pattern.compile(
            "java:comp/env/jdbc/[A-Za-z0-9_.-]{1,180}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private static final Set<String> TLS_MODES = Set.of(
            "DISABLED", "REQUIRED", "VERIFY_CA", "VERIFY_FULL");

    private final OracleDiscoveryRepository repository;
    private final EnvironmentCredentialResolver credentialResolver;
    private final OracleMetadataGateway gateway;

    public OracleDiscoveryService(
            OracleDiscoveryRepository repository,
            EnvironmentCredentialResolver credentialResolver,
            OracleMetadataGateway gateway) {
        this.repository = repository;
        this.credentialResolver = credentialResolver;
        this.gateway = gateway;
    }

    public ConnectionProbe testConnection(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid) {
        ConnectionProfile profile = profile(projectUuid, connectionUuid, connectionVersionUuid);
        try (Credentials credentials = credentials(profile)) {
            ConnectionProbe probe = gateway.test(profile, credentials);
            requireOracle19c(probe);
            requireTargetIdentity(probe);
            return probe;
        }
    }

    public ConnectionProbe testDraftConnection(
            String mode,
            String jndiName,
            String host,
            String serviceName,
            String sid,
            Integer port,
            JsonNode policy,
            String credentialReferencePath) {
        String normalizedMode = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        ConnectionProfile draft = new ConnectionProfile(
                0L, 0L, new UUID(0L, 0L), new UUID(0L, 0L), "ORACLE",
                normalizedMode, jndiName, "JDBC".equals(normalizedMode) ? ORACLE_DRIVER : null,
                host, serviceName, sid, "DISABLED", port == null ? 0 : port,
                policy, "JDBC".equals(normalizedMode) ? "ENV" : null,
                "JDBC".equals(normalizedMode) ? credentialReferencePath : null,
                "JDBC".equals(normalizedMode) ? "AKTIF" : null);
        ConnectionProfile profile = validatedProfile(draft);
        try (Credentials credentials = credentials(profile)) {
            ConnectionProbe probe = gateway.test(profile, credentials);
            requireOracle19c(probe);
            requireTargetIdentity(probe);
            return probe;
        }
    }

    public ConnectionProbe testDraftConnection(
            String mode, String jndiName, String host, String serviceName, String sid,
            Integer port, JsonNode policy, String username, char[] password) {
        String normalizedMode = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        ConnectionProfile profile = validatedProfile(new ConnectionProfile(
                0L, 0L, new UUID(0L, 0L), new UUID(0L, 0L), "ORACLE",
                normalizedMode, jndiName, "JDBC".equals(normalizedMode) ? ORACLE_DRIVER : null,
                host, serviceName, sid, "DISABLED", port == null ? 0 : port,
                policy, null, null, null));
        try (Credentials credentials = "JNDI".equals(normalizedMode)
                ? new Credentials("", new char[0])
                : new Credentials(username, password)) {
            ConnectionProbe probe = gateway.test(profile, credentials);
            requireOracle19c(probe);
            requireTargetIdentity(probe);
            return probe;
        }
    }

    public DiscoveryResult discover(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID physicalSchemaUuid,
            String tableName,
            int limit) {
        ConnectionProfile profile = profile(projectUuid, connectionUuid, connectionVersionUuid);
        requireActive(profile);
        PhysicalSchemaProfile physicalSchema = physicalSchema(profile, physicalSchemaUuid);
        if (limit < 1 || limit > 200) {
            throw validation("Keşif tablo limiti 1-200 aralığında olmalıdır.");
        }
        String owner = identifier(physicalSchema.schemaReference(), "Fiziksel şema referansı");
        String normalizedTableName = tableName == null || tableName.isBlank()
                ? null
                : identifier(tableName, "Tablo adı");
        try (Credentials credentials = credentials(profile)) {
            return gateway.discover(profile, credentials, owner, normalizedTableName, limit);
        }
    }

    public List<String> listSchemas(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid) {
        ConnectionProfile profile = profile(projectUuid, connectionUuid, connectionVersionUuid);
        if (!Set.of("TESTED", "ACTIVE").contains(profile.lifecycleStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CONNECTION_VERSION_NOT_TESTED",
                    "Oracle şemaları okunmadan önce bağlantı başarıyla test edilmelidir.");
        }
        try (Credentials credentials = credentials(profile)) {
            return gateway.listSchemas(profile, credentials);
        }
    }

    GovernedSnapshotCapture captureSchemaSnapshot(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID physicalSchemaUuid,
            UUID dataObjectUuid) {
        ConnectionProfile profile = profile(projectUuid, connectionUuid, connectionVersionUuid);
        requireActive(profile);
        PhysicalSchemaProfile physicalSchema = physicalSchema(profile, physicalSchemaUuid);
        DataObjectCaptureProfile dataObject = repository.findDataObjectCaptureProfile(
                        profile.projectId(), dataObjectUuid,
                        physicalSchemaUuid, connectionVersionUuid)
                .orElseThrow(() -> validation(
                        "Veri nesnesi aktif fiziksel şema bağıyla eşleşmiyor."));
        if (!"AKTIF".equals(dataObject.status()) || !"TABLO".equals(dataObject.objectType())) {
            throw validation("Oracle snapshot yalnız aktif tablo veri nesnesi için alınabilir.");
        }
        String tableName = identifier(dataObject.objectReference(), "Veri nesnesi referansı");
        if (profile.latestSuccessfulTestUuid() == null
                || profile.targetIdentityVersion() == null
                || profile.targetFingerprint() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CONNECTION_VERSION_EVIDENCE_MISSING",
                    "Aktif Oracle bağlantı sürümünün test kanıtı eksik.");
        }
        SnapshotCapture capture;
        try (Credentials credentials = credentials(profile)) {
            capture = gateway.captureSnapshot(
                    profile, credentials,
                    identifier(physicalSchema.schemaReference(), "Fiziksel şema referansı"),
                    tableName);
        }
        return new GovernedSnapshotCapture(
                projectUuid, connectionUuid, connectionVersionUuid,
                physicalSchemaUuid, dataObjectUuid, profile.lifecycleStateVersion(),
                profile.latestSuccessfulTestUuid(), profile.targetIdentityVersion(),
                profile.targetFingerprint(), capture);
    }

    private void requireActive(ConnectionProfile profile) {
        if (!"ACTIVE".equals(profile.lifecycleStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CONNECTION_VERSION_NOT_ACTIVE",
                    "Oracle metadata keşfinden önce bağlantı sürümü test edilip aktifleştirilmelidir.");
        }
    }

    private PhysicalSchemaProfile physicalSchema(
            ConnectionProfile profile, UUID physicalSchemaUuid) {
        PhysicalSchemaProfile physicalSchema = repository.findPhysicalSchema(
                        profile.projectId(), physicalSchemaUuid)
                .orElseThrow(() -> notFound("Fiziksel şema bulunamadı."));
        if (physicalSchema.connectionId() != profile.connectionId()) {
            throw validation("Fiziksel şema ve bağlantı sürümü aynı bağlantıya ait olmalıdır.");
        }
        if (!"AKTIF".equals(physicalSchema.status())) {
            throw validation("Fiziksel şema aktif olmalıdır.");
        }
        return physicalSchema;
    }

    private ConnectionProfile profile(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid) {
        ConnectionProfile profile = repository.findConnectionProfile(
                        projectUuid, connectionUuid, connectionVersionUuid)
                .orElseThrow(() -> notFound("Oracle bağlantı sürümü bulunamadı."));
        if (!"ORACLE".equals(profile.databaseType())) {
            throw validation("Bu işlem yalnız Oracle bağlantılarında kullanılabilir.");
        }
        return validatedProfile(profile);
    }

    private ConnectionProfile validatedProfile(ConnectionProfile profile) {
        if ("JNDI".equals(profile.mode())) {
            if (profile.jndiName() == null || !JNDI_NAME.matcher(profile.jndiName()).matches()
                    || profile.secretProvider() != null || profile.secretReferencePath() != null) {
                throw validation("Oracle JNDI bağlantı profili geçersiz.");
            }
            return profile;
        }
        if (!"JDBC".equals(profile.mode())) {
            throw validation("Oracle bağlantı modu geçersiz.");
        }
        if (!ORACLE_DRIVER.equals(profile.driverReference())) {
            throw validation("Oracle JDBC sürücü referansı izin listesinde değil.");
        }
        if (profile.host() == null || !HOST.matcher(profile.host()).matches()) {
            throw validation("Oracle sunucu adı geçersiz.");
        }
        if (profile.port() < 1 || profile.port() > 65535) {
            throw validation("Oracle portu geçersiz.");
        }
        if (!TLS_MODES.contains(profile.tlsMode())) {
            throw validation("Oracle TLS modu geçersiz.");
        }
        boolean hasService = validDatabaseName(profile.serviceName());
        boolean hasSid = validDatabaseName(profile.sid());
        if (hasService == hasSid) {
            throw validation("Oracle bağlantısında geçerli serviceName veya SID alanlarından biri olmalıdır.");
        }
        return profile;
    }

    private Credentials credentials(ConnectionProfile profile) {
        return "JNDI".equals(profile.mode())
                ? new Credentials("", new char[0])
                : credentialResolver.resolve(profile);
    }

    private boolean validDatabaseName(String value) {
        return value != null && DATABASE_NAME.matcher(value).matches();
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
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORACLE_VERSION_UNSUPPORTED",
                    "Bağlantı Oracle Database 19c ile uyumlu değil.");
        }
    }

    private void requireTargetIdentity(ConnectionProbe probe) {
        if (probe.targetIdentityVersion() != OracleDatabaseIdentityFingerprintV1.IDENTITY_VERSION
                || probe.targetFingerprint() == null
                || !probe.targetFingerprint().matches("[0-9a-f]{64}")) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "ORACLE_TARGET_IDENTITY_UNAVAILABLE",
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

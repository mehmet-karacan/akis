package tr.com.innova.akis.oracle;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.PhysicalSchemaProfile;

@Service
public class OracleDiscoveryService {

    private static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$#.-]{1,128}");
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
        try (Credentials credentials = credentialResolver.resolve(profile)) {
            ConnectionProbe probe = gateway.test(profile, credentials);
            requireOracle19c(probe);
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
        PhysicalSchemaProfile physicalSchema = repository.findPhysicalSchema(
                        profile.projectId(), physicalSchemaUuid)
                .orElseThrow(() -> notFound("Fiziksel şema bulunamadı."));
        if (physicalSchema.connectionId() != profile.connectionId()) {
            throw validation("Fiziksel şema ve bağlantı sürümü aynı bağlantıya ait olmalıdır.");
        }
        if (!"AKTIF".equals(physicalSchema.status())) {
            throw validation("Fiziksel şema aktif olmalıdır.");
        }
        if (limit < 1 || limit > 200) {
            throw validation("Keşif tablo limiti 1-200 aralığında olmalıdır.");
        }
        String owner = identifier(physicalSchema.schemaReference(), "Fiziksel şema referansı");
        String normalizedTableName = tableName == null || tableName.isBlank()
                ? null
                : identifier(tableName, "Tablo adı");
        try (Credentials credentials = credentialResolver.resolve(profile)) {
            return gateway.discover(profile, credentials, owner, normalizedTableName, limit);
        }
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

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

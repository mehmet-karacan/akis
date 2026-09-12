package tr.com.innova.akis.topology;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionVersionRow;
import tr.com.innova.akis.topology.TopologyModels.EnvironmentRow;
import tr.com.innova.akis.topology.TopologyModels.LogicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.PhysicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.ProjectRef;
import tr.com.innova.akis.topology.TopologyModels.SchemaBindingRow;

@Service
public class TopologyService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern HOST = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern JNDI_NAME = Pattern.compile(
            "java:comp/env/jdbc/[A-Za-z0-9_.-]{1,180}");
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$#.-]{1,128}");
    private static final Set<String> DATABASE_TYPES = Set.of("ORACLE", "POSTGRESQL", "MYSQL");
    private static final Set<String> TLS_MODES = Set.of(
            "DISABLED", "REQUIRED", "VERIFY_CA", "VERIFY_FULL");
    private static final Set<String> ORACLE_TLS_MODES = Set.of("DISABLED", "REQUIRED");
    private static final Set<String> CONNECTION_MODES = Set.of("JDBC", "JNDI");
    private static final Set<String> CONNECTION_POLICY_FIELDS = Set.of(
            "connectTimeoutMs", "readTimeoutMs", "networkTimeoutMs",
            "queryTimeoutSeconds", "purpose");
    private static final Set<String> RISKS = Set.of("DUSUK", "ORTA", "URETIM");
    private final TopologyRepository repository;
    private final ObjectMapper objectMapper;
    private final SecretValueSanitizer secretSanitizer;

    public TopologyService(
            TopologyRepository repository,
            ObjectMapper objectMapper,
            SecretValueSanitizer secretSanitizer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.secretSanitizer = secretSanitizer;
    }

    @Transactional
    ConnectionRow createConnection(
            UUID projectUuid,
            String code,
            String databaseType,
            String name,
            String description) {
        ProjectRef project = project(projectUuid);
        return repository.createConnection(
                project.id(), UUID.randomUUID(), normalizeCode(code),
                allowed(databaseType, DATABASE_TYPES, "veritabanı türü"),
                normalizeName(name), trimToNull(description));
    }

    @Transactional
    ConnectionWithInitialVersion createOracleConnectionWithInitialVersion(
            UUID projectUuid,
            String code,
            String name,
            String description,
            String mode,
            String host,
            String serviceName,
            String sid,
            Integer port,
            String jndiName,
            int policyVersion,
            JsonNode policy,
            String credentialProvider,
            String credentialReferencePath,
            String username) {
        ConnectionRow connection = createConnection(
                projectUuid, code, "ORACLE", name, description);
        ConnectionVersionRow version = createConnectionVersionWithCredential(
                projectUuid, connection.uuid(), mode, null, host,
                serviceName, sid, null, "DISABLED", port, jndiName,
                policyVersion, policy, credentialProvider, credentialReferencePath, username);
        return new ConnectionWithInitialVersion(connection, version);
    }

    record ConnectionWithInitialVersion(
            ConnectionRow connection,
            ConnectionVersionRow initialVersion) {
    }

    List<ConnectionRow> listConnections(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listConnections(project.id());
    }

    ConnectionRow connection(UUID projectUuid, UUID connectionUuid) {
        ProjectRef project = project(projectUuid);
        return connection(project, connectionUuid);
    }

    @Transactional
    ConnectionVersionRow createConnectionVersionWithCredential(
            UUID projectUuid,
            UUID connectionUuid,
            String mode,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String tlsMode,
            Integer port,
            String jndiName,
            int policyVersion,
            JsonNode policy,
            String credentialProvider,
            String credentialReferencePath) {
        return createConnectionVersionWithCredential(
                projectUuid, connectionUuid, mode, driverReference, host, serviceName, sid,
                databaseName, tlsMode, port, jndiName, policyVersion, policy,
                credentialProvider, credentialReferencePath, null);
    }

    private ConnectionVersionRow createConnectionVersionWithCredential(
            UUID projectUuid,
            UUID connectionUuid,
            String mode,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String tlsMode,
            Integer port,
            String jndiName,
            int policyVersion,
            JsonNode policy,
            String credentialProvider,
            String credentialReferencePath,
            String username) {
        ProjectRef project = project(projectUuid);
        ConnectionRow connection = connection(project, connectionUuid);
        if ("PASIF".equals(connection.status())) {
            throw validation("Pasif bağlantıya yeni sürüm eklenemez.");
        }
        String normalizedMode = allowed(mode == null ? "JDBC" : mode, CONNECTION_MODES, "bağlantı modu");
        String normalizedDriver = null;
        String normalizedHost = null;
        String normalizedServiceName = null;
        String normalizedSid = null;
        String normalizedDatabaseName = null;
        String normalizedJndiName = null;
        String normalizedTlsMode = "DISABLED";
        Integer normalizedPort = null;
        if ("JDBC".equals(normalizedMode)) {
            if (trimToNull(jndiName) != null) {
                throw validation("JDBC bağlantısında JNDI alanı gönderilemez.");
            }
            validateEndpoint(connection.databaseType(), serviceName, sid, databaseName);
            normalizedHost = required(host, "Sunucu adı", 253);
            if (!HOST.matcher(normalizedHost).matches() || normalizedHost.contains("..")) {
                throw validation("Sunucu adı geçersiz.");
            }
            if (port == null || port < 1 || port > 65535) {
                throw validation("Port 1-65535 aralığında olmalıdır.");
            }
            normalizedPort = port;
            normalizedDriver = pinnedDriver(connection.databaseType());
            String requestedDriver = trimToNull(driverReference);
            if (requestedDriver != null && !normalizedDriver.equals(requestedDriver)) {
                throw validation("Sürücü referansı platform izin listesiyle uyuşmuyor.");
            }
            normalizedServiceName = trimToNull(serviceName);
            normalizedSid = trimToNull(sid);
            normalizedDatabaseName = trimToNull(databaseName);
            String identifier = "ORACLE".equals(connection.databaseType())
                    ? (normalizedServiceName != null ? normalizedServiceName : normalizedSid)
                    : normalizedDatabaseName;
            if (identifier == null || !DATABASE_NAME.matcher(identifier).matches()) {
                throw validation("Veritabanı bağlantı tanımlayıcısı geçersiz.");
            }
            Set<String> allowedTlsModes = "ORACLE".equals(connection.databaseType())
                    ? ORACLE_TLS_MODES
                    : TLS_MODES;
            normalizedTlsMode = allowed(
                    tlsMode == null ? "DISABLED" : tlsMode, allowedTlsModes, "TLS modu");
        }
        else {
            if (!"ORACLE".equals(connection.databaseType())) {
                throw validation("JNDI modu şu anda yalnız Oracle bağlantıları için desteklenir.");
            }
            if (trimToNull(driverReference) != null || trimToNull(host) != null || port != null
                    || trimToNull(serviceName) != null || trimToNull(sid) != null
                    || trimToNull(databaseName) != null || (tlsMode != null && !"DISABLED".equals(tlsMode))) {
                throw validation("JNDI bağlantısında JDBC sunucu, port, sürücü veya TLS alanları gönderilemez.");
            }
            normalizedJndiName = required(jndiName, "JNDI adı", 200);
            if (!JNDI_NAME.matcher(normalizedJndiName).matches()) {
                throw validation("JNDI adı java:comp/env/jdbc/ altında güvenli bir yerel ad olmalıdır.");
            }
            if (credentialProvider != null || credentialReferencePath != null) {
                throw validation("JNDI bağlantısında secret referansı uygulama sunucusu tarafından yönetilir.");
            }
        }
        if (policyVersion < 1) {
            throw validation("Policy sürümü sıfırdan büyük olmalıdır.");
        }
        JsonNode safePolicy = policy == null ? objectMapper.createObjectNode() : policy;
        validateConnectionPolicy(safePolicy);

        String normalizedCredentialProvider = null;
        String normalizedCredentialPath = null;
        if ("JDBC".equals(normalizedMode)) {
            normalizedCredentialProvider = allowed(
                    credentialProvider, Set.of("ENV", "VAULT"), "credential provider");
            normalizedCredentialPath = required(
                    credentialReferencePath, "Kimlik bilgisi konumu", 1000);
            if ("ENV".equals(normalizedCredentialProvider)
                    && !normalizedCredentialPath.matches("[A-Z][A-Z0-9_]{1,199}")) {
                throw validation("ENV kimlik bilgisi konumu yalnız ortam değişkeni adı olmalıdır.");
            }
        }

        repository.lockConnection(connection.id());
        ConnectionVersionRow version = repository.createConnectionVersion(
                project.id(), connection.id(), UUID.randomUUID(),
                repository.nextConnectionVersion(connection.id()),
                normalizedMode, normalizedDriver, normalizedHost,
                normalizedServiceName, normalizedSid, normalizedDatabaseName,
                normalizedJndiName, normalizedTlsMode, normalizedPort, policyVersion, safePolicy);
        if (normalizedCredentialProvider != null) {
            repository.bindCredential(
                    project.id(), version.id(), normalizedCredentialProvider,
                    normalizedCredentialPath, "KIMLIK", trimToNull(username));
        }
        return version;
    }

    List<ConnectionVersionRow> listConnectionVersions(
            UUID projectUuid,
            UUID connectionUuid) {
        ProjectRef project = project(projectUuid);
        ConnectionRow connection = connection(project, connectionUuid);
        return repository.listConnectionVersions(project.id(), connection.id());
    }

    @Transactional
    PhysicalSchemaRow createPhysicalSchema(
            UUID projectUuid,
            UUID connectionUuid,
            String schema) {
        ProjectRef project = project(projectUuid);
        ConnectionRow connection = connection(project, connectionUuid);
        String normalizedSchema = normalizeCode(schema);
        return repository.createPhysicalSchema(
                project.id(), connection.id(), UUID.randomUUID(), normalizedSchema,
                normalizedSchema, normalizedSchema);
    }

    List<PhysicalSchemaRow> listPhysicalSchemas(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listPhysicalSchemas(project.id());
    }

    @Transactional
    LogicalSchemaRow createLogicalSchema(
            UUID projectUuid,
            String code,
            String name,
            String description) {
        ProjectRef project = project(projectUuid);
        return repository.createLogicalSchema(
                project.id(), UUID.randomUUID(), normalizeCode(code), normalizeName(name),
                trimToNull(description));
    }

    List<LogicalSchemaRow> listLogicalSchemas(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listLogicalSchemas(project.id());
    }

    @Transactional
    EnvironmentRow createEnvironment(
            UUID projectUuid,
            String code,
            String risk,
            int policyVersion,
            JsonNode policy,
            String name) {
        ProjectRef project = project(projectUuid);
        if (policyVersion < 1) {
            throw validation("Policy sürümü sıfırdan büyük olmalıdır.");
        }
        JsonNode safePolicy = policy == null ? objectMapper.createObjectNode() : policy;
        validatePolicy(safePolicy);
        return repository.createEnvironment(
                project.id(), UUID.randomUUID(), normalizeCode(code),
                allowed(risk == null ? "DUSUK" : risk, RISKS, "ortam riski"),
                policyVersion, safePolicy, normalizeName(name));
    }

    List<EnvironmentRow> listEnvironments(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listEnvironments(project.id());
    }

    @Transactional
    SchemaBindingRow createSchemaBinding(
            UUID projectUuid,
            UUID logicalSchemaUuid,
            UUID environmentUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid) {
        ProjectRef project = project(projectUuid);
        LogicalSchemaRow logical = repository.findLogicalSchema(project.id(), logicalSchemaUuid)
                .orElseThrow(() -> notFound("Mantıksal şema bulunamadı."));
        EnvironmentRow environment = repository.findEnvironment(project.id(), environmentUuid)
                .orElseThrow(() -> notFound("Ortam bulunamadı."));
        PhysicalSchemaRow physical = repository.findPhysicalSchema(project.id(), physicalSchemaUuid)
                .orElseThrow(() -> notFound("Fiziksel şema bulunamadı."));
        ConnectionVersionRow version = repository.findConnectionVersion(
                        project.id(), connectionVersionUuid)
                .orElseThrow(() -> notFound("Bağlantı sürümü bulunamadı."));
        if (physical.connectionId() != version.connectionId()) {
            throw validation("Fiziksel şema ile bağlantı sürümü aynı bağlantıya ait olmalıdır.");
        }
        if ("JNDI".equals(version.mode())) {
            throw validation("JNDI bağlantı sürümü çalıştırma bağında kullanılamaz.");
        }
        return repository.createSchemaBinding(
                project.id(), UUID.randomUUID(), logical.id(), environment.id(),
                physical.id(), version.id());
    }

    List<SchemaBindingRow> listSchemaBindings(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listSchemaBindings(project.id());
    }

    private ProjectRef project(UUID projectUuid) {
        return repository.findProject(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
    }

    private ConnectionRow connection(ProjectRef project, UUID connectionUuid) {
        return repository.findConnection(project.id(), connectionUuid)
                .orElseThrow(() -> notFound("Bağlantı bulunamadı."));
    }

    private void validateEndpoint(
            String databaseType,
            String serviceName,
            String sid,
            String databaseName) {
        boolean hasService = trimToNull(serviceName) != null;
        boolean hasSid = trimToNull(sid) != null;
        boolean hasDatabase = trimToNull(databaseName) != null;
        if (databaseType.equals("ORACLE")) {
            if (hasService == hasSid || hasDatabase) {
                throw validation("Oracle bağlantısında serviceName veya sid alanlarından yalnız biri zorunludur.");
            }
        }
        else if (!hasDatabase || hasService || hasSid) {
            throw validation("PostgreSQL/MySQL bağlantısında yalnız databaseName zorunludur.");
        }
    }

    private void validatePolicy(JsonNode policy) {
        if (!policy.isObject()) {
            throw validation("Policy JSON nesnesi olmalıdır.");
        }
        if (!secretSanitizer.sensitivePaths(policy).isEmpty()) {
            throw validation("Policy içinde secret veya credential değeri tutulamaz.");
        }
    }

    private void validateConnectionPolicy(JsonNode policy) {
        validatePolicy(policy);
        if (!policy.propertyNames().stream().allMatch(CONNECTION_POLICY_FIELDS::contains)) {
            throw validation("Bağlantı policy alanı izin listesinde değil.");
        }
        policyInteger(policy, "connectTimeoutMs", 1_000, 120_000);
        policyInteger(policy, "readTimeoutMs", 1_000, 300_000);
        policyInteger(policy, "networkTimeoutMs", 1_000, 300_000);
        policyInteger(policy, "queryTimeoutSeconds", 1, 300);
        JsonNode purpose = policy.get("purpose");
        if (purpose != null && (!purpose.isString()
                || !purpose.stringValue().matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw validation("Bağlantı purpose policy değeri geçersiz.");
        }
    }

    private void policyInteger(JsonNode policy, String field, int minimum, int maximum) {
        JsonNode value = policy.get(field);
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum)) {
            throw validation("Bağlantı timeout policy değeri geçersiz.");
        }
    }

    private String pinnedDriver(String databaseType) {
        return switch (databaseType) {
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            default -> throw validation("Desteklenmeyen veritabanı türü.");
        };
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw validation("Kod A-Z ile başlamalı ve yalnız A-Z, 0-9, _ içermelidir.");
        }
        return normalized;
    }

    private String normalizeName(String name) {
        return required(name, "Ad", 200);
    }

    private String required(String value, String field, int maximumLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maximumLength) {
            throw validation(field + " 1-" + maximumLength + " karakter olmalıdır.");
        }
        return normalized;
    }

    private String allowed(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw validation("Geçersiz " + field + ".");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

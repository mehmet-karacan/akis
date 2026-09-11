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
import tr.com.innova.akis.topology.TopologyModels.SecretReferenceRow;

@Service
public class TopologyService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Set<String> DATABASE_TYPES = Set.of("ORACLE", "POSTGRESQL", "MYSQL");
    private static final Set<String> SECRET_PROVIDERS = Set.of("ENV", "VAULT", "KUBERNETES");
    private static final Set<String> TLS_MODES = Set.of(
            "DISABLED", "REQUIRED", "VERIFY_CA", "VERIFY_FULL");
    private static final Set<String> SECRET_ROLES = Set.of(
            "KIMLIK", "WALLET", "CLIENT_SERTIFIKA");
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
    SecretReferenceRow createSecretReference(
            UUID projectUuid,
            String code,
            String referencePath,
            String versionReference,
            String provider,
            String name) {
        ProjectRef project = project(projectUuid);
        String normalizedProvider = allowed(provider, SECRET_PROVIDERS, "secret provider");
        String path = required(referencePath, "Secret referans yolu", 500);
        if (normalizedProvider.equals("ENV") && !path.matches("[A-Z][A-Z0-9_]{1,199}")) {
            throw validation("ENV secret referansı yalnız ortam değişkeni adı olmalıdır.");
        }
        return repository.createSecretReference(
                project.id(), UUID.randomUUID(), normalizeCode(code), path,
                trimToNull(versionReference), normalizedProvider, normalizeName(name));
    }

    List<SecretReferenceRow> listSecretReferences(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listSecretReferences(project.id());
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

    List<ConnectionRow> listConnections(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listConnections(project.id());
    }

    ConnectionRow connection(UUID projectUuid, UUID connectionUuid) {
        ProjectRef project = project(projectUuid);
        return connection(project, connectionUuid);
    }

    @Transactional
    ConnectionVersionRow createConnectionVersion(
            UUID projectUuid,
            UUID connectionUuid,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String tlsMode,
            int port,
            int policyVersion,
            JsonNode policy,
            UUID secretReferenceUuid,
            String secretRole) {
        ProjectRef project = project(projectUuid);
        ConnectionRow connection = connection(project, connectionUuid);
        if ("PASIF".equals(connection.status())) {
            throw validation("Pasif bağlantıya yeni sürüm eklenemez.");
        }
        validateEndpoint(connection.databaseType(), serviceName, sid, databaseName);
        if (port < 1 || port > 65535) {
            throw validation("Port 1-65535 aralığında olmalıdır.");
        }
        if (policyVersion < 1) {
            throw validation("Policy sürümü sıfırdan büyük olmalıdır.");
        }
        JsonNode safePolicy = policy == null ? objectMapper.createObjectNode() : policy;
        validatePolicy(safePolicy);

        SecretReferenceRow secret = null;
        String normalizedRole = null;
        if (secretReferenceUuid != null) {
            secret = repository.findSecretReference(project.id(), secretReferenceUuid)
                    .orElseThrow(() -> notFound("Secret referansı bulunamadı."));
            normalizedRole = allowed(
                    secretRole == null ? "KIMLIK" : secretRole,
                    SECRET_ROLES,
                    "secret rolü");
        }
        else if (secretRole != null) {
            throw validation("Secret rolü için secretReferenceUuid zorunludur.");
        }

        repository.lockConnection(connection.id());
        ConnectionVersionRow version = repository.createConnectionVersion(
                project.id(), connection.id(), UUID.randomUUID(),
                repository.nextConnectionVersion(connection.id()),
                required(driverReference, "Sürücü referansı", 300),
                required(host, "Sunucu adı", 500),
                trimToNull(serviceName), trimToNull(sid), trimToNull(databaseName),
                allowed(tlsMode == null ? "DISABLED" : tlsMode, TLS_MODES, "TLS modu"),
                port, policyVersion, safePolicy);
        if (secret != null) {
            repository.bindSecret(
                    project.id(), version.id(), secret.id(), normalizedRole);
        }
        repository.activateDraftConnection(connection.id());
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
            String code,
            String schemaReference,
            String name) {
        ProjectRef project = project(projectUuid);
        ConnectionRow connection = connection(project, connectionUuid);
        return repository.createPhysicalSchema(
                project.id(), connection.id(), UUID.randomUUID(), normalizeCode(code),
                required(schemaReference, "Fiziksel şema referansı", 300),
                normalizeName(name));
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

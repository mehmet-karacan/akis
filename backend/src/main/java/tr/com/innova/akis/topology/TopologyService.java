package tr.com.innova.akis.topology;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryService;
import tr.com.innova.akis.security.ConnectionCredentialCipher;
import tr.com.innova.akis.topology.TopologyModels.ConnectionCatalogRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionDependencyRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionTestRow;
import tr.com.innova.akis.topology.TopologyModels.EnvironmentRow;
import tr.com.innova.akis.topology.TopologyModels.LogicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.PhysicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.SchemaBindingRow;
import tr.com.innova.akis.topology.TopologyRepository.ConnectionTestWrite;
import tr.com.innova.akis.topology.TopologyRepository.ConnectionWrite;
import tr.com.innova.akis.topology.TopologyRepository.PhysicalSchemaWrite;

@Service
public class TopologyService {

    static final Set<String> DATABASE_TYPES = Set.of("ORACLE", "POSTGRESQL", "MYSQL", "SQLSERVER");
    private static final Set<String> MODES = Set.of("JDBC", "JNDI");
    private static final Set<String> STATUSES = Set.of("ETKIN", "PASIF");
    private static final Set<String> RISKS = Set.of("DUSUK", "ORTA", "YUKSEK", "URETIM");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern PREFIX = Pattern.compile("[A-Z][A-Z0-9_$]{0,7}");
    private static final Pattern JNDI_NAME = Pattern.compile("java:comp/env/jdbc/[A-Za-z0-9_.-]{1,180}");
    private static final Pattern HOST = Pattern.compile("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$#.-]{1,128}");

    private final TopologyRepository repository;
    private final ObjectMapper objectMapper;
    private final ConnectionCredentialCipher cipher;
    private final OracleDiscoveryService discovery;

    TopologyService(TopologyRepository repository, ObjectMapper objectMapper,
            ConnectionCredentialCipher cipher, OracleDiscoveryService discovery) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.discovery = discovery;
    }

    // ---------------------------------------------------------------- connections

    public record ConnectionInput(
            String code, String name, String description, String databaseType, String mode,
            String driverReference, String host, Integer port, String serviceName, String sid,
            String databaseName, String jdbcUrlExtra, String jndiName, String username, String password,
            Integer fetchSize, Integer batchSize, Integer connectTimeoutMs, Integer readTimeoutMs,
            Integer queryTimeoutSeconds, String onConnectSql, String onDisconnectSql, String status) {
    }

    List<ConnectionRow> listConnections() {
        return repository.listConnections();
    }

    List<ConnectionCatalogRow> listConnectionCatalog() {
        return repository.listConnectionCatalog();
    }

    public ConnectionRow connection(UUID uuid) {
        return repository.findConnection(uuid).orElseThrow(() -> notFound("Bağlantı bulunamadı."));
    }

    List<ConnectionDependencyRow> listConnectionDependencies(UUID uuid) {
        return repository.listConnectionDependencies(connection(uuid).id());
    }

    @Transactional
    ConnectionRow createConnection(ConnectionInput input) {
        ConnectionWrite write = normalizeConnection(input, null);
        if (repository.findConnectionByCode(write.code()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONNECTION_CODE_EXISTS", "Bu kodla bir bağlantı zaten var.");
        }
        String encrypted = "JDBC".equals(write.mode()) ? encryptCredential(write.username(), required(input.password(), "Şifre", 1000)) : null;
        return repository.createConnection(write, encrypted, null);
    }

    @Transactional
    ConnectionRow updateConnection(UUID uuid, ConnectionInput input) {
        ConnectionRow current = connection(uuid);
        ConnectionWrite write = normalizeConnection(input, current);
        repository.findConnectionByCode(write.code())
                .filter(other -> other.id() != current.id())
                .ifPresent(other -> { throw new ApiException(HttpStatus.CONFLICT, "CONNECTION_CODE_EXISTS", "Bu kodla bir bağlantı zaten var."); });
        String encrypted = trimToNull(input.password()) == null ? null : encryptCredential(write.username(), input.password());
        if ("JDBC".equals(write.mode()) && encrypted == null && !current.hasPassword()) {
            throw validation("Bu bağlantı için kayıtlı şifre yok; şifre girilmelidir.");
        }
        // Username lives inside the encrypted envelope too; keep it in sync when only the username changed.
        if ("JDBC".equals(write.mode()) && encrypted == null && current.hasPassword() && !java.util.Objects.equals(write.username(), current.username())) {
            encrypted = repository.findEncryptedPassword(current.id()).map(stored -> encryptCredential(write.username(), storedPassword(stored))).orElse(null);
        }
        if (!repository.updateConnection(uuid, write, encrypted, null)) throw notFound("Bağlantı bulunamadı.");
        return connection(uuid);
    }

    @Transactional
    void deleteConnection(UUID uuid) {
        ConnectionRow connection = connection(uuid);
        if (!repository.listConnectionDependencies(connection.id()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONNECTION_IN_USE",
                    "Bağlantı mantıksal şema eşlemelerinde kullanılıyor. Eşlemeleri kaldırmadan silinemez.");
        }
        if (!repository.listPhysicalSchemas().stream().noneMatch(p -> p.connectionId() == connection.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONNECTION_HAS_PHYSICAL_SCHEMAS",
                    "Bağlantıya bağlı fiziksel şemalar var. Önce onları silin.");
        }
        repository.deleteConnection(connection.id());
    }

    /** Stored secret is the V032 envelope {"username","password"}: discovery and the runtime both read it that way. */
    private String encryptCredential(String username, String password) {
        var envelope = objectMapper.createObjectNode();
        envelope.put("username", username == null ? "" : username);
        envelope.put("password", password);
        return cipher.encrypt(envelope.toString());
    }

    /** Accepts both the envelope and a legacy plain-password secret. */
    private String storedPassword(String encrypted) {
        String raw = cipher.decrypt(encrypted);
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node != null && node.isObject() && node.hasNonNull("password")) return node.path("password").asText();
        }
        catch (RuntimeException ignored) { /* legacy plain password */ }
        return raw;
    }

    @Transactional
    ConnectionTestRow testConnection(UUID uuid) {
        ConnectionRow connection = connection(uuid);
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            ConnectionProbe probe = discovery.testConnection(uuid);
            OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            return repository.recordTest(connection.id(), new ConnectionTestWrite(
                    true, null, probe.databaseProduct(), probe.databaseVersion(), probe.driverName(),
                    probe.driverVersion(), probe.databaseMajorVersion(), probe.databaseMinorVersion(),
                    probe.targetFingerprint() == null ? null : probe.targetIdentityVersion(), probe.targetFingerprint(),
                    startedAt, completedAt, java.time.Duration.between(startedAt, completedAt).toMillis()), null);
        }
        catch (ApiException exception) {
            recordFailure(connection.id(), exception.code(), startedAt);
            throw exception;
        }
        catch (RuntimeException exception) {
            recordFailure(connection.id(), "CONNECTION_TEST_FAILED", startedAt);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CONNECTION_TEST_FAILED", "Bağlantı testi tamamlanamadı.");
        }
    }

    private void recordFailure(long connectionId, String code, OffsetDateTime startedAt) {
        OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        repository.recordTest(connectionId, new ConnectionTestWrite(
                false, code, null, null, null, null, null, null, null, null,
                startedAt, completedAt, java.time.Duration.between(startedAt, completedAt).toMillis()), null);
    }

    List<ConnectionTestRow> listTests(UUID uuid, int limit) {
        return repository.listTests(connection(uuid).id(), Math.max(1, Math.min(limit, 200)));
    }

    private ConnectionWrite normalizeConnection(ConnectionInput in, ConnectionRow current) {
        String databaseType = allowed(in.databaseType(), DATABASE_TYPES, "sağlayıcı türü");
        String mode = allowed(in.mode() == null ? "JDBC" : in.mode(), MODES, "bağlantı modu");
        String status = allowed(in.status() == null ? (current == null ? "ETKIN" : current.status()) : in.status(), STATUSES, "durum");
        String code = normalizeCode(in.code());
        String name = normalizeName(in.name());
        int fetchSize = range(in.fetchSize(), 30, 1, 10000, "getirme boyutu");
        int batchSize = range(in.batchSize(), 30, 1, 10000, "toplu güncelleme boyutu");
        int connectTimeout = range(in.connectTimeoutMs(), 10000, 1000, 120000, "bağlantı zaman aşımı");
        int readTimeout = range(in.readTimeoutMs(), 60000, 1000, 300000, "okuma zaman aşımı");
        int queryTimeout = range(in.queryTimeoutSeconds(), 60, 1, 3600, "sorgu zaman aşımı");
        if ("JNDI".equals(mode)) {
            String jndi = required(in.jndiName(), "JNDI adı", 400);
            if (!JNDI_NAME.matcher(jndi).matches()) throw validation("JNDI adı java:comp/env/jdbc/ altında güvenli bir yerel ad olmalıdır.");
            return new ConnectionWrite(code, name, trimToNull(in.description()), databaseType, mode,
                    null, null, null, null, null, null, null, jndi, null,
                    fetchSize, batchSize, connectTimeout, readTimeout, queryTimeout,
                    trimToNull(in.onConnectSql()), trimToNull(in.onDisconnectSql()), status);
        }
        String host = required(in.host(), "Sunucu adı", 500);
        if (!HOST.matcher(host).matches() || host.contains("..")) throw validation("Sunucu adı geçersiz.");
        if (in.port() == null || in.port() < 1 || in.port() > 65535) throw validation("Port 1-65535 aralığında olmalıdır.");
        String serviceName = identifierOrNull(in.serviceName(), "Servis adı");
        String sid = identifierOrNull(in.sid(), "SID");
        String databaseName = identifierOrNull(in.databaseName(), "Veritabanı adı");
        if ("ORACLE".equals(databaseType)) {
            databaseName = null;
            if ((serviceName == null) == (sid == null)) throw validation("Oracle bağlantısında servis adı veya SID alanlarından yalnız biri olmalıdır.");
        }
        else {
            serviceName = null; sid = null;
            if (databaseName == null) throw validation("Veritabanı adı gereklidir.");
        }
        String username = required(in.username(), "Kullanıcı adı", 400);
        return new ConnectionWrite(code, name, trimToNull(in.description()), databaseType, mode,
                driverFor(databaseType, trimToNull(in.driverReference())), host, in.port(), serviceName, sid,
                databaseName, trimToNull(in.jdbcUrlExtra()), null, username,
                fetchSize, batchSize, connectTimeout, readTimeout, queryTimeout,
                trimToNull(in.onConnectSql()), trimToNull(in.onDisconnectSql()), status);
    }

    static String driverFor(String databaseType, String explicit) {
        if (explicit != null) return explicit;
        return switch (databaseType) {
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> throw new IllegalArgumentException(databaseType);
        };
    }

    // ---------------------------------------------------------------- physical schemas

    public record PhysicalSchemaInput(
            UUID connectionUuid, String code, String name, String description, String catalogName,
            String schemaName, String workCatalogName, String workSchemaName, Boolean defaultSchema,
            String loadingPrefix, String integrationPrefix, String errorPrefix, String tempPrefix,
            String objectPattern, String remoteObjectPattern, String sequencePattern, String status) {
    }

    List<PhysicalSchemaRow> listPhysicalSchemas() {
        return repository.listPhysicalSchemas();
    }

    PhysicalSchemaRow physicalSchema(UUID uuid) {
        return repository.findPhysicalSchema(uuid).orElseThrow(() -> notFound("Fiziksel şema bulunamadı."));
    }

    @Transactional
    PhysicalSchemaRow createPhysicalSchema(PhysicalSchemaInput input) {
        ConnectionRow connection = connection(input.connectionUuid());
        PhysicalSchemaWrite write = normalizePhysical(input, null, connection.databaseType());
        PhysicalSchemaRow created = repository.createPhysicalSchema(connection.id(), connection.databaseType(), write, null);
        if (write.defaultSchema()) repository.clearDefaultPhysicalSchema(connection.id(), created.uuid());
        return created;
    }

    @Transactional
    PhysicalSchemaRow updatePhysicalSchema(UUID uuid, PhysicalSchemaInput input) {
        PhysicalSchemaRow current = physicalSchema(uuid);
        PhysicalSchemaWrite write = normalizePhysical(input, current, connection(current.connectionUuid()).databaseType());
        if (write.defaultSchema()) repository.clearDefaultPhysicalSchema(current.connectionId(), uuid);
        if (!repository.updatePhysicalSchema(uuid, write, null)) throw notFound("Fiziksel şema bulunamadı.");
        return physicalSchema(uuid);
    }

    @Transactional
    void deletePhysicalSchema(UUID uuid) {
        physicalSchema(uuid);
        if (repository.physicalSchemaInUse(uuid)) {
            throw new ApiException(HttpStatus.CONFLICT, "PHYSICAL_SCHEMA_IN_USE",
                    "Fiziksel şema bir eşlemede, çalışma alanı politikasında veya yayında kullanılıyor. Kullanımları kaldırmadan silinemez.");
        }
        repository.deletePhysicalSchema(uuid);
    }

    /**
     * Oracle schema names are dictionary identifiers and upper-cased; PostgreSQL namespaces are case-sensitive and
     * kept exactly as typed (unquoted names are already lower-case in pg_namespace).
     */
    private PhysicalSchemaWrite normalizePhysical(PhysicalSchemaInput in, PhysicalSchemaRow current, String databaseType) {
        boolean caseSensitive = "POSTGRESQL".equals(databaseType);
        String schemaName = required(in.schemaName(), "Şema adı", 128);
        schemaName = caseSensitive ? schemaName : schemaName.toUpperCase(Locale.ROOT);
        String code = normalizeCode(in.code() == null || in.code().isBlank() ? schemaName : in.code());
        String name = normalizeName(in.name() == null || in.name().isBlank() ? schemaName : in.name());
        String work = trimToNull(in.workSchemaName()) == null ? schemaName
                : caseSensitive ? in.workSchemaName().trim() : in.workSchemaName().trim().toUpperCase(Locale.ROOT);
        String loading = prefix(in.loadingPrefix(), current == null ? "C$_" : current.loadingPrefix(), "Yükleme prefixi");
        String integration = prefix(in.integrationPrefix(), current == null ? "I$_" : current.integrationPrefix(), "Entegrasyon prefixi");
        String error = prefix(in.errorPrefix(), current == null ? "E$_" : current.errorPrefix(), "Hata prefixi");
        String temp = prefix(in.tempPrefix(), current == null ? "T$_" : current.tempPrefix(), "Geçici prefix");
        if (Set.of(loading, integration, error, temp).size() != 4) throw validation("Prefixler birbirinden farklı olmalıdır.");
        return new PhysicalSchemaWrite(code, name, trimToNull(in.description()),
                trimToNull(in.catalogName()), schemaName, trimToNull(in.workCatalogName()), work,
                Boolean.TRUE.equals(in.defaultSchema()),
                loading, integration, error, temp,
                orDefault(in.objectPattern(), current == null ? "%SCHEMA.%OBJECT" : current.objectPattern()),
                orDefault(in.remoteObjectPattern(), current == null ? "%SCHEMA.%OBJECT@%DSERVER" : current.remoteObjectPattern()),
                orDefault(in.sequencePattern(), current == null ? "%SCHEMA.%OBJECT.nextval" : current.sequencePattern()),
                allowed(in.status() == null ? (current == null ? "ETKIN" : current.status()) : in.status(), STATUSES, "durum"));
    }

    private String prefix(String value, String fallback, String field) {
        String candidate = trimToNull(value) == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (!PREFIX.matcher(candidate).matches()) throw validation(field + " 1-8 karakter; A-Z ile başlayıp A-Z, 0-9, _ veya $ içermelidir.");
        return candidate;
    }

    // ---------------------------------------------------------------- logical schemas

    List<LogicalSchemaRow> listLogicalSchemas() {
        return repository.listLogicalSchemas();
    }

    LogicalSchemaRow logicalSchema(UUID uuid) {
        return repository.findLogicalSchema(uuid).orElseThrow(() -> notFound("Mantıksal şema bulunamadı."));
    }

    @Transactional
    LogicalSchemaRow createLogicalSchema(String code, String name, String description, String databaseType,
            UUID environmentUuid, UUID physicalSchemaUuid) {
        boolean mappingRequested = environmentUuid != null || physicalSchemaUuid != null;
        if (mappingRequested && (environmentUuid == null || physicalSchemaUuid == null)) {
            throw validation("Ortam ve fiziksel şema birlikte seçilmelidir.");
        }
        String type = databaseType == null && physicalSchemaUuid != null
                ? physicalSchema(physicalSchemaUuid).databaseType()
                : allowed(databaseType, DATABASE_TYPES, "sağlayıcı türü");
        LogicalSchemaRow logical = repository.createLogicalSchema(
                normalizeCode(code), normalizeName(name), trimToNull(description), type, null);
        if (mappingRequested) createSchemaBinding(logical.uuid(), environmentUuid, physicalSchemaUuid);
        return logical;
    }

    @Transactional
    LogicalSchemaRow updateLogicalSchema(UUID uuid, String name, String description, String status) {
        LogicalSchemaRow current = logicalSchema(uuid);
        repository.updateLogicalSchema(uuid, normalizeName(name), trimToNull(description),
                allowed(status == null ? current.status() : status, STATUSES, "durum"), null);
        return logicalSchema(uuid);
    }

    @Transactional
    void deleteLogicalSchema(UUID uuid) {
        logicalSchema(uuid);
        if (repository.logicalSchemaInUse(uuid)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTEXT_IN_USE", "Mantıksal şema eşleme veya modellerde kullanılıyor. Kullanımları kaldırmadan silinemez.");
        }
        repository.deleteLogicalSchema(uuid);
    }

    // ---------------------------------------------------------------- environments

    List<EnvironmentRow> listEnvironments() {
        return repository.listEnvironments();
    }

    EnvironmentRow environment(UUID uuid) {
        return repository.findEnvironment(uuid).orElseThrow(() -> notFound("Ortam bulunamadı."));
    }

    @Transactional
    EnvironmentRow createEnvironment(String code, String name, String description, String risk, boolean defaultEnvironment, JsonNode policy) {
        JsonNode safePolicy = policy == null ? objectMapper.createObjectNode() : policy;
        if (!safePolicy.isObject()) throw validation("Ortam politikası JSON nesnesi olmalıdır.");
        if (defaultEnvironment) repository.clearDefaultEnvironment(null);
        return repository.createEnvironment(normalizeCode(code), normalizeName(name), trimToNull(description),
                allowed(risk == null ? "DUSUK" : risk, RISKS, "ortam riski"), defaultEnvironment, 1, safePolicy, null);
    }

    @Transactional
    EnvironmentRow updateEnvironment(UUID uuid, String name, String description, String risk, Boolean defaultEnvironment, String status) {
        EnvironmentRow current = environment(uuid);
        boolean makeDefault = defaultEnvironment == null ? current.defaultEnvironment() : defaultEnvironment;
        if (makeDefault) repository.clearDefaultEnvironment(uuid);
        repository.updateEnvironment(uuid, normalizeName(name), trimToNull(description),
                allowed(risk == null ? current.risk() : risk, RISKS, "ortam riski"), makeDefault,
                allowed(status == null ? current.status() : status, STATUSES, "durum"), null);
        return environment(uuid);
    }

    @Transactional
    void deleteEnvironment(UUID uuid) {
        environment(uuid);
        if (repository.environmentInUse(uuid)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTEXT_IN_USE", "Ortam eşleme, yayın veya doğrulamalarda kullanılıyor. Kullanımları kaldırmadan silinemez.");
        }
        repository.deleteEnvironment(uuid);
    }

    // ---------------------------------------------------------------- schema bindings

    List<SchemaBindingRow> listSchemaBindings() {
        return repository.listSchemaBindings();
    }

    @Transactional
    SchemaBindingRow createSchemaBinding(UUID logicalSchemaUuid, UUID environmentUuid, UUID physicalSchemaUuid) {
        LogicalSchemaRow logical = logicalSchema(logicalSchemaUuid);
        EnvironmentRow environment = environment(environmentUuid);
        PhysicalSchemaRow physical = physicalSchema(physicalSchemaUuid);
        requireSameTechnology(logical, physical);
        return repository.createSchemaBinding(logical.id(), environment.id(), physical.id(), physical.databaseType(), null);
    }

    @Transactional
    SchemaBindingRow updateSchemaBinding(UUID bindingUuid, UUID logicalSchemaUuid, UUID environmentUuid, UUID physicalSchemaUuid) {
        repository.findSchemaBinding(bindingUuid).orElseThrow(() -> notFound("Şema eşlemesi bulunamadı."));
        LogicalSchemaRow logical = logicalSchema(logicalSchemaUuid);
        EnvironmentRow environment = environment(environmentUuid);
        PhysicalSchemaRow physical = physicalSchema(physicalSchemaUuid);
        requireSameTechnology(logical, physical);
        repository.updateSchemaBinding(bindingUuid, logical.id(), environment.id(), physical.id(), physical.databaseType(), null);
        return repository.findSchemaBinding(bindingUuid).orElseThrow();
    }

    @Transactional
    void deleteSchemaBinding(UUID bindingUuid) {
        if (!repository.deleteSchemaBinding(bindingUuid)) throw notFound("Şema eşlemesi bulunamadı.");
    }

    private void requireSameTechnology(LogicalSchemaRow logical, PhysicalSchemaRow physical) {
        if (!logical.databaseType().equals(physical.databaseType())) {
            throw validation("Mantıksal şema (" + logical.databaseType() + ") ile fiziksel şema ("
                    + physical.databaseType() + ") teknolojileri farklı.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private int range(Integer value, int fallback, int minimum, int maximum, String field) {
        int candidate = value == null ? fallback : value;
        if (candidate < minimum || candidate > maximum) throw validation(field + " " + minimum + "-" + maximum + " aralığında olmalıdır.");
        return candidate;
    }

    private String identifierOrNull(String value, String field) {
        String candidate = trimToNull(value);
        if (candidate == null) return null;
        if (!IDENTIFIER.matcher(candidate).matches()) throw validation(field + " geçersiz.");
        return candidate;
    }

    private String orDefault(String value, String fallback) {
        String candidate = trimToNull(value);
        return candidate == null ? fallback : candidate;
    }

    private String normalizeCode(String code) {
        String normalized = required(code, "Kod", 100).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw validation("Kod A-Z ile başlamalı; A-Z, 0-9 ve _ içermelidir.");
        return normalized;
    }

    private String normalizeName(String name) {
        return required(name, "Ad", 200);
    }

    private String required(String value, String field, int maximumLength) {
        String normalized = trimToNull(value);
        if (normalized == null) throw validation(field + " gereklidir.");
        if (normalized.length() > maximumLength) throw validation(field + " en fazla " + maximumLength + " karakter olabilir.");
        return normalized;
    }

    private String allowed(String value, Set<String> allowed, String field) {
        String normalized = trimToNull(value) == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw validation("Geçersiz " + field + ".");
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

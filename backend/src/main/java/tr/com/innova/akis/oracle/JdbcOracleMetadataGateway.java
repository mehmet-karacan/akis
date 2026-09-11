package tr.com.innova.akis.oracle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ColumnMetadata;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConstraintMetadata;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.TableMetadata;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.SnapshotCapture;

@Component
final class JdbcOracleMetadataGateway implements OracleMetadataGateway {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JdbcOracleMetadataGateway.class);

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    private static final String DATABASE_IDENTITY_SQL = """
            SELECT SYS_CONTEXT('USERENV', 'DB_UNIQUE_NAME') AS DB_UNIQUE_NAME,
                   SYS_CONTEXT('USERENV', 'CON_NAME') AS CON_NAME
              FROM SYS.DUAL
            """;

    private final OracleDatabaseIdentityFingerprintV1 identityFingerprint =
            new OracleDatabaseIdentityFingerprintV1();
    private final OracleSchemaDictionaryReader schemaDictionaryReader;

    JdbcOracleMetadataGateway(ObjectMapper objectMapper) {
        this.schemaDictionaryReader = new OracleSchemaDictionaryReader(objectMapper);
    }

    @Override
    public ConnectionProbe test(ConnectionProfile profile, Credentials credentials) {
        try (Connection connection = open(profile, credentials)) {
            if (!connection.isValid(5)) {
                throw connectionFailed();
            }
            DatabaseMetaData metadata = connection.getMetaData();
            OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity identity =
                    readDatabaseIdentity(connection);
            return new ConnectionProbe(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(),
                    metadata.getDatabaseMajorVersion(),
                    metadata.getDatabaseMinorVersion(),
                    metadata.getDriverName(),
                    metadata.getDriverVersion(),
                    identity.identityVersion(),
                    identity.fingerprint());
        }
        catch (SQLException exception) {
            throw connectionFailed();
        }
    }

    private OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity readDatabaseIdentity(
            Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DATABASE_IDENTITY_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Oracle database identity query returned no row.");
            }
            String databaseUniqueName = resultSet.getString("DB_UNIQUE_NAME");
            String containerName = resultSet.getString("CON_NAME");
            if (resultSet.next()) {
                throw new SQLException("Oracle database identity query was ambiguous.");
            }
            try {
                return identityFingerprint.canonicalize(databaseUniqueName, containerName);
            }
            catch (IllegalArgumentException exception) {
                throw new SQLException("Oracle database identity is invalid.", exception);
            }
        }
    }

    @Override
    public DiscoveryResult discover(
            ConnectionProfile profile,
            Credentials credentials,
            String owner,
            String tableName,
            int limit) {
        try (Connection connection = open(profile, credentials)) {
            DatabaseMetaData metadata = connection.getMetaData();
            ensureOracle19c(metadata);
            verifyPinnedTargetIdentity(profile, readDatabaseIdentity(connection));
            List<TableMetadata> tables = new ArrayList<>();
            boolean truncated = false;
            String pattern = tableName == null ? "%" : tableName;
            try (ResultSet resultSet = metadata.getTables(
                    null, owner, pattern, new String[] {"TABLE", "VIEW"})) {
                while (resultSet.next()) {
                    if (tables.size() == limit) {
                        truncated = true;
                        break;
                    }
                    String tableOwner = resultSet.getString("TABLE_SCHEM");
                    String discoveredTable = resultSet.getString("TABLE_NAME");
                    String type = resultSet.getString("TABLE_TYPE");
                    tables.add(new TableMetadata(
                            tableOwner,
                            discoveredTable,
                            type,
                            readColumns(metadata, tableOwner, discoveredTable),
                            readConstraints(metadata, tableOwner, discoveredTable)));
                }
            }
            return new DiscoveryResult(
                    owner,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    truncated,
                    List.copyOf(tables));
        }
        catch (SQLException exception) {
            LOGGER.warn(
                    "Oracle metadata discovery failed (vendorCode={}, sqlState={}).",
                    exception.getErrorCode(), exception.getSQLState());
            throw discoveryFailed();
        }
    }

    @Override
    public SnapshotCapture captureSnapshot(
            ConnectionProfile profile,
            Credentials credentials,
            String owner,
            String tableName) {
        try (Connection connection = open(profile, credentials)) {
            DatabaseMetaData metadata = connection.getMetaData();
            ensureOracle19c(metadata);
            verifyPinnedTargetIdentity(profile, readDatabaseIdentity(connection));
            return new SnapshotCapture(
                    OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
                    schemaDictionaryReader.read(connection, owner, tableName));
        }
        catch (OracleSchemaSnapshotCodecException exception) {
            LOGGER.warn("Oracle schema codec rejected dictionary metadata: {}", exception.getMessage());
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORACLE_SCHEMA_UNSUPPORTED",
                    "Oracle tablo şeması güvenli snapshot sözleşmesiyle uyumlu değil.");
        }
        catch (SQLException exception) {
            LOGGER.warn(
                    "Oracle schema capture failed (vendorCode={}, sqlState={}).",
                    exception.getErrorCode(), exception.getSQLState());
            throw discoveryFailed();
        }
    }

    void verifyPinnedTargetIdentity(
            ConnectionProfile profile,
            OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity actual) {
        String expectedFingerprint = profile.targetFingerprint();
        boolean validContract = profile.targetIdentityVersion() != null
                && profile.targetIdentityVersion() == actual.identityVersion()
                && expectedFingerprint != null
                && expectedFingerprint.matches("[0-9a-f]{64}");
        boolean sameTarget = validContract && MessageDigest.isEqual(
                expectedFingerprint.getBytes(StandardCharsets.US_ASCII),
                actual.fingerprint().getBytes(StandardCharsets.US_ASCII));
        if (!sameTarget) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORACLE_TARGET_IDENTITY_MISMATCH",
                    "Oracle metadata hedef kimliği aktif bağlantı kanıtıyla eşleşmiyor.");
        }
    }

    private Connection open(ConnectionProfile profile, Credentials credentials) throws SQLException {
        if ("JNDI".equals(profile.mode())) {
            return openJndi(profile);
        }
        loadDriver(profile.driverReference());
        Properties properties = new Properties();
        properties.setProperty("user", credentials.username());
        properties.setProperty("password", new String(credentials.password()));
        properties.setProperty(
                "oracle.net.CONNECT_TIMEOUT",
                Integer.toString(policyInteger(
                        profile, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS, 1_000, 120_000)));
        properties.setProperty(
                "oracle.jdbc.ReadTimeout",
                Integer.toString(policyInteger(
                        profile, "readTimeoutMs", DEFAULT_READ_TIMEOUT_MS, 1_000, 300_000)));
        Connection connection;
        try {
            connection = DriverManager.getConnection(jdbcUrl(profile), properties);
        }
        finally {
            properties.clear();
        }
        try {
            connection.setReadOnly(true);
            return connection;
        }
        catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private Connection openJndi(ConnectionProfile profile) {
        InitialContext context = null;
        Connection connection = null;
        try {
            context = new InitialContext();
            Object resource = context.lookup(profile.jndiName());
            if (!(resource instanceof DataSource dataSource)) {
                throw jndiUnavailable();
            }
            connection = dataSource.getConnection();
            connection.setReadOnly(true);
            return connection;
        }
        catch (NamingException | SQLException | RuntimeException exception) {
            if (connection != null) {
                try {
                    connection.close();
                }
                catch (SQLException ignored) {
                    // Preserve only the sanitized JNDI failure.
                }
            }
            throw jndiUnavailable();
        }
        finally {
            if (context != null) {
                try {
                    context.close();
                }
                catch (NamingException ignored) {
                    // The DataSource lookup result no longer depends on this context.
                }
            }
        }
    }

    private ApiException jndiUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ORACLE_JNDI_RESOURCE_UNAVAILABLE",
                "Oracle JNDI DataSource bu çalışma ortamında kullanılamıyor.");
    }

    private void loadDriver(String driverReference) {
        try {
            Class.forName(driverReference);
        }
        catch (ClassNotFoundException | LinkageError exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORACLE_DRIVER_UNAVAILABLE",
                    "Oracle JDBC sürücüsü çalışma zamanında bulunamadı.");
        }
    }

    private String jdbcUrl(ConnectionProfile profile) {
        String protocol = "DISABLED".equals(profile.tlsMode()) ? "tcp" : "tcps";
        if (profile.serviceName() != null) {
            return "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=" + protocol
                    + ")(HOST=" + profile.host() + ")(PORT=" + profile.port()
                    + "))(CONNECT_DATA=(SERVICE_NAME=" + profile.serviceName() + ")))";
        }
        return "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=" + protocol
                + ")(HOST=" + profile.host() + ")(PORT=" + profile.port()
                + "))(CONNECT_DATA=(SID=" + profile.sid() + ")))";
    }

    private int policyInteger(
            ConnectionProfile profile,
            String name,
            int defaultValue,
            int minimum,
            int maximum) {
        if (profile.policy() == null || !profile.policy().has(name)) {
            return defaultValue;
        }
        int value = profile.policy().path(name).asInt(-1);
        if (value < minimum || value > maximum) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORACLE_POLICY_INVALID",
                    "Oracle bağlantı timeout politikası geçersiz.");
        }
        return value;
    }

    private List<ColumnMetadata> readColumns(
            DatabaseMetaData metadata,
            String owner,
            String table) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet resultSet = metadata.getColumns(null, owner, table, "%")) {
            while (resultSet.next()) {
                // Oracle exposes COLUMN_DEF as a LONG-backed stream. Reading it through
                // DatabaseMetaData can close the stream (ORA-17027) before iteration ends.
                // Default expressions require a separate, explicit dictionary query.
                String defaultValue = null;
                columns.add(new ColumnMetadata(
                        resultSet.getString("COLUMN_NAME"),
                        resultSet.getInt("DATA_TYPE"),
                        resultSet.getString("TYPE_NAME"),
                        resultSet.getInt("ORDINAL_POSITION"),
                        nullableInteger(resultSet, "COLUMN_SIZE"),
                        nullableInteger(resultSet, "DECIMAL_DIGITS"),
                        resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        defaultValue));
            }
        }
        return List.copyOf(columns);
    }

    private List<ConstraintMetadata> readConstraints(
            DatabaseMetaData metadata,
            String owner,
            String table) throws SQLException {
        List<ConstraintMetadata> constraints = new ArrayList<>();
        Set<String> primaryKeyNames = new LinkedHashSet<>();
        Map<String, List<String>> primaryKeys = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(null, owner, table)) {
            while (resultSet.next()) {
                String name = fallbackName(resultSet.getString("PK_NAME"), "PK_" + table);
                primaryKeyNames.add(name);
                primaryKeys.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(resultSet.getString("COLUMN_NAME"));
            }
        }
        primaryKeys.forEach((name, columns) -> constraints.add(new ConstraintMetadata(
                name, "PK", List.copyOf(columns), null, null)));

        Map<String, List<String>> uniqueKeys = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(null, owner, table, true, true)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                String column = resultSet.getString("COLUMN_NAME");
                if (name != null && column != null && !primaryKeyNames.contains(name)
                        && resultSet.getShort("TYPE") != DatabaseMetaData.tableIndexStatistic) {
                    uniqueKeys.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                }
            }
        }
        uniqueKeys.forEach((name, columns) -> constraints.add(new ConstraintMetadata(
                name, "UK", List.copyOf(columns), null, null)));

        Map<String, ForeignKeyBuilder> foreignKeys = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getImportedKeys(null, owner, table)) {
            while (resultSet.next()) {
                String name = fallbackName(resultSet.getString("FK_NAME"), "FK_" + table);
                String referencedOwner = resultSet.getString("PKTABLE_SCHEM");
                String referencedTable = resultSet.getString("PKTABLE_NAME");
                ForeignKeyBuilder key = foreignKeys.computeIfAbsent(
                        name,
                        ignored -> new ForeignKeyBuilder(referencedOwner, referencedTable));
                key.columns.add(resultSet.getString("FKCOLUMN_NAME"));
            }
        }
        foreignKeys.forEach((name, key) -> constraints.add(new ConstraintMetadata(
                name, "FK", List.copyOf(key.columns), key.owner, key.table)));
        return List.copyOf(constraints);
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private void ensureOracle19c(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName();
        if (product == null || !product.toUpperCase(java.util.Locale.ROOT).contains("ORACLE")
                || metadata.getDatabaseMajorVersion() != 19) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORACLE_VERSION_UNSUPPORTED",
                    "Bağlantı Oracle Database 19c ile uyumlu değil.");
        }
    }

    private String fallbackName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private ApiException connectionFailed() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "ORACLE_CONNECTION_FAILED",
                "Oracle bağlantısı kurulamadı veya doğrulanamadı.");
    }

    private ApiException discoveryFailed() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "ORACLE_DISCOVERY_FAILED",
                "Oracle şema metadata keşfi tamamlanamadı.");
    }

    private static final class ForeignKeyBuilder {
        private final String owner;
        private final String table;
        private final List<String> columns = new ArrayList<>();

        private ForeignKeyBuilder(String owner, String table) {
            this.owner = owner;
            this.table = table;
        }
    }
}

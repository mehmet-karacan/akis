package tr.com.innova.akis.oracle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.HexFormat;

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
final class JdbcSchemaDiscoveryPort implements SchemaDiscoveryPort {

    @Override
    public String technology() { return "ORACLE"; }

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JdbcSchemaDiscoveryPort.class);

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    private static final String DATABASE_IDENTITY_SQL = """
            SELECT SYS_CONTEXT('USERENV', 'DB_UNIQUE_NAME') AS DB_UNIQUE_NAME,
                   SYS_CONTEXT('USERENV', 'CON_NAME') AS CON_NAME
              FROM SYS.DUAL
            """;
    private static final String ACCESSIBLE_SCHEMAS_SQL = """
            SELECT USERNAME AS OWNER
              FROM ALL_USERS
             ORDER BY USERNAME
            """;

    private final OracleDatabaseIdentityFingerprintV1 identityFingerprint =
            new OracleDatabaseIdentityFingerprintV1();
    private final OracleSchemaDictionaryReader schemaDictionaryReader;

    JdbcSchemaDiscoveryPort(ObjectMapper objectMapper) {
        this.schemaDictionaryReader = new OracleSchemaDictionaryReader(objectMapper);
    }

    @Override
    public ConnectionProbe test(ConnectionProfile profile, Credentials credentials) {
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
            if (!connection.isValid(5)) {
                throw DiscoveryConnections.connectionFailed();
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
            throw DiscoveryConnections.connectionFailed();
        }
    }

    @Override
    public List<String> listSchemas(ConnectionProfile profile, Credentials credentials) {
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
            DatabaseMetaData metadata = connection.getMetaData();
            ensureOracle19c(metadata);
            verifyPinnedTargetIdentity(profile, readDatabaseIdentity(connection));
            Set<String> schemas = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(ACCESSIBLE_SCHEMAS_SQL);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    schemas.add(resultSet.getString("OWNER"));
                }
            }
            return List.copyOf(schemas);
        }
        catch (SQLException exception) {
            LOGGER.warn(
                    "Oracle schema listing failed (vendorCode={}, sqlState={}).",
                    exception.getErrorCode(), exception.getSQLState());
            throw DiscoveryConnections.discoveryFailed();
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
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
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
                            JdbcDictionaryMetadata.readColumns(metadata, tableOwner, discoveredTable),
                            JdbcDictionaryMetadata.readConstraints(metadata, tableOwner, discoveredTable)));
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
            throw DiscoveryConnections.discoveryFailed();
        }
    }

    @Override
    public SnapshotCapture captureSnapshot(
            ConnectionProfile profile,
            Credentials credentials,
            String owner,
            String tableName) {
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
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
            throw DiscoveryConnections.discoveryFailed();
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

}

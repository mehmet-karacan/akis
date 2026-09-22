package tr.com.innova.akis.postgres;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.DiscoveryConnections;
import tr.com.innova.akis.oracle.JdbcDictionaryMetadata;
import tr.com.innova.akis.oracle.OracleDatabaseIdentityFingerprintV1;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.SnapshotCapture;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.TableMetadata;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecException;
import tr.com.innova.akis.oracle.SchemaDiscoveryPort;

/**
 * PostgreSQL adapter of {@link SchemaDiscoveryPort}. The connection identity that the topology pins is
 * {@code POSTGRESQL_DB_V1}: database name plus its pg_database oid, so a database re-created under the same name is a
 * different target while dumps restored into it are not (the installation uuid of the target ledger refines this later).
 */
@Component
public final class JdbcPostgresSchemaDiscovery implements SchemaDiscoveryPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcPostgresSchemaDiscovery.class);
    private static final String DATABASE_IDENTITY_SQL = """
            SELECT current_database() AS database_name, d.oid AS database_oid
              FROM pg_catalog.pg_database d
             WHERE d.datname = current_database()
            """;
    private static final String ACCESSIBLE_SCHEMAS_SQL = """
            SELECT n.nspname
              FROM pg_catalog.pg_namespace n
             WHERE n.nspname NOT LIKE 'pg\\_%' AND n.nspname <> 'information_schema'
               AND pg_catalog.has_schema_privilege(n.oid, 'USAGE')
             ORDER BY n.nspname
            """;

    private final PostgresSchemaDictionaryReader dictionaryReader;

    public JdbcPostgresSchemaDiscovery(ObjectMapper objectMapper) {
        this.dictionaryReader = new PostgresSchemaDictionaryReader(objectMapper);
    }

    @Override
    public String technology() { return "POSTGRESQL"; }

    @Override
    public ConnectionProbe test(ConnectionProfile profile, Credentials credentials) {
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
            if (!connection.isValid(5)) throw DiscoveryConnections.connectionFailed();
            DatabaseMetaData metadata = connection.getMetaData();
            return new ConnectionProbe(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(),
                    metadata.getDatabaseMajorVersion(),
                    metadata.getDatabaseMinorVersion(),
                    metadata.getDriverName(),
                    metadata.getDriverVersion(),
                    OracleDatabaseIdentityFingerprintV1.IDENTITY_VERSION,
                    databaseFingerprint(connection));
        }
        catch (SQLException exception) {
            throw DiscoveryConnections.connectionFailed();
        }
    }

    @Override
    public List<String> listSchemas(ConnectionProfile profile, Credentials credentials) {
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
            requirePinnedIdentity(profile, connection);
            List<String> schemas = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(ACCESSIBLE_SCHEMAS_SQL);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) schemas.add(resultSet.getString("nspname"));
            }
            return List.copyOf(schemas);
        }
        catch (SQLException exception) {
            LOGGER.warn("PostgreSQL schema listing failed (sqlState={}).", exception.getSQLState());
            throw DiscoveryConnections.discoveryFailed();
        }
    }

    @Override
    public DiscoveryResult discover(ConnectionProfile profile, Credentials credentials, String owner, String tableName, int limit) {
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
            requirePinnedIdentity(profile, connection);
            DatabaseMetaData metadata = connection.getMetaData();
            List<TableMetadata> tables = new ArrayList<>();
            boolean truncated = false;
            String pattern = tableName == null ? "%" : tableName;
            try (ResultSet resultSet = metadata.getTables(null, owner, pattern, new String[] {"TABLE", "PARTITIONED TABLE", "VIEW"})) {
                while (resultSet.next()) {
                    if (tables.size() == limit) { truncated = true; break; }
                    String tableOwner = resultSet.getString("TABLE_SCHEM");
                    String discoveredTable = resultSet.getString("TABLE_NAME");
                    String type = "VIEW".equals(resultSet.getString("TABLE_TYPE")) ? "VIEW" : "TABLE";
                    tables.add(new TableMetadata(tableOwner, discoveredTable, type,
                            JdbcDictionaryMetadata.readColumns(metadata, tableOwner, discoveredTable),
                            JdbcDictionaryMetadata.readConstraints(metadata, tableOwner, discoveredTable)));
                }
            }
            return new DiscoveryResult("POSTGRESQL", owner, OffsetDateTime.now(ZoneOffset.UTC), truncated, List.copyOf(tables));
        }
        catch (SQLException exception) {
            LOGGER.warn("PostgreSQL metadata discovery failed (sqlState={}).", exception.getSQLState());
            throw DiscoveryConnections.discoveryFailed();
        }
    }

    @Override
    public SnapshotCapture captureSnapshot(ConnectionProfile profile, Credentials credentials, String owner, String tableName) {
        try (Connection connection = DiscoveryConnections.open(profile, credentials)) {
            requirePinnedIdentity(profile, connection);
            return new SnapshotCapture(
                    OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
                    dictionaryReader.read(connection, owner, tableName));
        }
        catch (OracleSchemaSnapshotCodecException exception) {
            LOGGER.warn("PostgreSQL schema codec rejected catalog metadata: {}", exception.getMessage());
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "POSTGRESQL_SCHEMA_UNSUPPORTED",
                    "PostgreSQL tablo şeması güvenli snapshot sözleşmesiyle uyumlu değil.");
        }
        catch (SQLException exception) {
            LOGGER.warn("PostgreSQL schema capture failed (sqlState={}).", exception.getSQLState());
            throw DiscoveryConnections.discoveryFailed();
        }
    }

    /** A pinned connection identity must still describe the database we are talking to; otherwise nothing is read. */
    private void requirePinnedIdentity(ConnectionProfile profile, Connection connection) throws SQLException {
        if (profile.targetFingerprint() == null) return;
        if (!profile.targetFingerprint().equals(databaseFingerprint(connection))) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTGRESQL_TARGET_IDENTITY_CHANGED",
                    "PostgreSQL veritabanı kimliği bağlantı testinde kaydedilenden farklı; bağlantıyı yeniden test edin.");
        }
    }

    static String databaseFingerprint(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DATABASE_IDENTITY_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) throw new SQLException("current_database() is unavailable.");
            return sha256("POSTGRESQL_DB_V1|" + resultSet.getString("database_name") + "|" + resultSet.getLong("database_oid"));
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable.", exception);
        }
    }
}

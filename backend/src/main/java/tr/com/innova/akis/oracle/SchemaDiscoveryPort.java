package tr.com.innova.akis.oracle;

import java.util.List;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.SnapshotCapture;

/**
 * Technology port for metadata discovery: probe a connection, list schemas, browse tables and capture the governed
 * schema snapshot of one table. {@link OracleDiscoveryService} routes each call to the adapter whose {@link #technology()}
 * matches the connection's provider (Oracle: {@link JdbcOracleMetadataGateway}, PostgreSQL: JdbcPostgresSchemaDiscovery).
 */
public interface SchemaDiscoveryPort {
    /** Provider code as stored on the connection (ORACLE, POSTGRESQL). */
    String technology();

    ConnectionProbe test(ConnectionProfile profile, Credentials credentials);

    List<String> listSchemas(ConnectionProfile profile, Credentials credentials);

    DiscoveryResult discover(
            ConnectionProfile profile,
            Credentials credentials,
            String owner,
            String tableName,
            int limit);

    SnapshotCapture captureSnapshot(
            ConnectionProfile profile,
            Credentials credentials,
            String owner,
            String tableName);
}

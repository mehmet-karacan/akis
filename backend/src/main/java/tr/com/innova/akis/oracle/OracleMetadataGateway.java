package tr.com.innova.akis.oracle;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.SnapshotCapture;

interface OracleMetadataGateway {

    ConnectionProbe test(ConnectionProfile profile, Credentials credentials);

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

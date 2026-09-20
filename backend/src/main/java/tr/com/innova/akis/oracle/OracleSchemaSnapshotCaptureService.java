package tr.com.innova.akis.oracle;

import java.util.UUID;

import org.springframework.stereotype.Service;

import tr.com.innova.akis.oracle.OracleSchemaSnapshotWriter.PersistResult;

@Service
class OracleSchemaSnapshotCaptureService {

    private final OracleDiscoveryService discoveryService;
    private final OracleSchemaSnapshotWriter writer;

    OracleSchemaSnapshotCaptureService(
            OracleDiscoveryService discoveryService,
            OracleSchemaSnapshotWriter writer) {
        this.discoveryService = discoveryService;
        this.writer = writer;
    }

    PersistResult capture(
            UUID projectUuid,
            UUID connectionUuid,
            UUID physicalSchemaUuid,
            UUID dataObjectUuid) {
        return writer.persist(discoveryService.captureSchemaSnapshot(
                projectUuid, connectionUuid, physicalSchemaUuid, dataObjectUuid));
    }
}

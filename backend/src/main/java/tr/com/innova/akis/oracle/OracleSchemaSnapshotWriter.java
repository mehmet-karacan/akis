package tr.com.innova.akis.oracle;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.discovery.SchemaSnapshotService;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.GovernedSnapshotCapture;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.SnapshotDefinition;

@Service
class OracleSchemaSnapshotWriter {

    private final SchemaSnapshotService snapshotService;
    private final OracleSchemaSnapshotEvidenceRepository evidenceRepository;

    OracleSchemaSnapshotWriter(
            SchemaSnapshotService snapshotService,
            OracleSchemaSnapshotEvidenceRepository evidenceRepository) {
        this.snapshotService = snapshotService;
        this.evidenceRepository = evidenceRepository;
    }

    @Transactional
    PersistResult persist(GovernedSnapshotCapture governed) {
        SnapshotDefinition definition = governed.capture().definition();
        List<ColumnInput> columns = definition.columns().stream()
                .map(column -> new ColumnInput(
                        column.reference(), column.producerType(), column.canonicalType(),
                        column.ordinal(), column.precision(), column.scale(), column.length(),
                        column.timePrecision(), column.nullable(), column.defaultExpression(),
                        column.name()))
                .toList();
        List<ConstraintInput> constraints = definition.constraints().stream()
                .map(constraint -> new ConstraintInput(
                        constraint.externalReference(), constraint.type(),
                        constraint.enabled(), constraint.detailVersion(), constraint.details(),
                        constraint.name(), constraint.columnReferences()))
                .toList();
        SnapshotRow snapshot = snapshotService.create(
                governed.projectUuid(), governed.dataObjectUuid(),
                governed.physicalSchemaUuid(), governed.connectionVersionUuid(),
                definition.engineVersion(), governed.capture().capturedAt(),
                definition.propertyVersion(), definition.properties(), columns, constraints);
        evidenceRepository.attest(
                governed.projectUuid(), governed.connectionUuid(),
                governed.connectionVersionUuid(), snapshot.uuid(),
                governed.lifecycleStateVersion(), governed.successfulTestUuid(),
                governed.targetIdentityVersion(), governed.targetFingerprint());
        return new PersistResult(
                snapshot,
                !snapshot.discoveredAt().equals(governed.capture().capturedAt()));
    }

    record PersistResult(SnapshotRow snapshot, boolean reused) {
    }
}

package tr.com.innova.akis.oracle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotWriter.PersistResult;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.DISCOVERY_WRITE;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/connections/{connectionUuid}")
final class OracleSchemaSnapshotCaptureController {

    private final OracleSchemaSnapshotCaptureService service;
    private final AuthorizationService authorization;

    OracleSchemaSnapshotCaptureController(
            OracleSchemaSnapshotCaptureService service,
            AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/physical-schemas/{physicalSchemaUuid}/data-objects/{dataObjectUuid}/schema-snapshots:discover")
    ResponseEntity<SnapshotView> capture(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID physicalSchemaUuid,
            @PathVariable UUID dataObjectUuid) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        PersistResult result = service.capture(
                projectUuid, connectionUuid, physicalSchemaUuid, dataObjectUuid);
        HttpStatus status = result.reused() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(
                SnapshotView.from(result.snapshot(), result.reused()));
    }

    record SnapshotView(
            UUID uuid,
            UUID dataObjectUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            String fingerprint,
            String engineVersion,
            OffsetDateTime discoveredAt,
            OffsetDateTime createdAt,
            List<ColumnView> columns,
            List<ConstraintView> constraints,
            boolean serverProduced,
            boolean reused) {

        static SnapshotView from(SnapshotRow row, boolean reused) {
            return new SnapshotView(
                    row.uuid(), row.dataObjectUuid(), row.physicalSchemaUuid(),
                    row.connectionVersionUuid(), row.fingerprint(), row.engineVersion(),
                    row.discoveredAt(), row.createdAt(),
                    row.columns().stream().map(ColumnView::from).toList(),
                    row.constraints().stream().map(ConstraintView::from).toList(),
                    true, reused);
        }
    }

    record ColumnView(
            String reference,
            String producerType,
            String canonicalType,
            int ordinal,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision,
            boolean nullable) {

        static ColumnView from(ColumnRow row) {
            return new ColumnView(
                    row.reference(), row.producerType(), row.canonicalType(), row.ordinal(),
                    row.precision(), row.scale(), row.length(), row.timePrecision(),
                    row.nullable());
        }
    }

    record ConstraintView(
            String externalReference,
            String type,
            boolean enabled,
            String name,
            List<String> columnReferences) {

        static ConstraintView from(ConstraintRow row) {
            return new ConstraintView(
                    row.externalReference(), row.type(), row.enabled(), row.name(),
                    row.columnReferences());
        }
    }
}

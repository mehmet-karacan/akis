package tr.com.innova.akis.discovery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/data-objects/{dataObjectUuid}/schema-snapshots")
final class SchemaSnapshotController {

    private final SchemaSnapshotService service;
    private final AuthorizationService authorization;

    SchemaSnapshotController(SchemaSnapshotService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ResponseEntity<SnapshotView> create(
            @PathVariable UUID projectUuid,
            @PathVariable UUID dataObjectUuid,
            @Valid @RequestBody CreateSnapshotRequest request) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        SnapshotRow row = service.create(
                projectUuid, dataObjectUuid, request.physicalSchemaUuid(),
                request.connectionVersionUuid(), request.engineVersion(),
                request.discoveredAt(), request.propertyVersion(), request.properties(),
                request.columns().stream().map(ColumnRequest::toInput).toList(),
                request.constraints().stream().map(ConstraintRequest::toInput).toList());
        return ResponseEntity.status(201).body(SnapshotView.from(row));
    }

    @GetMapping
    List<SnapshotView> list(
            @PathVariable UUID projectUuid,
            @PathVariable UUID dataObjectUuid) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_READ);
        return service.list(projectUuid, dataObjectUuid).stream()
                .map(SnapshotView::from)
                .toList();
    }

    @GetMapping("/{snapshotUuid}")
    SnapshotView get(
            @PathVariable UUID projectUuid,
            @PathVariable UUID dataObjectUuid,
            @PathVariable UUID snapshotUuid) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_READ);
        return SnapshotView.from(service.get(projectUuid, dataObjectUuid, snapshotUuid));
    }

    record CreateSnapshotRequest(
            @NotNull UUID physicalSchemaUuid,
            @NotNull UUID connectionVersionUuid,
            @NotBlank String engineVersion,
            @NotNull OffsetDateTime discoveredAt,
            @Min(1) int propertyVersion,
            @NotNull JsonNode properties,
            @NotEmpty List<@Valid ColumnRequest> columns,
            @NotNull List<@Valid ConstraintRequest> constraints) {
    }

    record ColumnRequest(
            @NotBlank String reference,
            @NotBlank String producerType,
            @NotBlank String canonicalType,
            @Min(1) int ordinal,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision,
            boolean nullable,
            String defaultExpression,
            @NotBlank String name) {

        ColumnInput toInput() {
            return new ColumnInput(
                    reference, producerType, canonicalType, ordinal, precision, scale,
                    length, timePrecision, nullable, defaultExpression, name);
        }
    }

    record ConstraintRequest(
            @NotBlank String externalReference,
            @NotBlank String type,
            boolean enabled,
            @Min(1) int detailVersion,
            @NotNull JsonNode details,
            @NotBlank String name,
            @NotNull List<@NotBlank String> columnReferences) {

        ConstraintInput toInput() {
            return new ConstraintInput(
                    externalReference, type, enabled, detailVersion, details, name,
                    columnReferences);
        }
    }

    record SnapshotView(
            UUID uuid,
            UUID dataObjectUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            String fingerprint,
            String engineVersion,
            OffsetDateTime discoveredAt,
            int propertyVersion,
            JsonNode properties,
            OffsetDateTime createdAt,
            List<ColumnView> columns,
            List<ConstraintView> constraints) {

        static SnapshotView from(SnapshotRow row) {
            return new SnapshotView(
                    row.uuid(), row.dataObjectUuid(), row.physicalSchemaUuid(),
                    row.connectionVersionUuid(), row.fingerprint(), row.engineVersion(),
                    row.discoveredAt(), row.propertyVersion(), row.properties(), row.createdAt(),
                    row.columns().stream().map(ColumnView::from).toList(),
                    row.constraints().stream().map(ConstraintView::from).toList());
        }
    }

    record ColumnView(
            UUID uuid,
            String reference,
            String producerType,
            String canonicalType,
            int ordinal,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision,
            boolean nullable,
            String defaultExpression,
            String name) {

        static ColumnView from(ColumnRow row) {
            return new ColumnView(
                    row.uuid(), row.reference(), row.producerType(), row.canonicalType(),
                    row.ordinal(), row.precision(), row.scale(), row.length(),
                    row.timePrecision(), row.nullable(), row.defaultExpression(), row.name());
        }
    }

    record ConstraintView(
            UUID uuid,
            String externalReference,
            String type,
            boolean enabled,
            int detailVersion,
            JsonNode details,
            String name,
            List<String> columnReferences) {

        static ConstraintView from(ConstraintRow row) {
            return new ConstraintView(
                    row.uuid(), row.externalReference(), row.type(), row.enabled(),
                    row.detailVersion(), row.details(), row.name(), row.columnReferences());
        }
    }
}

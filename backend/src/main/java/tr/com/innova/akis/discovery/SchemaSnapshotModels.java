package tr.com.innova.akis.discovery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public final class SchemaSnapshotModels {

    private SchemaSnapshotModels() {
    }

    public record ProjectRef(long id) {
    }

    public record DataObjectRef(long id, UUID uuid) {
    }

    public record PhysicalSchemaRef(long id, UUID uuid, long connectionId) {
    }

    public record ConnectionVersionRef(long id, UUID uuid, long connectionId) {
    }

    public record ColumnInput(
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
    }

    public record ConstraintInput(
            String externalReference,
            String type,
            boolean enabled,
            int detailVersion,
            JsonNode details,
            String name,
            List<String> columnReferences) {
    }

    public record CreateSnapshot(
            long projectId,
            long dataObjectId,
            UUID dataObjectUuid,
            long physicalSchemaId,
            UUID physicalSchemaUuid,
            long connectionVersionId,
            UUID connectionVersionUuid,
            UUID uuid,
            String fingerprint,
            String engineVersion,
            OffsetDateTime discoveredAt,
            int propertyVersion,
            JsonNode properties,
            List<ColumnInput> columns,
            List<ConstraintInput> constraints) {
    }

    public record ColumnRow(
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
    }

    public record ConstraintRow(
            UUID uuid,
            String externalReference,
            String type,
            boolean enabled,
            int detailVersion,
            JsonNode details,
            String name,
            List<String> columnReferences) {
    }

    public record SnapshotRow(
            long id,
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
            List<ColumnRow> columns,
            List<ConstraintRow> constraints) {
    }
}

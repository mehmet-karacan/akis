package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Typed, immutable execution input for the bounded Oracle-to-Oracle pilot.
 * It deliberately contains no connection URL or credential material.
 */
public record PilotRuntimePlan(
        int planVersion,
        String runtimePlanHash,
        String releaseHash,
        String scenarioPlanHash,
        UUID definitionUuid,
        UUID definitionVersionUuid,
        int maximumSourceRows,
        DatasetBinding source,
        DatasetBinding target,
        List<DirectColumnMapping> columnMappings,
        WriteStrategy writeStrategy,
        JsonNode canonicalPlan) implements MappingExecutionContract {

    static final int CURRENT_VERSION = 1;
    static final int MAXIMUM_SOURCE_ROWS = 1_000;

    public PilotRuntimePlan {
        columnMappings = List.copyOf(columnMappings);
        canonicalPlan = canonicalPlan.deepCopy();
    }

    @Override
    public JsonNode canonicalPlan() {
        return canonicalPlan.deepCopy();
    }

    public enum WriteStrategy {
        ATOMIC_DELETE_INSERT
    }

    public record DirectColumnMapping(String sourceColumn, String targetColumn) implements MappingExecutionContract.ColumnProjection {
    }

    public record DatasetBinding(
            String datasetId,
            DatasetRole role,
            DatabaseType databaseType,
            DataObjectType dataObjectType,
            UUID definitionDataObjectUuid,
            UUID dataObjectUuid,
            UUID environmentSchemaBindingUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            UUID schemaSnapshotUuid,
            long bindingVersion,
            String schemaSnapshotFingerprint,
            String physicalIdentity,
            String owner,
            String objectName) {
    }

    public enum DatasetRole {
        SOURCE,
        TARGET
    }

    public enum DatabaseType {
        ORACLE
    }

    public enum DataObjectType {
        TABLE
    }
}

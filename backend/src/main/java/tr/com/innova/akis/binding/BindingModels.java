package tr.com.innova.akis.binding;

import java.time.OffsetDateTime;
import java.util.UUID;

import tr.com.innova.akis.metadata.DefinitionType;

final class BindingModels {

    private BindingModels() {
    }

    record ProjectRef(long id) {
    }

    record DefinitionVersionRef(
            long id,
            UUID definitionUuid,
            UUID versionUuid,
            DefinitionType definitionType) {
    }

    record DataObjectRef(long id, UUID uuid) {
    }

    record SchemaSnapshotRef(long id, UUID uuid, long dataObjectId) {
    }

    record CreateBinding(
            long projectId,
            long definitionVersionId,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            long dataObjectId,
            UUID dataObjectUuid,
            long schemaSnapshotId,
            UUID schemaSnapshotUuid,
            String nodeCode,
            BindingRole role,
            UUID uuid) {
    }

    record BindingRow(
            UUID uuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            String nodeCode,
            BindingRole role,
            UUID dataObjectUuid,
            UUID schemaSnapshotUuid,
            OffsetDateTime createdAt) {
    }
}

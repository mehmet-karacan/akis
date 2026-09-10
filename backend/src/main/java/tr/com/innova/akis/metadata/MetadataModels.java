package tr.com.innova.akis.metadata;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

final class MetadataModels {

    private MetadataModels() {
    }

    record ProjectRow(
            long id,
            UUID uuid,
            String code,
            String status,
            String name,
            String description,
            long version,
            OffsetDateTime createdAt) {
    }

    record FolderRow(
            long id,
            long projectId,
            UUID uuid,
            UUID parentUuid,
            String code,
            String type,
            String status,
            String name,
            String description,
            long version) {
    }

    record DefinitionRow(
            long id,
            Long projectId,
            UUID uuid,
            UUID folderUuid,
            DefinitionType type,
            String code,
            String status,
            String name,
            String description,
            long version) {
    }

    record DraftRow(
            UUID uuid,
            long definitionId,
            int schemaVersion,
            JsonNode content,
            long version) {
    }

    record VersionRow(
            UUID uuid,
            int versionNumber,
            int schemaVersion,
            String contentHash,
            JsonNode content,
            String description,
            OffsetDateTime createdAt) {
    }
}

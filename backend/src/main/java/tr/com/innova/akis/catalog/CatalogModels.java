package tr.com.innova.akis.catalog;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

final class CatalogModels {

    private CatalogModels() {
    }

    record ProjectRef(long id) {
    }

    record LogicalSchemaRef(long id, UUID uuid) {
    }

    record ModelRow(
            long id,
            UUID uuid,
            long logicalSchemaId,
            UUID logicalSchemaUuid,
            String technologyCode,
            UUID reverseEnvironmentUuid,
            String reverseMode,
            UUID rkmDefinitionUuid,
            JsonNode reverseOptions,
            String code,
            String status,
            String name,
            String description,
            long dataObjectCount,
            OffsetDateTime lastMetadataUpdate,
            long version) {
    }

    record SubmodelRow(
            long id,
            UUID uuid,
            long modelId,
            UUID modelUuid,
            UUID parentUuid,
            String code,
            String name,
            long version) {
    }

    record DataObjectRow(
            long id,
            UUID uuid,
            long modelId,
            UUID modelUuid,
            Long submodelId,
            UUID submodelUuid,
            String code,
            String objectReference,
            String type,
            String status,
            Integer querySchemaVersion,
            JsonNode queryDefinition,
            String name,
            long version) {
    }
}

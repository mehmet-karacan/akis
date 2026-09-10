package tr.com.innova.akis.scenario;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.metadata.DefinitionType;

final class ScenarioModels {

    private ScenarioModels() {
    }

    record SourceVersion(
            long id,
            long projectId,
            UUID projectUuid,
            UUID definitionUuid,
            UUID versionUuid,
            DefinitionType definitionType,
            int definitionVersion,
            int schemaVersion,
            String contentHash,
            JsonNode content) {
    }

    record CompiledPlan(
            int planVersion,
            String planHash,
            JsonNode plan,
            JsonNode parameterSchema,
            JsonNode validationResult) {
    }

    record ScenarioRow(
            long id,
            UUID uuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            DefinitionType definitionType,
            int scenarioVersion,
            int planVersion,
            String planHash,
            JsonNode plan,
            JsonNode parameterSchema,
            OffsetDateTime createdAt) {
    }

    record CompileResult(ScenarioRow scenario, boolean created) {
    }
}

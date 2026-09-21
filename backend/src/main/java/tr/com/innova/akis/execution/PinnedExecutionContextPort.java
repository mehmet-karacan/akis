package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

interface PinnedExecutionContextPort {

    Optional<PinnedExecutionContext> find(UUID runUuid);

    /** Job parameters pinned on the request (e.g. {"batchRows": 2000}); empty object when none. */
    default tools.jackson.databind.JsonNode jobParameters(UUID runUuid) { return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(); }

    /** For a RESUME attempt (baslatma_turu DEVAM_ET): the failed attempt of the same job it continues from. */
    default Optional<UUID> resumeOrigin(UUID runUuid) { return Optional.empty(); }

    record PinnedExecutionContext(
            UUID jobRequestUuid,
            UUID runUuid,
            UUID publicationUuid,
            int attemptNumber,
            String releaseHash,
            String planHash,
            JsonNode scenarioPlan,
            JsonNode physicalManifest) {
    }
}

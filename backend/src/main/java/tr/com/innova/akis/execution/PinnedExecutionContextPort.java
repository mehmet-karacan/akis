package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

interface PinnedExecutionContextPort {

    Optional<PinnedExecutionContext> find(UUID runUuid);

    record PinnedExecutionContext(
            UUID jobRequestUuid,
            UUID runUuid,
            UUID publicationUuid,
            int attemptNumber,
            String releaseHash,
            String planHash,
            JsonNode physicalManifest) {
    }
}

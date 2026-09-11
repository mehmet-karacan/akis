package tr.com.innova.akis.execution;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

final class ExecutionModels {

    private ExecutionModels() {
    }

    record Actor(long id, UUID uuid, String name) {
    }

    record PublicationContext(
            long projectId,
            long publicationId,
            UUID publicationUuid,
            String publicationStatus,
            String environmentRisk,
            String releaseHash,
            String planHash,
            JsonNode physicalManifest) {
    }

    record IdempotencyReservation(
            long id,
            String requestHash,
            Long jobRequestId) {
    }

    record RunRow(
            long jobRequestId,
            long runId,
            long stateId,
            UUID jobRequestUuid,
            UUID runUuid,
            UUID publicationUuid,
            int attemptNumber,
            String startType,
            String status,
            String releaseHash,
            String planHash,
            long lastEventNumber,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime cancellationRequestedAt) {
    }

    record RunEventRow(
            UUID uuid,
            long eventNumber,
            String type,
            OffsetDateTime eventTime,
            JsonNode data) {
    }

    record StartResult(RunRow run, boolean created) {
    }
}

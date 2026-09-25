package tr.com.innova.akis.execution;

import java.time.OffsetDateTime;
import java.util.List;
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

    record RunEventPage(List<RunEventRow> items, Long nextCursor, boolean hasMore) {
    }

    record RunSummaryRow(
            RunRow run,
            UUID definitionUuid,
            String definitionCode,
            String definitionName,
            String definitionType,
            UUID environmentUuid,
            String environmentCode,
            String environmentName,
            String environmentRisk,
            String initiatorName,
            /** The schedule that fired this run (V049); null means it was started manually. */
            String scheduleCode,
            Long selectedRows,
            Long insertedRows,
            Long updatedRows,
            Long deletedRows) {
    }

    record RunOverviewRow(
            long totalRuns,
            long activeRuns,
            long queuedRuns,
            long succeededRuns,
            long failedRuns,
            long selectedRows,
            long insertedRows,
            long updatedRows,
            long deletedRows) {
        static RunOverviewRow empty() { return new RunOverviewRow(0, 0, 0, 0, 0, 0, 0, 0, 0); }
    }

    record RunSearch(
            String view,
            String query,
            String statuses,
            String environmentCode,
            String definitionType,
            OffsetDateTime from,
            OffsetDateTime to,
            int page,
            int size,
            /** null = both; true = only schedule-triggered runs; false = only manually started runs. */
            Boolean scheduled) {
        RunSearch(String view, String query, String statuses, String environmentCode, String definitionType,
                OffsetDateTime from, OffsetDateTime to, int page, int size) {
            this(view, query, statuses, environmentCode, definitionType, from, to, page, size, null);
        }
    }

    record RunSummaryPage(List<RunSummaryRow> items, long total, int page, int size) {
    }

    record RunStepRow(
            UUID uuid,
            UUID parentUuid,
            String code,
            String type,
            int ordinal,
            String name,
            String status,
            String connectionRole,
            String risk,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long rowCount,
            Long byteCount,
            String errorCode,
            String logCounter,
            String transactionState,
            /** Package step: the child run that executed the step's object; null for other step kinds. */
            UUID childRunUuid) {
        RunStepRow(UUID uuid, UUID parentUuid, String code, String type, int ordinal,
                String name, String status, String connectionRole, String risk,
                OffsetDateTime startedAt, OffsetDateTime finishedAt, Long rowCount,
                Long byteCount, String errorCode) {
            this(uuid, parentUuid, code, type, ordinal, name, status, connectionRole, risk,
                    startedAt, finishedAt, rowCount, byteCount, errorCode, null, "UNCONFIRMED");
        }
        RunStepRow(UUID uuid, UUID parentUuid, String code, String type, int ordinal,
                String name, String status, String connectionRole, String risk,
                OffsetDateTime startedAt, OffsetDateTime finishedAt, Long rowCount,
                Long byteCount, String errorCode, String logCounter, String transactionState) {
            this(uuid, parentUuid, code, type, ordinal, name, status, connectionRole, risk,
                    startedAt, finishedAt, rowCount, byteCount, errorCode, logCounter, transactionState, null);
        }
    }

    record StartResult(RunRow run, boolean created) {
    }
}

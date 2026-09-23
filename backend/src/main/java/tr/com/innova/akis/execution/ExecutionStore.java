package tr.com.innova.akis.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.IdempotencyReservation;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;
import tr.com.innova.akis.execution.ExecutionModels.RunEventRow;
import tr.com.innova.akis.execution.ExecutionModels.RunEventPage;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.RunSearch;
import tr.com.innova.akis.execution.ExecutionModels.RunSummaryPage;
import tr.com.innova.akis.execution.ExecutionModels.RunStepRow;

interface ExecutionStore {

    boolean projectExists(UUID projectUuid);

    Optional<Long> findProjectId(UUID projectUuid);

    Optional<Actor> findActiveActor(String provider, String subject);

    default Optional<Actor> findActiveActor(long userId) {
        return Optional.empty();
    }

    Optional<PublicationContext> lockPublication(UUID projectUuid, UUID publicationUuid);

    boolean reserveIdempotency(
            long projectId,
            long actorId,
            String scope,
            String keyHash,
            String requestHash,
            UUID reservationUuid);

    Optional<IdempotencyReservation> lockIdempotency(
            long projectId, long actorId, String scope, String keyHash);

    RunRow createQueuedRun(
            PublicationContext publication,
            Actor actor,
            String requestHash,
            UUID jobRequestUuid,
            UUID runUuid,
            UUID stateUuid,
            UUID eventUuid);

    /** Job parameters (e.g. batchRows) are pinned on the request and read by the worker; null means defaults. */
    default RunRow createQueuedRun(
            PublicationContext publication,
            Actor actor,
            String requestHash,
            UUID jobRequestUuid,
            UUID runUuid,
            UUID stateUuid,
            UUID eventUuid,
            Integer batchRows) {
        return createQueuedRun(publication, actor, requestHash, jobRequestUuid, runUuid, stateUuid, eventUuid);
    }

    /** Attributes the job request to the schedule occurrence that fired it (see V049); null scheduleId means a manual run. */
    default RunRow createQueuedRun(
            PublicationContext publication,
            Actor actor,
            String requestHash,
            UUID jobRequestUuid,
            UUID runUuid,
            UUID stateUuid,
            UUID eventUuid,
            Integer batchRows,
            Long scheduleId,
            java.time.OffsetDateTime plannedAt) {
        return createQueuedRun(publication, actor, requestHash, jobRequestUuid, runUuid, stateUuid, eventUuid, batchRows);
    }

    void completeIdempotency(long reservationId, long jobRequestId, RunRow run);

    Optional<RunRow> findByJobRequestId(long projectId, long jobRequestId);

    Optional<RunRow> find(UUID projectUuid, UUID runUuid);

    Optional<RunRow> lock(UUID projectUuid, UUID runUuid);

    List<RunRow> list(UUID projectUuid);

    RunSummaryPage search(UUID projectUuid, RunSearch search);

    List<RunEventRow> listEvents(UUID projectUuid, UUID runUuid);

    RunEventPage listEvents(UUID projectUuid, UUID runUuid, long after, int size);

    List<RunStepRow> listSteps(UUID projectUuid, UUID runUuid);

    default boolean hasCompleteInputSnapshot(UUID projectUuid, UUID runUuid) {
        return false;
    }

    RunRow cancelQueued(RunRow run, Actor actor, UUID eventUuid);

    /**
     * Operator intervention: the run's quarantined target (ASKIDA after a reconciliation conflict) goes back to BOS with a
     * new generation, and the run closes as BASARISIZ so a restart becomes possible. Returns false when nothing was quarantined.
     */
    default boolean releaseQuarantinedTarget(RunRow run, Actor actor, String reason) { return false; }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.IdempotencyReservation;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;
import tr.com.innova.akis.execution.ExecutionModels.RunEventRow;
import tr.com.innova.akis.execution.ExecutionModels.RunEventPage;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.RunSearch;
import tr.com.innova.akis.execution.ExecutionModels.RunSummaryPage;
import tr.com.innova.akis.execution.ExecutionModels.RunStepRow;
import tr.com.innova.akis.execution.ExecutionModels.StartResult;
import tr.com.innova.akis.metadata.ApiException;

class ExecutionServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID PUBLICATION_UUID = UUID.randomUUID();
    private static final UUID OTHER_PUBLICATION_UUID = UUID.randomUUID();
    private static final Actor ACTOR = new Actor(7, UUID.randomUUID(), "Runner");

    @Test
    void createsOneQueuedRunAndReplaysTheSameIdempotentRequest() {
        FakeStore store = new FakeStore();
        ExecutionService service = service(store, true, ignored -> { });

        StartResult first = service.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0001", ACTOR);
        StartResult replay = service.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0001", ACTOR);

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(first.run().runUuid(), replay.run().runUuid());
        assertEquals("BEKLIYOR", first.run().status());
        assertEquals("a".repeat(64), first.run().releaseHash());
        assertEquals("b".repeat(64), first.run().planHash());
        assertEquals(1, first.run().lastEventNumber());
        assertEquals(1, store.createdRuns);
        assertEquals(1, store.completedReservations);
    }

    @Test
    void rejectsChangedRequestForTheSameIdempotencyKey() {
        FakeStore store = new FakeStore();
        ExecutionService service = service(store, true, ignored -> { });
        service.start(PROJECT_UUID, PUBLICATION_UUID, "request-key-0002", ACTOR);

        ApiException error = assertThrows(ApiException.class, () -> service.start(
                PROJECT_UUID, OTHER_PUBLICATION_UUID, "request-key-0002", ACTOR));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code());
        assertEquals(1, store.createdRuns);
    }

    @Test
    void replayStillReturnsTheExistingRunAfterPublicationIsSuspended() {
        FakeStore store = new FakeStore();
        ExecutionService service = service(store, true, ignored -> { });
        StartResult first = service.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0003", ACTOR);
        store.publicationStatus = "ASKIDA";

        StartResult replay = service.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0003", ACTOR);

        assertFalse(replay.created());
        assertEquals(first.run().runUuid(), replay.run().runUuid());
    }

    @Test
    void rejectsNewRunForInactivePublication() {
        FakeStore store = new FakeStore();
        store.publicationStatus = "ONAY_BEKLIYOR";
        ExecutionService service = service(store, true, ignored -> { });

        ApiException error = assertThrows(ApiException.class, () -> service.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0004", ACTOR));

        assertEquals("PUBLICATION_NOT_ACTIVE", error.code());
        assertEquals(0, store.createdRuns);
    }

    @Test
    void requiresAdditionalPermissionForProductionPublication() {
        FakeStore store = new FakeStore();
        store.environmentRisk = "URETIM";
        List<UUID> checkedProjects = new ArrayList<>();
        ExecutionService service = service(store, true, checkedProjects::add);

        service.start(PROJECT_UUID, PUBLICATION_UUID, "request-key-0005", ACTOR);

        assertEquals(List.of(PROJECT_UUID), checkedProjects);
    }

    @Test
    void featureFlagAndIdempotencyKeyFailClosed() {
        FakeStore store = new FakeStore();
        ExecutionService disabled = service(store, false, ignored -> { });
        ApiException featureError = assertThrows(ApiException.class, () -> disabled.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0006", ACTOR));

        ExecutionService enabled = service(store, true, ignored -> { });
        ApiException keyError = assertThrows(ApiException.class, () -> enabled.start(
                PROJECT_UUID, PUBLICATION_UUID, null, ACTOR));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, featureError.status());
        assertEquals("EXECUTION_REQUESTS_DISABLED", featureError.code());
        assertEquals(HttpStatus.BAD_REQUEST, keyError.status());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", keyError.code());
    }

    @Test
    void cancelsOnlyQueuedRunAndRepeatedCancelDoesNotAppendAnotherEvent() {
        FakeStore store = new FakeStore();
        ExecutionService service = service(store, true, ignored -> { });
        RunRow queued = service.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0007", ACTOR).run();

        RunRow cancelled = service.cancel(PROJECT_UUID, queued.runUuid(), ACTOR);
        RunRow replay = service.cancel(PROJECT_UUID, queued.runUuid(), ACTOR);

        assertEquals("IPTAL", cancelled.status());
        assertEquals(2, cancelled.lastEventNumber());
        assertEquals(cancelled.runUuid(), replay.runUuid());
        assertEquals(1, store.cancelCount);
    }

    @Test
    void rejectsCancellationAfterWorkerHasClaimedRun() {
        FakeStore store = new FakeStore();
        ExecutionService service = service(store, true, ignored -> { });
        RunRow queued = service.start(
                PROJECT_UUID, PUBLICATION_UUID, "request-key-0008", ACTOR).run();
        store.run = store.withStatus(queued, "HAZIRLANIYOR");

        ApiException error = assertThrows(ApiException.class,
                () -> service.cancel(PROJECT_UUID, queued.runUuid(), ACTOR));

        assertEquals("RUN_NOT_CANCELLABLE", error.code());
        assertEquals(0, store.cancelCount);
    }

    private ExecutionService service(
            FakeStore store,
            boolean acceptManualRequests,
            ExecutionPermissionGate permissionGate) {
        return new ExecutionService(
                store, permissionGate,
                new ExecutionFeatureFlags(acceptManualRequests, false, false),
                new RunStateMachine());
    }

    private static final class FakeStore implements ExecutionStore {

        private final Map<String, MutableReservation> reservations = new LinkedHashMap<>();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private String publicationStatus = "AKTIF";
        private String environmentRisk = "DUSUK";
        private RunRow run;
        private int createdRuns;
        private int completedReservations;
        private int cancelCount;

        @Override
        public boolean projectExists(UUID projectUuid) {
            return PROJECT_UUID.equals(projectUuid);
        }

        @Override
        public Optional<Long> findProjectId(UUID projectUuid) {
            return PROJECT_UUID.equals(projectUuid) ? Optional.of(10L) : Optional.empty();
        }

        @Override
        public Optional<Actor> findActiveActor(String provider, String subject) {
            return Optional.of(ACTOR);
        }

        @Override
        public Optional<PublicationContext> lockPublication(
                UUID projectUuid, UUID publicationUuid) {
            if (!PROJECT_UUID.equals(projectUuid)) {
                return Optional.empty();
            }
            return Optional.of(new PublicationContext(
                    10, PUBLICATION_UUID.equals(publicationUuid) ? 20 : 21,
                    publicationUuid, publicationStatus, environmentRisk,
                    "a".repeat(64), "b".repeat(64),
                    objectMapper.createObjectNode().put("manifestVersion", 1)));
        }

        @Override
        public boolean reserveIdempotency(
                long projectId,
                long actorId,
                String scope,
                String keyHash,
                String requestHash,
                UUID reservationUuid) {
            if (reservations.containsKey(keyHash)) {
                return false;
            }
            reservations.put(keyHash, new MutableReservation(
                    reservations.size() + 1, requestHash, null));
            return true;
        }

        @Override
        public Optional<IdempotencyReservation> lockIdempotency(
                long projectId, long actorId, String scope, String keyHash) {
            MutableReservation value = reservations.get(keyHash);
            return value == null ? Optional.empty() : Optional.of(
                    new IdempotencyReservation(value.id, value.requestHash, value.jobRequestId));
        }

        @Override
        public RunRow createQueuedRun(
                PublicationContext publication,
                Actor actor,
                String requestHash,
                UUID jobRequestUuid,
                UUID runUuid,
                UUID stateUuid,
                UUID eventUuid) {
            createdRuns++;
            run = new RunRow(
                    30, 40, 50, jobRequestUuid, runUuid, publication.publicationUuid(),
                    1, "ILK", "BEKLIYOR", publication.releaseHash(),
                    publication.planHash(), 1,
                    OffsetDateTime.now(), null, null, null);
            return run;
        }

        @Override
        public void completeIdempotency(long reservationId, long jobRequestId, RunRow run) {
            completedReservations++;
            reservations.values().stream()
                    .filter(value -> value.id == reservationId)
                    .findFirst()
                    .orElseThrow()
                    .jobRequestId = jobRequestId;
        }

        @Override
        public Optional<RunRow> findByJobRequestId(long projectId, long jobRequestId) {
            return run != null && run.jobRequestId() == jobRequestId
                    ? Optional.of(run) : Optional.empty();
        }

        @Override
        public Optional<RunRow> find(UUID projectUuid, UUID runUuid) {
            return run != null && PROJECT_UUID.equals(projectUuid) && run.runUuid().equals(runUuid)
                    ? Optional.of(run) : Optional.empty();
        }

        @Override
        public Optional<RunRow> lock(UUID projectUuid, UUID runUuid) {
            return find(projectUuid, runUuid);
        }

        @Override
        public List<RunRow> list(UUID projectUuid) {
            return run == null ? List.of() : List.of(run);
        }

        @Override
        public RunSummaryPage search(UUID projectUuid, RunSearch search) {
            return new RunSummaryPage(List.of(), 0, search.page(), search.size());
        }

        @Override
        public List<RunEventRow> listEvents(UUID projectUuid, UUID runUuid) {
            return run == null ? List.of() : List.of(new RunEventRow(
                    UUID.randomUUID(), 1, "RUN_REQUESTED", OffsetDateTime.now(),
                    objectMapper.createObjectNode()));
        }

        @Override
        public RunEventPage listEvents(UUID projectUuid, UUID runUuid, long after, int size) {
            return new RunEventPage(listEvents(projectUuid, runUuid), null, false);
        }

        @Override
        public List<RunStepRow> listSteps(UUID projectUuid, UUID runUuid) {
            return List.of();
        }

        @Override
        public RunRow cancelQueued(RunRow source, Actor actor, UUID eventUuid) {
            cancelCount++;
            run = new RunRow(
                    source.jobRequestId(), source.runId(), source.stateId(),
                    source.jobRequestUuid(), source.runUuid(), source.publicationUuid(),
                    source.attemptNumber(), source.startType(), "IPTAL",
                    source.releaseHash(), source.planHash(), 2,
                    source.createdAt(), source.startedAt(), OffsetDateTime.now(),
                    source.cancellationRequestedAt());
            return run;
        }

        private RunRow withStatus(RunRow source, String status) {
            return new RunRow(
                    source.jobRequestId(), source.runId(), source.stateId(),
                    source.jobRequestUuid(), source.runUuid(), source.publicationUuid(),
                    source.attemptNumber(), source.startType(), status,
                    source.releaseHash(), source.planHash(),
                    source.lastEventNumber(), source.createdAt(), source.startedAt(),
                    source.finishedAt(), source.cancellationRequestedAt());
        }

        private static final class MutableReservation {
            private final long id;
            private final String requestHash;
            private Long jobRequestId;

            private MutableReservation(long id, String requestHash, Long jobRequestId) {
                this.id = id;
                this.requestHash = requestHash;
                this.jobRequestId = jobRequestId;
            }
        }
    }
}

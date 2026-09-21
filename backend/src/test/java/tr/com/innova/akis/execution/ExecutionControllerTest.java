package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tr.com.innova.akis.security.PermissionCodes.RUN_CANCEL;
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;
import static tr.com.innova.akis.security.PermissionCodes.RUN_START;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
import tr.com.innova.akis.security.AuthorizationService;

class ExecutionControllerTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID PUBLICATION_UUID = UUID.randomUUID();
    private static final UUID RUN_UUID = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "runner", "ignored", List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void startUsesRunStartPermissionAndReturnsCreatedResource() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        ExecutionController controller = controller(authorization);

        var response = controller.start(
                PROJECT_UUID, "request-key-1000",
                new ExecutionController.StartRunRequest(PUBLICATION_UUID));

        assertEquals(RUN_START, authorization.permission);
        assertEquals(PROJECT_UUID, authorization.projectUuid);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(RUN_UUID, response.getBody().runUuid());
    }

    @Test
    void readSurfacesUseRunReadPermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        ExecutionController controller = controller(authorization);

        controller.list(PROJECT_UUID);
        assertEquals(RUN_READ, authorization.permission);
        controller.search(PROJECT_UUID, "RECENT", null, null, null, null,
                null, null, 0, 50);
        assertEquals(RUN_READ, authorization.permission);
        controller.get(PROJECT_UUID, RUN_UUID);
        assertEquals(RUN_READ, authorization.permission);
        controller.events(PROJECT_UUID, RUN_UUID);
        assertEquals(RUN_READ, authorization.permission);
        controller.eventPage(PROJECT_UUID, RUN_UUID, 0, 100);
        assertEquals(RUN_READ, authorization.permission);
        controller.steps(PROJECT_UUID, RUN_UUID);
        assertEquals(RUN_READ, authorization.permission);
    }

    @Test
    void cancelUsesRunCancelPermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        ExecutionController controller = controller(authorization);

        controller.cancel(PROJECT_UUID, RUN_UUID);

        assertEquals(RUN_CANCEL, authorization.permission);
        assertEquals(PROJECT_UUID, authorization.projectUuid);
    }

    private ExecutionController controller(CapturingAuthorization authorization) {
        ActorOnlyStore actorStore = new ActorOnlyStore();
        return new ExecutionController(
                new StubExecutionService(), new RunActorResolver(actorStore), authorization,
                new ExecutionFeatureFlags(true, false, false));
    }

    private static RunRow run() {
        return new RunRow(
                1, 2, 3, UUID.randomUUID(), RUN_UUID, PUBLICATION_UUID,
                1, "ILK", "BEKLIYOR", "a".repeat(64), "b".repeat(64), 1,
                OffsetDateTime.now(), null, null, null);
    }

    private static final class StubExecutionService extends ExecutionService {

        private StubExecutionService() {
            super(null, null, new ExecutionFeatureFlags(true, false, false), new RunStateMachine());
        }

        @Override
        StartResult start(
                UUID projectUuid,
                UUID publicationUuid,
                String idempotencyKey,
                Actor actor,
                Integer batchRows) {
            return new StartResult(run(), true);
        }

        @Override
        List<RunRow> list(UUID projectUuid) {
            return List.of(run());
        }

        @Override
        RunSummaryPage search(UUID projectUuid, RunSearch search) {
            return new RunSummaryPage(List.of(), 0, search.page(), search.size());
        }

        @Override
        RunRow get(UUID projectUuid, UUID runUuid) {
            return run();
        }

        @Override
        List<RunEventRow> events(UUID projectUuid, UUID runUuid) {
            return List.of();
        }

        @Override
        RunEventPage events(UUID projectUuid, UUID runUuid, long after, int size) {
            return new RunEventPage(List.of(), null, false);
        }

        @Override
        List<RunStepRow> steps(UUID projectUuid, UUID runUuid) {
            return List.of();
        }

        @Override
        RunRow cancel(UUID projectUuid, UUID runUuid, Actor actor) {
            return run();
        }
    }

    private static final class CapturingAuthorization extends AuthorizationService {

        private UUID projectUuid;
        private String permission;

        private CapturingAuthorization() {
            super(null, "fail-closed");
        }

        @Override
        public void requireProjectPermission(UUID projectUuid, String permissionCode) {
            this.projectUuid = projectUuid;
            this.permission = permissionCode;
        }
    }

    private static final class ActorOnlyStore implements ExecutionStore {

        @Override
        public Optional<Actor> findActiveActor(String provider, String subject) {
            return Optional.of(new Actor(1, UUID.randomUUID(), "Runner"));
        }

        @Override public boolean projectExists(UUID projectUuid) { throw unsupported(); }
        @Override public Optional<Long> findProjectId(UUID projectUuid) { throw unsupported(); }
        @Override public Optional<PublicationContext> lockPublication(UUID p, UUID y) { throw unsupported(); }
        @Override public boolean reserveIdempotency(long p, long a, String s, String k, String r, UUID u) { throw unsupported(); }
        @Override public Optional<IdempotencyReservation> lockIdempotency(long p, long a, String s, String k) { throw unsupported(); }
        @Override public RunRow createQueuedRun(PublicationContext p, Actor a, String h, UUID j, UUID r, UUID s, UUID e) { throw unsupported(); }
        @Override public void completeIdempotency(long i, long j, RunRow r) { throw unsupported(); }
        @Override public Optional<RunRow> findByJobRequestId(long p, long j) { throw unsupported(); }
        @Override public Optional<RunRow> find(UUID p, UUID r) { throw unsupported(); }
        @Override public Optional<RunRow> lock(UUID p, UUID r) { throw unsupported(); }
        @Override public List<RunRow> list(UUID p) { throw unsupported(); }
        @Override public RunSummaryPage search(UUID p, RunSearch s) { throw unsupported(); }
        @Override public List<RunEventRow> listEvents(UUID p, UUID r) { throw unsupported(); }
        @Override public RunEventPage listEvents(UUID p, UUID r, long a, int s) { throw unsupported(); }
        @Override public List<RunStepRow> listSteps(UUID p, UUID r) { throw unsupported(); }
        @Override public RunRow cancelQueued(RunRow r, Actor a, UUID e) { throw unsupported(); }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException();
        }
    }
}

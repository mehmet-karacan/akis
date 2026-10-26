package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.RunLeasePort.*;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.SessionPurpose;

class ProcedureCancellationCompletionTest {
    private static final Duration LEASE = Duration.ofSeconds(60);

    @Test
    void acknowledgesUsingClaimedIdentityAfterRuntimeReturnsAndSessionCloses() throws Exception {
        Fixture fixture = new Fixture();
        var result = fixture.worker.runOnce(fixture.identity, LEASE);

        assertEquals(new ProcedureWorkerOrchestrator.FailedSafely("KM_EXECUTION_REJECTED"), result);
        assertEquals(1, fixture.port.completions);
        assertSame(fixture.port.token, fixture.port.completedToken);
        assertTrue(fixture.port.closedAtCompletion);
        verify(fixture.connection).close();
    }

    @Test
    void acknowledgesAfterRuntimeExceptionAndCleanupWithoutMaskingWorkerResult() throws Exception {
        Fixture fixture = new Fixture();
        fixture.runtimeFailure = true;
        fixture.port.completionFailure = true;

        assertEquals(new ProcedureWorkerOrchestrator.StoppedFailClosed("USER_CANCEL"),
                fixture.worker.runOnce(fixture.identity, LEASE));
        assertEquals(3, fixture.port.completions, "Transient failures must have a bounded retry budget");
        assertTrue(fixture.port.closedAtCompletion);
    }

    @Test
    void transientAcknowledgementFailureRetriesAfterCleanupWithoutReenteringRuntime() throws Exception {
        Fixture fixture = new Fixture();
        fixture.port.completionFailuresRemaining = 1;
        assertInstanceOf(ProcedureWorkerOrchestrator.FailedSafely.class,
                fixture.worker.runOnce(fixture.identity, LEASE));
        assertEquals(2, fixture.port.completions);
        assertTrue(fixture.port.closedAtCompletion);
        assertEquals(1, fixture.port.heartbeats);
        verify(fixture.staged, times(1)).run(any(), any());
    }

    @Test
    void packageChildDispatchAcknowledgesAfterItsSessionClosesAndBeforeParentReturns() throws Exception {
        Fixture fixture = new Fixture(true);
        assertInstanceOf(ProcedureWorkerOrchestrator.FailedSafely.class,
                fixture.worker.runOnce(fixture.identity, LEASE));
        assertEquals(2, fixture.port.completedTokens.size());
        assertEquals(fixture.childToken, fixture.port.completedTokens.getFirst());
        assertEquals(fixture.port.token, fixture.port.completedTokens.getLast());
        assertTrue(fixture.port.closedAtCompletion);
    }

    @Test
    void rejectedAcknowledgementDoesNotReenterRuntimeOrRenewAuthority() throws Exception {
        Fixture fixture = new Fixture();
        fixture.port.acceptCompletion = false;

        assertInstanceOf(ProcedureWorkerOrchestrator.FailedSafely.class,
                fixture.worker.runOnce(fixture.identity, LEASE));
        assertEquals(1, fixture.port.completions);
        assertEquals(1, fixture.port.heartbeats);
        verify(fixture.staged, times(1)).run(any(), any());
    }

    @Test
    void idleWorkerDoesNotAcknowledgeAnUnclaimedRun() throws Exception {
        Fixture fixture = new Fixture();
        fixture.port.claim = false;

        assertInstanceOf(ProcedureWorkerOrchestrator.Idle.class,
                fixture.worker.runOnce(fixture.identity, LEASE));
        assertEquals(0, fixture.port.completions);
        verifyNoInteractions(fixture.staged);
    }

    private static final class Fixture {
        final AtomicBoolean closed = new AtomicBoolean();
        final FakeLeasePort port = new FakeLeasePort(closed);
        final WorkerIdentity identity = new WorkerIdentity("worker-cancel", UUID.randomUUID());
        final Connection connection = mock(Connection.class);
        final StagedWorkerOrchestrator staged = mock(StagedWorkerOrchestrator.class);
        final ProcedureWorkerOrchestrator worker;
        final RunLeaseToken childToken = new RunLeaseToken(UUID.randomUUID(), "worker-cancel", 8,
                OffsetDateTime.now().plusSeconds(60));
        boolean runtimeFailure;

        Fixture() throws Exception {
            this(false);
        }

        Fixture(boolean packageParent) throws Exception {
            doAnswer(invocation -> { closed.set(true); return null; }).when(connection).close();
            var constructor = RuntimeOracleSession.class.getDeclaredConstructor(
                    Connection.class, SessionPurpose.class, int.class);
            constructor.setAccessible(true);
            RuntimeOracleSession session = constructor.newInstance(connection, SessionPurpose.SOURCE_READ, 30);
            doAnswer(invocation -> {
                try (session) {
                    LeaseGate gate = invocation.getArgument(1);
                    // Cancellation rejects renewal and closes the work gate before runtime unwinds.
                    assertEquals(LeaseGateException.Failure.CANCELLATION_REQUESTED,
                            assertThrows(LeaseGateException.class, gate::checkpoint).failure());
                    if (runtimeFailure) throw new LeaseGateException(LeaseGateException.Failure.CANCELLATION_REQUESTED);
                    return new ProcedureWorkerOrchestrator.FailedSafely("KM_EXECUTION_REJECTED");
                }
            }).when(staged).run(any(), any());
            ObjectMapper mapper = new ObjectMapper();
            PinnedExecutionContextPort contexts = run -> Optional.of(new PinnedExecutionContextPort.PinnedExecutionContext(
                    UUID.randomUUID(), run, UUID.randomUUID(), 1, "a".repeat(64), "b".repeat(64),
                    mapper.createObjectNode(), mapper.createObjectNode().put(
                            "runtimeCapability", packageParent && run.equals(port.token.runUuid())
                                    ? "ORACLE_PACKAGE_V1" : StagedRuntimePlanResolver.CAPABILITY)));
            worker = new ProcedureWorkerOrchestrator(new WorkerLeaseService(port), contexts,
                    mock(ProcedureRuntimePlanResolver.class), mock(JdbcPinnedSchemaSnapshotStore.class),
                    mock(RuntimeOracleConnectionProvider.class), mapper, mock(RunExecutionTransitionPort.class),
                    mock(JdbcProcedureExecutionJournalStore.class), mock(ProcedureTaskExecutorSessionFactory.class));
            worker.configureStagedWorker(staged);
            if (packageParent) {
                PackageWorkerOrchestrator packages = mock(PackageWorkerOrchestrator.class);
                doAnswer(invocation -> {
                    PackageWorkerOrchestrator.ChildRunner children = invocation.getArgument(5);
                    var child = new ClaimedRun(childToken, "a".repeat(64), "b".repeat(64));
                    try (var childGate = new HeartbeatSupervisor(new WorkerLeaseService(port), childToken, LEASE,
                            (task, delay, interval) -> () -> { })) {
                        var result = children.run(child, childGate);
                        assertTrue(port.completedTokens.contains(childToken),
                                "The child must acknowledge before control returns to its package");
                        return result;
                    }
                }).when(packages).run(any(), any(), any(), any(), any(), any());
                worker.configurePackageWorker(packages);
            }
        }
    }

    private static final class FakeLeasePort implements RunLeasePort {
        final RunLeaseToken token = new RunLeaseToken(UUID.randomUUID(), "worker-cancel", 7,
                OffsetDateTime.now().minusSeconds(60));
        final AtomicBoolean closed;
        boolean claim = true;
        boolean acceptCompletion = true;
        boolean completionFailure;
        int completionFailuresRemaining;
        final List<RunLeaseToken> completedTokens = new ArrayList<>();
        int completions;
        int heartbeats;
        boolean closedAtCompletion;
        RunLeaseToken completedToken;

        FakeLeasePort(AtomicBoolean closed) { this.closed = closed; }

        public Optional<ClaimedRun> claimForPreflight(WorkerIdentity worker, Duration lease) {
            return claim ? Optional.of(new ClaimedRun(token, "a".repeat(64), "b".repeat(64))) : Optional.empty();
        }
        public HeartbeatResult heartbeat(RunLeaseToken token, Duration lease) {
            heartbeats++;
            return HeartbeatResult.rejected();
        }
        public boolean cancellationRequested(RunLeaseToken token) { return true; }
        @Override
        public boolean completeCancellation(RunLeaseToken token) {
            completions++;
            completedToken = token;
            completedTokens.add(token);
            closedAtCompletion = closed.get();
            if (completionFailure || completionFailuresRemaining-- > 0) throw new IllegalStateException("Sensitive JDBC detail");
            return acceptCompletion;
        }
        public TargetFenceToken acquireTarget(RunLeaseToken token, String hash, int version) {
            throw new AssertionError("No work authorized after cancellation");
        }
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.HeartbeatSupervisor.ScheduledTask;
import tr.com.innova.akis.execution.HeartbeatSupervisor.Scheduler;
import tr.com.innova.akis.execution.LeaseGateException.Failure;
import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatResult;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.SessionPurpose;

class HeartbeatSupervisorTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Test
    void rejectedRenewalStillInterruptsCancelledSourceAndStagingSessions() throws Exception {
        RunLeaseToken initial = token(4, 60);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.rejected());
        port.cancelRequested = true;
        RuntimeOperationRegistry operations = new RuntimeOperationRegistry();
        CountDownLatch interrupted = new CountDownLatch(3);
        for (SessionPurpose purpose : List.of(
                SessionPurpose.SOURCE_READ, SessionPurpose.WORK_CONTROL, SessionPurpose.WORK_DATA)) {
            Connection connection = mock(Connection.class);
            doAnswer(invocation -> {
                interrupted.countDown();
                return null;
            }).when(connection).abort(org.mockito.ArgumentMatchers.any());
            operations.register(initial.runUuid(), initial.generation(),
                    session(connection, purpose));
        }
        FakeScheduler scheduler = new FakeScheduler();
        try (HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port, operations), initial, LEASE, scheduler)) {
            assertEquals(Failure.CANCELLATION_REQUESTED,
                    assertThrows(LeaseGateException.class, scheduler::tick).failure());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "All registered JDBC sessions must be interrupted");
            assertEquals(List.of(initial), port.cancellationTokens);
            assertEquals(1, scheduler.cancelCount);
            assertEquals(Failure.CANCELLATION_REQUESTED,
                    assertThrows(LeaseGateException.class, supervisor::checkpoint).failure());
            assertEquals(Failure.CANCELLATION_REQUESTED,
                    assertThrows(LeaseGateException.class,
                            () -> supervisor.execute(token -> { throw new AssertionError("Work resumed"); })).failure());
        }
    }

    @Test
    void acceptedRenewalCancellationClosesGateBeforeSlowJdbcAbortFinishes() throws Exception {
        RunLeaseToken initial = token(4, 60);
        RunLeaseToken refreshed = token(4, 70);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.accepted(refreshed));
        port.cancelRequested = true;
        RuntimeOperationRegistry operations = new RuntimeOperationRegistry();
        CountDownLatch abortStarted = new CountDownLatch(1);
        CountDownLatch releaseAbort = new CountDownLatch(1);
        Connection connection = mock(Connection.class);
        doAnswer(invocation -> {
            abortStarted.countDown();
            releaseAbort.await(10, TimeUnit.SECONDS);
            return null;
        }).when(connection).abort(org.mockito.ArgumentMatchers.any());
        operations.register(initial.runUuid(), initial.generation(),
                session(connection, SessionPurpose.SOURCE_READ));
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port, operations), initial, LEASE, scheduler);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Failure> cancellation = caller.submit(() ->
                    assertThrows(LeaseGateException.class, scheduler::tick).failure());
            assertTrue(abortStarted.await(2, TimeUnit.SECONDS));
            assertEquals(Failure.CANCELLATION_REQUESTED, cancellation.get(2, TimeUnit.SECONDS));
            Future<Failure> checkpoint = caller.submit(() ->
                    assertThrows(LeaseGateException.class, supervisor::checkpoint).failure());
            assertEquals(Failure.CANCELLATION_REQUESTED, checkpoint.get(2, TimeUnit.SECONDS));
            assertEquals(1, releaseAbort.getCount(), "The gate must close while JDBC abort is blocked");
            assertEquals(List.of(refreshed), port.cancellationTokens);
        }
        finally {
            releaseAbort.countDown();
            supervisor.close();
            caller.shutdownNow();
        }
    }

    @Test
    void rejectedRenewalWithoutOwnedCancellationRemainsAuthorityLost() {
        RunLeaseToken initial = token(4, 60);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.rejected());
        try (HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, new FakeScheduler())) {
            assertEquals(Failure.LEASE_AUTHORITY_LOST,
                    assertThrows(LeaseGateException.class, supervisor::checkpoint).failure());
            assertEquals(List.of(initial), port.cancellationTokens);
            assertEquals(Failure.LEASE_AUTHORITY_LOST,
                    assertThrows(LeaseGateException.class,
                            () -> supervisor.execute(token -> { throw new AssertionError("Work resumed"); })).failure());
        }
    }

    @Test
    void failedCancellationLookupAfterRejectedRenewalFailsClosed() {
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.rejected());
        port.cancellationFailure = new IllegalStateException("Cancellation lookup unavailable");
        try (HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), token(4, 60), LEASE, new FakeScheduler())) {
            assertEquals(Failure.LEASE_AUTHORITY_LOST,
                    assertThrows(LeaseGateException.class, supervisor::checkpoint).failure());
            assertEquals(1, port.cancellationTokens.size());
        }
    }

    @Test
    void periodicAndSynchronousHeartbeatsRetainFreshestExactToken() {
        RunLeaseToken initial = token(1, 60);
        RunLeaseToken periodic = token(1, 70);
        RunLeaseToken checkpoint = token(1, 80);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.accepted(periodic));
        port.answers.add(HeartbeatResult.accepted(checkpoint));
        FakeScheduler scheduler = new FakeScheduler();

        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, scheduler);

        assertEquals(Duration.ofSeconds(10), scheduler.initialDelay);
        assertEquals(Duration.ofSeconds(10), scheduler.interval);
        scheduler.tick();
        assertSame(checkpoint, supervisor.checkpoint());
        assertEquals(List.of(initial, periodic), port.receivedTokens);
        assertEquals(List.of(LEASE, LEASE), port.receivedLeases);

        supervisor.close();
        scheduler.tick();
        assertEquals(2, port.receivedTokens.size());
        assertEquals(1, scheduler.cancelCount);
    }

    @Test
    void firstHeartbeatFailureStopsScheduleAndFailsClosed() {
        RunLeaseToken initial = token(1, 60);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(new RuntimeException("sensitive database detail"));
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, scheduler);

        LeaseGateException scheduledFailure = assertThrows(
                LeaseGateException.class, scheduler::tick);
        assertEquals(Failure.LEASE_AUTHORITY_LOST, scheduledFailure.failure());
        assertEquals("Heartbeat lease authority was lost.", scheduledFailure.getMessage());
        assertEquals(1, scheduler.cancelCount);

        LeaseGateException checkpointFailure = assertThrows(
                LeaseGateException.class, supervisor::checkpoint);
        assertEquals(Failure.LEASE_AUTHORITY_LOST, checkpointFailure.failure());
        scheduler.tick();
        assertEquals(1, port.receivedTokens.size());
    }

    @Test
    void changedLeaseIdentityIsRejectedAndNeverBecomesCurrent() {
        RunLeaseToken initial = token(4, 60);
        RunLeaseToken wrongGeneration = token(5, 70);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.accepted(wrongGeneration));
        port.cancelRequested = true;
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, scheduler);

        LeaseGateException failure = assertThrows(
                LeaseGateException.class, supervisor::checkpoint);
        assertEquals(Failure.LEASE_AUTHORITY_LOST, failure.failure());
        assertTrue(port.cancellationTokens.isEmpty(), "An invalid refreshed identity cannot authorize cancellation");
        assertEquals(1, scheduler.cancelCount);
    }

    @Test
    void closeIsIdempotentAndCheckpointCannotRestartHeartbeat() {
        FakeLeasePort port = new FakeLeasePort();
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), token(1, 60), LEASE, scheduler);

        supervisor.close();
        supervisor.close();

        LeaseGateException failure = assertThrows(
                LeaseGateException.class, supervisor::checkpoint);
        assertEquals(Failure.CLOSED, failure.failure());
        assertEquals(1, scheduler.cancelCount);
        assertEquals(0, port.receivedTokens.size());
    }

    @Test
    void concurrentCheckpointsAreSerializedOverTheFreshestToken() throws Exception {
        RunLeaseToken initial = token(1, 60);
        RunLeaseToken firstRefresh = token(1, 70);
        RunLeaseToken secondRefresh = token(1, 80);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.accepted(firstRefresh));
        port.answers.add(HeartbeatResult.accepted(secondRefresh));
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, new FakeScheduler());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<RunLeaseToken> left = callers.submit(() -> {
                start.await();
                return supervisor.checkpoint();
            });
            Future<RunLeaseToken> right = callers.submit(() -> {
                start.await();
                return supervisor.checkpoint();
            });

            start.countDown();
            assertEquals(
                    Set.of(firstRefresh, secondRefresh),
                    Set.of(left.get(2, TimeUnit.SECONDS), right.get(2, TimeUnit.SECONDS)));
            assertEquals(List.of(initial, firstRefresh), port.receivedTokens);
        }
        finally {
            supervisor.close();
            callers.shutdownNow();
        }
    }

    @Test
    void shortAuthorityOperationSerializesAgainstScheduledHeartbeat() throws Exception {
        RunLeaseToken initial = token(1, 60);
        RunLeaseToken refreshed = token(1, 70);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.accepted(refreshed));
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, scheduler);
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch tickStarted = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<RunLeaseToken> operation = callers.submit(() -> supervisor.execute(token -> {
                operationStarted.countDown();
                try {
                    releaseOperation.await(2, TimeUnit.SECONDS);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException();
                }
                return token;
            }));
            operationStarted.await(2, TimeUnit.SECONDS);
            Future<?> heartbeat = callers.submit(() -> {
                tickStarted.countDown();
                scheduler.tick();
            });
            tickStarted.await(2, TimeUnit.SECONDS);

            assertFalse(heartbeat.isDone());
            assertEquals(0, port.receivedTokens.size());
            releaseOperation.countDown();
            assertSame(initial, operation.get(2, TimeUnit.SECONDS));
            heartbeat.get(2, TimeUnit.SECONDS);
            assertEquals(List.of(initial), port.receivedTokens);
        }
        finally {
            releaseOperation.countDown();
            supervisor.close();
            callers.shutdownNow();
        }
    }

    @Test
    void acceptedTerminalOperationLatchesBeforeWaitingHeartbeat() throws Exception {
        RunLeaseToken initial = token(1, 60);
        FakeLeasePort port = new FakeLeasePort();
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, scheduler);
        CountDownLatch terminalStarted = new CountDownLatch(1);
        CountDownLatch releaseTerminal = new CountDownLatch(1);
        CountDownLatch heartbeatDispatched = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> terminalCall = callers.submit(() -> supervisor.completeTerminal(
                    token -> {
                        assertSame(initial, token);
                        terminalStarted.countDown();
                        try {
                            releaseTerminal.await(2, TimeUnit.SECONDS);
                        }
                        catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException();
                        }
                        return "ACCEPTED";
                    }, "ACCEPTED"::equals));
            terminalStarted.await(2, TimeUnit.SECONDS);
            Future<?> heartbeat = callers.submit(
                    () -> scheduler.tick(heartbeatDispatched));
            heartbeatDispatched.await(2, TimeUnit.SECONDS);
            assertFalse(heartbeat.isDone());

            releaseTerminal.countDown();
            assertEquals("ACCEPTED", terminalCall.get(2, TimeUnit.SECONDS));
            heartbeat.get(2, TimeUnit.SECONDS);
            assertEquals(1, scheduler.cancelCount);
            assertEquals(0, port.receivedTokens.size());
            LeaseGateException terminal = assertThrows(
                    LeaseGateException.class, supervisor::checkpoint);
            assertEquals(Failure.TERMINAL, terminal.failure());
        }
        finally {
            releaseTerminal.countDown();
            supervisor.close();
            callers.shutdownNow();
        }
    }

    @Test
    void rejectedTerminalOperationLeavesGateActive() {
        RunLeaseToken initial = token(1, 60);
        RunLeaseToken refreshed = token(1, 70);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.accepted(refreshed));
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, scheduler);

        assertEquals("REJECTED", supervisor.completeTerminal(
                token -> "REJECTED", "ACCEPTED"::equals));
        assertEquals(0, scheduler.cancelCount);
        scheduler.tick();
        assertEquals(List.of(initial), port.receivedTokens);
        supervisor.close();
    }

    @Test
    void authorityOperationExceptionIsSanitizedAndFailsClosed() {
        FakeLeasePort port = new FakeLeasePort();
        FakeScheduler scheduler = new FakeScheduler();
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), token(1, 60), LEASE, scheduler);

        LeaseGateException failure = assertThrows(
                LeaseGateException.class,
                () -> supervisor.execute(token -> {
                    throw new RuntimeException("secret SQL and endpoint detail");
                }));

        assertEquals(Failure.AUTHORITY_OPERATION_UNCONFIRMED, failure.failure());
        assertEquals(
                "Lease-authorized PostgreSQL operation could not be confirmed.",
                failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, scheduler.cancelCount);
        assertEquals(Failure.AUTHORITY_OPERATION_UNCONFIRMED,
                assertThrows(LeaseGateException.class, supervisor::checkpoint).failure());
    }

    @Test
    void equalDeadlineIsKeptAfterConfirmedHeartbeatAndEarlierDeadlineIsRejected() {
        RunLeaseToken initial = token(1, 60);
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(HeartbeatResult.accepted(initial));
        port.answers.add(HeartbeatResult.accepted(token(1, 30)));
        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), initial, LEASE, new FakeScheduler());

        // Same deadline: the store confirmed the extension; clock granularity is not lost authority.
        assertEquals(initial.leaseDeadline(), supervisor.checkpoint().leaseDeadline());
        LeaseGateException failure = assertThrows(
                LeaseGateException.class, supervisor::checkpoint);
        assertEquals(Failure.LEASE_AUTHORITY_LOST, failure.failure());
    }

    @Test
    void invalidLeaseAndSchedulerFailureAreSanitized() {
        FakeLeasePort port = new FakeLeasePort();
        WorkerLeaseService service = new WorkerLeaseService(port);
        FakeScheduler scheduler = new FakeScheduler();

        LeaseGateException invalid = assertThrows(
                LeaseGateException.class,
                () -> new HeartbeatSupervisor(
                        service, token(1, 60), Duration.ofSeconds(29), scheduler));
        assertEquals(Failure.INVALID_CONTRACT, invalid.failure());

        Scheduler broken = (task, initialDelay, interval) -> {
            throw new RuntimeException("sensitive scheduler detail");
        };
        LeaseGateException start = assertThrows(
                LeaseGateException.class,
                () -> new HeartbeatSupervisor(service, token(1, 60), LEASE, broken));
        assertEquals(Failure.START_FAILED, start.failure());
        assertEquals("Heartbeat supervision could not be started.", start.getMessage());
    }

    @Test
    void immediateScheduledFailureCancelsTaskAfterRegistrationReturns() {
        FakeLeasePort port = new FakeLeasePort();
        port.answers.add(new RuntimeException("sensitive database detail"));
        ImmediateScheduler scheduler = new ImmediateScheduler();

        HeartbeatSupervisor supervisor = new HeartbeatSupervisor(
                new WorkerLeaseService(port), token(1, 60), LEASE, scheduler);

        assertEquals(1, scheduler.cancelCount);
        LeaseGateException failure = assertThrows(
                LeaseGateException.class, supervisor::checkpoint);
        assertEquals(Failure.LEASE_AUTHORITY_LOST, failure.failure());
        assertEquals(1, port.receivedTokens.size());
    }

    private RuntimeOracleSession session(Connection connection, SessionPurpose purpose) throws Exception {
        var constructor = RuntimeOracleSession.class.getDeclaredConstructor(
                Connection.class, SessionPurpose.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connection, purpose, 30);
    }

    private RunLeaseToken token(long generation, long deadlineOffsetSeconds) {
        return new RunLeaseToken(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                "worker-one",
                generation,
                OffsetDateTime.parse("2026-09-11T10:00:00Z")
                        .plusSeconds(deadlineOffsetSeconds));
    }

    private static final class FakeScheduler implements Scheduler {

        private Runnable task;
        private Duration initialDelay;
        private Duration interval;
        private boolean cancelled;
        private int cancelCount;

        @Override
        public ScheduledTask schedule(
                Runnable task, Duration initialDelay, Duration interval) {
            this.task = task;
            this.initialDelay = initialDelay;
            this.interval = interval;
            return () -> {
                if (!cancelled) {
                    cancelled = true;
                    cancelCount++;
                }
            };
        }

        void tick() {
            if (!cancelled) {
                task.run();
            }
        }

        void tick(CountDownLatch dispatched) {
            if (!cancelled) {
                dispatched.countDown();
                task.run();
            }
        }
    }

    private static final class ImmediateScheduler implements Scheduler {

        private int cancelCount;

        @Override
        public ScheduledTask schedule(
                Runnable task, Duration initialDelay, Duration interval) {
            try {
                task.run();
            }
            catch (LeaseGateException ignored) {
                // Mirrors an executor retaining task failure internally.
            }
            return () -> cancelCount++;
        }
    }

    private static final class FakeLeasePort implements RunLeasePort {

        private final Queue<Object> answers = new ArrayDeque<>();
        private final List<RunLeaseToken> receivedTokens = new ArrayList<>();
        private final List<Duration> receivedLeases = new ArrayList<>();
        private final List<RunLeaseToken> cancellationTokens = new ArrayList<>();
        private boolean cancelRequested;
        private RuntimeException cancellationFailure;

        @Override
        public boolean cancellationRequested(RunLeaseToken token) {
            cancellationTokens.add(token);
            if (cancellationFailure != null) throw cancellationFailure;
            return cancelRequested;
        }

        @Override
        public Optional<ClaimedRun> claimForPreflight(
                WorkerIdentity worker, Duration lease) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HeartbeatResult heartbeat(RunLeaseToken token, Duration lease) {
            receivedTokens.add(token);
            receivedLeases.add(lease);
            Object answer = answers.remove();
            if (answer instanceof RuntimeException exception) {
                throw exception;
            }
            return (HeartbeatResult) answer;
        }

        @Override
        public TargetFenceToken acquireTarget(
                RunLeaseToken token, String canonicalTargetHash, int identityVersion) {
            throw new UnsupportedOperationException();
        }
    }
}

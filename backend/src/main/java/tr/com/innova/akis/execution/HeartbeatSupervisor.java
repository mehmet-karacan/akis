package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import tr.com.innova.akis.execution.LeaseGateException.Failure;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;

/**
 * Periodically renews one run lease and provides serialized synchronous
 * checkpoints. It never starts a worker or polls for work.
 */
final class HeartbeatSupervisor implements LeaseGate {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HeartbeatSupervisor.class);

    private static final Duration MAXIMUM_INTERVAL = Duration.ofSeconds(10);
    private static final long MINIMUM_LEASE_SECONDS = 30;
    private static final long MAXIMUM_LEASE_SECONDS = 300;

    private final Object monitor = new Object();
    private final WorkerLeaseService leases;
    private final Duration lease;
    private final Scheduler scheduler;
    private final boolean ownsScheduler;

    private RunLeaseToken freshest;
    private ScheduledTask scheduledTask;
    private Failure failure;
    private boolean cancellationCompleted;
    private boolean terminal;
    private boolean closed;

    static HeartbeatSupervisor start(
            WorkerLeaseService leases, RunLeaseToken initialToken, Duration lease) {
        return new HeartbeatSupervisor(
                leases, initialToken, lease, new ExecutorScheduler(), true);
    }

    HeartbeatSupervisor(
            WorkerLeaseService leases,
            RunLeaseToken initialToken,
            Duration lease,
            Scheduler scheduler) {
        this(leases, initialToken, lease, scheduler, false);
    }

    private HeartbeatSupervisor(
            WorkerLeaseService leases,
            RunLeaseToken initialToken,
            Duration lease,
            Scheduler scheduler,
            boolean ownsScheduler) {
        this.leases = Objects.requireNonNull(leases, "Worker lease service is required.");
        this.freshest = requireToken(initialToken);
        this.lease = requireLease(lease);
        this.scheduler = Objects.requireNonNull(scheduler, "Heartbeat scheduler is required.");
        this.ownsScheduler = ownsScheduler;
        Duration interval = heartbeatInterval(this.lease);
        try {
            ScheduledTask task = scheduler.schedule(
                    this::scheduledHeartbeat, interval, interval);
            if (task == null) {
                throw new IllegalStateException();
            }
            synchronized (monitor) {
                scheduledTask = task;
                if (failure != null || closed) {
                    cancelLocked();
                }
            }
        }
        catch (RuntimeException exception) {
            closeOwnedScheduler();
            throw new LeaseGateException(Failure.START_FAILED);
        }
    }

    @Override
    public RunLeaseToken checkpoint() {
        synchronized (monitor) {
            requireOpenLocked();
            return refreshLocked();
        }
    }

    @Override
    public <T> T execute(AuthorizedOperation<T> operation) {
        if (operation == null) {
            throw new LeaseGateException(Failure.INVALID_CONTRACT);
        }
        synchronized (monitor) {
            requireOpenLocked();
            try {
                return operation.execute(freshest);
            }
            catch (RuntimeException exception) {
                failOperationLocked();
                throw new LeaseGateException(Failure.AUTHORITY_OPERATION_UNCONFIRMED);
            }
        }
    }

    @Override
    public <T> T completeTerminal(
            TerminalOperation<T> operation, Predicate<? super T> accepted) {
        if (operation == null || accepted == null) {
            throw new LeaseGateException(Failure.INVALID_CONTRACT);
        }
        synchronized (monitor) {
            requireOpenLocked();
            try {
                T result = operation.execute(freshest);
                if (accepted.test(result)) {
                    terminal = true;
                    cancelLocked();
                }
                return result;
            }
            catch (RuntimeException exception) {
                failOperationLocked();
                throw new LeaseGateException(Failure.AUTHORITY_OPERATION_UNCONFIRMED);
            }
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            cancelLocked();
        }
        closeOwnedScheduler();
    }

    private void scheduledHeartbeat() {
        synchronized (monitor) {
            if (closed || terminal || failure != null) {
                return;
            }
            refreshLocked();
        }
    }

    private RunLeaseToken refreshLocked() {
        try {
            // The store already confirmed the extension. A checkpoint landing right after the scheduled
            // heartbeat can read an identical deadline (clock_timestamp granularity on Windows hosts);
            // only a deadline that moved backwards is evidence of lost authority.
            RunLeaseToken refreshed = leases.heartbeatOrFail(freshest, lease);
            if (!sameLeaseIdentity(freshest, refreshed)
                    || refreshed.leaseDeadline() == null
                    || refreshed.leaseDeadline().isBefore(freshest.leaseDeadline())) {
                throw new IllegalStateException("Refreshed lease " + refreshed + " does not extend " + freshest);
            }
            freshest = refreshed;
            return freshest;
        }
        catch (RuntimeException exception) {
            LOG.warn("Heartbeat for run {} generation {} lost lease authority: {}{}", freshest.runUuid(), freshest.generation(),
                    exception, exception.getCause() == null ? "" : " <- " + exception.getCause());
            failure = Failure.LEASE_AUTHORITY_LOST;
            cancelLocked();
            throw new LeaseGateException(failure);
        }
    }

    private void requireOpenLocked() {
        if (closed) {
            throw new LeaseGateException(Failure.CLOSED);
        }
        if (terminal) {
            throw new LeaseGateException(Failure.TERMINAL);
        }
        if (failure != null) {
            throw new LeaseGateException(failure);
        }
    }

    private void failOperationLocked() {
        failure = Failure.AUTHORITY_OPERATION_UNCONFIRMED;
        cancelLocked();
    }

    private void cancelLocked() {
        if (scheduledTask == null || cancellationCompleted) {
            return;
        }
        cancellationCompleted = true;
        try {
            scheduledTask.cancel();
        }
        catch (RuntimeException ignored) {
            // The gate is already closed or failed; cancellation is best effort.
        }
    }

    private void closeOwnedScheduler() {
        if (!ownsScheduler) {
            return;
        }
        try {
            scheduler.close();
        }
        catch (RuntimeException ignored) {
            // The gate remains closed even if executor shutdown reports a failure.
        }
    }

    private boolean sameLeaseIdentity(RunLeaseToken expected, RunLeaseToken actual) {
        return actual != null
                && Objects.equals(expected.runUuid(), actual.runUuid())
                && Objects.equals(expected.workerReference(), actual.workerReference())
                && expected.generation() == actual.generation();
    }

    private RunLeaseToken requireToken(RunLeaseToken token) {
        if (token == null || token.runUuid() == null
                || token.workerReference() == null || token.workerReference().isBlank()
                || token.generation() <= 0 || token.leaseDeadline() == null) {
            throw new LeaseGateException(Failure.INVALID_CONTRACT);
        }
        return token;
    }

    private Duration requireLease(Duration candidate) {
        if (candidate == null || candidate.isNegative() || candidate.isZero()
                || candidate.getNano() != 0
                || candidate.getSeconds() < MINIMUM_LEASE_SECONDS
                || candidate.getSeconds() > MAXIMUM_LEASE_SECONDS) {
            throw new LeaseGateException(Failure.INVALID_CONTRACT);
        }
        return candidate;
    }

    private Duration heartbeatInterval(Duration lease) {
        Duration leaseThird = lease.dividedBy(3);
        return leaseThird.compareTo(MAXIMUM_INTERVAL) < 0
                ? leaseThird : MAXIMUM_INTERVAL;
    }

    interface Scheduler extends AutoCloseable {

        ScheduledTask schedule(Runnable task, Duration initialDelay, Duration interval);

        @Override
        default void close() {
        }
    }

    @FunctionalInterface
    interface ScheduledTask {

        void cancel();
    }

    private static final class ExecutorScheduler implements Scheduler {

        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "akis-run-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                });

        @Override
        public ScheduledTask schedule(
                Runnable task, Duration initialDelay, Duration interval) {
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                    task,
                    initialDelay.toNanos(),
                    interval.toNanos(),
                    TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}

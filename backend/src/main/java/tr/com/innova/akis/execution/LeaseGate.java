package tr.com.innova.akis.execution;

import java.util.function.Predicate;

import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;

/**
 * A fail-closed checkpoint over one exact worker lease.
 *
 * <p>This foundation only renews and serializes authority. Closing it never writes
 * a terminal run state; the future orchestrator must perform the appropriate typed
 * {@link RunExecutionTransitionPort} mutation through {@link #completeTerminal}
 * before closing the gate.
 */
interface LeaseGate extends AutoCloseable {

    /** Refreshes the lease synchronously and returns the freshest exact token. */
    RunLeaseToken checkpoint();

    /**
     * Runs one short PostgreSQL authority operation serialized with heartbeat.
     * Oracle/network work must never be placed inside this critical section.
     */
    <T> T execute(AuthorizedOperation<T> operation);

    /**
     * Runs one short PostgreSQL terminal operation. When {@code accepted}
     * returns true, terminal state is latched and heartbeat is cancelled before
     * this method releases the authority critical section.
     */
    <T> T completeTerminal(
            TerminalOperation<T> operation, Predicate<? super T> accepted);

    @Override
    void close();

    @FunctionalInterface
    interface AuthorizedOperation<T> {

        T execute(RunLeaseToken token);
    }

    @FunctionalInterface
    interface TerminalOperation<T> {

        T execute(RunLeaseToken token);
    }
}

final class LeaseGateException extends RuntimeException {

    private final Failure failure;

    LeaseGateException(Failure failure) {
        super(message(failure));
        this.failure = failure;
    }

    Failure failure() {
        return failure;
    }

    private static String message(Failure failure) {
        return switch (failure) {
            case INVALID_CONTRACT -> "Heartbeat lease gate contract is invalid.";
            case START_FAILED -> "Heartbeat supervision could not be started.";
            case LEASE_AUTHORITY_LOST -> "Heartbeat lease authority was lost.";
            case AUTHORITY_OPERATION_UNCONFIRMED ->
                    "Lease-authorized PostgreSQL operation could not be confirmed.";
            case TERMINAL -> "Heartbeat lease gate already completed terminal work.";
            case CLOSED -> "Heartbeat lease gate is closed.";
        };
    }

    enum Failure {
        INVALID_CONTRACT,
        START_FAILED,
        LEASE_AUTHORITY_LOST,
        AUTHORITY_OPERATION_UNCONFIRMED,
        TERMINAL,
        CLOSED
    }
}

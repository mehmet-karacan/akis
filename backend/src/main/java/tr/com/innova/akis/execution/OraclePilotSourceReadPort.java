package tr.com.innova.akis.execution;

import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;

/** Safe application boundary for one bounded Oracle pilot source read. */
interface OraclePilotSourceReadPort {

    SourceReadResult read(SourceReadCommand command);

    record SourceReadCommand(
            PilotRuntimePlan plan,
            PinnedExecutionContext execution,
            PinnedSnapshots snapshots) {
    }

    sealed interface SourceReadResult
            permits ReadSucceeded, NotAttempted, SafeFailure, OutcomeUnknown {
    }

    record ReadSucceeded(OraclePilotBatch batch) implements SourceReadResult {
    }

    record NotAttempted(Failure failure) implements SourceReadResult {
    }

    record SafeFailure(Failure failure) implements SourceReadResult {
    }

    record OutcomeUnknown(Failure failure) implements SourceReadResult {
    }

    enum Failure {
        INVALID_CONTRACT,
        CONNECTION_UNAVAILABLE,
        INVALID_SESSION_PURPOSE,
        INVALID_SESSION_STATE,
        SOURCE_SCHEMA_DRIFT,
        SOURCE_READ_REJECTED,
        SOURCE_READ_FAILED,
        SESSION_CLOSE_UNCONFIRMED
    }
}

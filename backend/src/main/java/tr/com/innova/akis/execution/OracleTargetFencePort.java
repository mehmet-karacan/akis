package tr.com.innova.akis.execution;

import java.util.UUID;

import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

/** Safe application boundary for committing one target-local Oracle fence. */
interface OracleTargetFencePort {

    OracleTargetFenceResult acquire(OracleTargetFenceCommand command);

    record OracleTargetFenceCommand(
            MappingExecutionContract plan,
            PinnedExecutionContext execution,
            TargetFenceToken fence) {
    }

    sealed interface OracleTargetFenceResult
            permits CommitConfirmed, NotAttempted, SafeFailure, OutcomeUnknown,
                    FencedOut {
    }

    record FenceReceipt(
            UUID targetResourceUuid,
            long targetGeneration,
            String canonicalTargetHash) {
    }

    record CommitConfirmed(FenceReceipt receipt)
            implements OracleTargetFenceResult {
    }

    record NotAttempted(FailureCode failure)
            implements OracleTargetFenceResult {
    }

    record SafeFailure(FailureCode failure)
            implements OracleTargetFenceResult {
    }

    record OutcomeUnknown(FailureCode failure)
            implements OracleTargetFenceResult {
    }

    record FencedOut(FailureCode failure)
            implements OracleTargetFenceResult {
    }

    enum FailureCode {
        INVALID_CONTRACT,
        CONNECTION_UNAVAILABLE,
        TARGET_IDENTITY_MISMATCH,
        STALE_FENCE,
        ORACLE_OPERATION_REJECTED,
        ROLLBACK_NOT_CONFIRMED,
        COMMIT_OUTCOME_UNKNOWN
    }
}

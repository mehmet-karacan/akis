package tr.com.innova.akis.execution;

import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

/** Safe application boundary for one target-local Oracle publication. */
interface OracleAtomicPublishPort {

    OracleAtomicPublishResult publish(OracleAtomicPublishCommand command);

    record OracleAtomicPublishCommand(
            PilotRuntimePlan plan,
            PinnedExecutionContext execution,
            TargetFenceToken fence,
            PinnedSnapshots snapshots,
            OraclePilotBatch batch) {
    }

    sealed interface OracleAtomicPublishResult
            permits CommitConfirmed, AlreadyRecorded, NotAttempted, SafeFailure,
                    OutcomeUnknown, EvidenceConflict, FencedOut {
    }

    record PublishReceipt(
            String publishKeyHash,
            String payloadHash,
            long rowCount,
            long byteCount) {
    }

    record CommitConfirmed(PublishReceipt receipt)
            implements OracleAtomicPublishResult {
    }

    record AlreadyRecorded(PublishReceipt receipt)
            implements OracleAtomicPublishResult {
    }

    record NotAttempted(FailureCode failure)
            implements OracleAtomicPublishResult {
    }

    /** Oracle transaction was opened but a rollback response was confirmed. */
    record SafeFailure(FailureCode failure)
            implements OracleAtomicPublishResult {
    }

    record OutcomeUnknown(FailureCode failure)
            implements OracleAtomicPublishResult {
    }

    record EvidenceConflict(FailureCode failure)
            implements OracleAtomicPublishResult {
    }

    record FencedOut(FailureCode failure)
            implements OracleAtomicPublishResult {
    }

    enum FailureCode {
        INVALID_CONTRACT,
        INTENT_NOT_FOUND,
        CONTROL_PLANE_UNAVAILABLE,
        CONNECTION_UNAVAILABLE,
        STALE_FENCE,
        LEDGER_EVIDENCE_CONFLICT,
        ORACLE_OPERATION_REJECTED,
        WRITE_VERIFICATION_FAILED,
        PREPARE_OUTCOME_UNKNOWN,
        ROLLBACK_NOT_CONFIRMED,
        COMMIT_OUTCOME_UNKNOWN
    }
}

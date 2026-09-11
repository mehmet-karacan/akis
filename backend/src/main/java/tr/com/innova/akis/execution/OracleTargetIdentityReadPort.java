package tr.com.innova.akis.execution;

import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;

/** Safe application boundary for a fresh, read-only Oracle target identity read. */
interface OracleTargetIdentityReadPort {

    TargetIdentityReadResult read(TargetIdentityReadCommand command);

    record TargetIdentityReadCommand(
            PilotRuntimePlan plan,
            PinnedExecutionContext execution,
            PinnedSnapshot targetSnapshot) {
    }

    sealed interface TargetIdentityReadResult
            permits IdentityReadSucceeded, NotAttempted, SafeFailure {
    }

    record IdentityReadSucceeded(TargetIdentityEvidence evidence)
            implements TargetIdentityReadResult {
    }

    record TargetIdentityEvidence(
            int targetIdentityVersion,
            String databaseUniqueName,
            String containerName,
            String owner,
            String objectType,
            String objectName,
            String canonicalTargetHash) {
    }

    record NotAttempted(Failure failure) implements TargetIdentityReadResult {
    }

    record SafeFailure(Failure failure) implements TargetIdentityReadResult {
    }

    enum Failure {
        INVALID_CONTRACT,
        CONNECTION_UNAVAILABLE,
        INVALID_SESSION_PURPOSE,
        INVALID_SESSION_STATE,
        TARGET_SCHEMA_REJECTED,
        TARGET_IDENTITY_REJECTED,
        TARGET_READ_FAILED,
        SESSION_CLOSE_UNCONFIRMED
    }
}

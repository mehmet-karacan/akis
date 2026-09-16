package tr.com.innova.akis.execution;

import java.util.Objects;
import java.util.UUID;

/** Smallest unit whose replay safety is established by durable evidence. */
record RecoveryUnit(
        String workUnitKey,
        UUID stepUuid,
        String stepCode,
        UnitKind kind,
        Decision decision,
        TransactionOutcome transactionOutcome,
        String evidenceReference,
        String reasonCode) {

    RecoveryUnit {
        workUnitKey = required(workUnitKey, "workUnitKey");
        stepCode = required(stepCode, "stepCode");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(transactionOutcome, "transactionOutcome");
        if (decision == Decision.SKIP_WITH_EVIDENCE
                && (evidenceReference == null || evidenceReference.isBlank())) {
            throw new IllegalArgumentException("A skipped unit requires durable evidence.");
        }
        if ((decision == Decision.BLOCKED || decision == Decision.RECONCILE_REQUIRED)
                && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("A blocked unit requires a reason code.");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value;
    }

    enum UnitKind { TRANSACTION_GROUP, CHUNK, READ_DEPENDENCY, PUBLISH, CLEANUP }

    enum Decision { RUN, SKIP_WITH_EVIDENCE, REPLAY_DEPENDENCY, RECONCILE_REQUIRED, BLOCKED }

    enum TransactionOutcome {
        NOT_ATTEMPTED,
        EXECUTED_UNCOMMITTED,
        COMMIT_CONFIRMED,
        ROLLBACK_CONFIRMED,
        OUTCOME_UNKNOWN
    }
}

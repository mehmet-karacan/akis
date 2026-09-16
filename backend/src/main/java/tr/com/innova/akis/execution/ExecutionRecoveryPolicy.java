package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable, compiled recovery policy. It is evidence, not a UI hint. */
record ExecutionRecoveryPolicy(
        int contractVersion,
        Set<RecoveryAction> allowedActions,
        int maximumAttempts,
        Duration initialBackoff,
        Duration retryBudget,
        boolean automaticRetry) {

    static final int CURRENT_VERSION = 1;

    ExecutionRecoveryPolicy {
        if (contractVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported recovery policy version.");
        }
        allowedActions = Set.copyOf(Objects.requireNonNull(allowedActions));
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalArgumentException("Recovery attempt limit is invalid.");
        }
        if (initialBackoff == null || initialBackoff.isNegative()
                || retryBudget == null || retryBudget.isNegative()) {
            throw new IllegalArgumentException("Recovery timing budget is invalid.");
        }
        if (automaticRetry && allowedActions.contains(RecoveryAction.RECONCILE)) {
            throw new IllegalArgumentException(
                    "Unknown outcomes cannot be reconciled by automatic retry.");
        }
    }

    static ExecutionRecoveryPolicy conservative() {
        return new ExecutionRecoveryPolicy(
                CURRENT_VERSION,
                EnumSet.of(RecoveryAction.RETRY_FAILED_UNIT, RecoveryAction.RESUME,
                        RecoveryAction.RESTART, RecoveryAction.CANCEL,
                        RecoveryAction.RECONCILE),
                3, Duration.ofSeconds(5), Duration.ofMinutes(15), false);
    }

    enum RecoveryAction {
        RETRY_FAILED_UNIT,
        RESUME,
        RESTART,
        CANCEL,
        RECONCILE
    }
}

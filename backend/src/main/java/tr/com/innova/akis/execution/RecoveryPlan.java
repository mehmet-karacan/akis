package tr.com.innova.akis.execution;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;

record RecoveryPlan(
        int evidenceVersion,
        UUID runUuid,
        long expectedStateVersion,
        String planHash,
        Set<RecoveryAction> allowedActions,
        List<String> reasonCodes,
        List<RecoveryUnit> units,
        boolean reconciliationRequired,
        boolean preservesWorkspace,
        boolean resetsTarget) {

    RecoveryPlan {
        if (evidenceVersion < 1 || runUuid == null || expectedStateVersion < 0
                || planHash == null || !planHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Recovery plan identity is invalid.");
        }
        allowedActions = Set.copyOf(allowedActions == null ? Set.of() : allowedActions);
        reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
        units = List.copyOf(units == null ? List.of() : units);
        if (reconciliationRequired
                && !allowedActions.equals(Set.of(RecoveryAction.RECONCILE))) {
            throw new IllegalArgumentException("Reconciliation must be the only allowed action.");
        }
    }
}

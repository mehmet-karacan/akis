package tr.com.innova.akis.execution;

import java.util.List;
import java.util.Set;

import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;

/** Fail-closed recovery decision shared by API and worker. */
record RecoveryEligibility(
        boolean eligible,
        Set<RecoveryAction> allowedActions,
        List<String> reasonCodes,
        boolean reconciliationRequired) {

    RecoveryEligibility {
        allowedActions = Set.copyOf(allowedActions == null ? Set.of() : allowedActions);
        reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
        if (!eligible && !allowedActions.isEmpty()) {
            throw new IllegalArgumentException("An ineligible run cannot expose recovery actions.");
        }
        if (reconciliationRequired
                && !allowedActions.equals(Set.of(RecoveryAction.RECONCILE))) {
            throw new IllegalArgumentException(
                    "Unknown transaction outcomes allow reconciliation only.");
        }
    }
}

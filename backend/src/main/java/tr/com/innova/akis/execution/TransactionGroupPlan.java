package tr.com.innova.akis.execution;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Compiled transaction boundary; statement execution and commit are separate facts. */
record TransactionGroupPlan(
        String groupId,
        UUID connectionVersionUuid,
        int channel,
        List<String> memberStepCodes,
        ProcedureRuntimePlan.TransactionIsolation isolation,
        CommitBoundary commitBoundary,
        String operationKey,
        boolean containsDdl) {

    TransactionGroupPlan {
        if (groupId == null || groupId.isBlank() || connectionVersionUuid == null
                || channel < 0 || channel > 9 || memberStepCodes == null
                || memberStepCodes.isEmpty() || memberStepCodes.stream().anyMatch(
                        value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Transaction group plan is incomplete.");
        }
        memberStepCodes = List.copyOf(memberStepCodes);
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(commitBoundary, "commitBoundary");
        if (containsDdl) {
            throw new IllegalArgumentException("DDL cannot join a managed transaction group.");
        }
        if (operationKey == null || operationKey.isBlank()) {
            throw new IllegalArgumentException("Transaction group operation key is required.");
        }
    }

    enum CommitBoundary { PER_STATEMENT, END_OF_GROUP }
}

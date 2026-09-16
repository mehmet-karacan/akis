package tr.com.innova.akis.execution;

import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;

/** Exact-acknowledgement metadata boundary for ordered Procedure task events. */
interface ProcedureExecutionJournalPort {

    boolean started(TaskEvidence evidence);

    boolean succeeded(TaskEvidence evidence, long rowCount, long byteCount);

    default boolean executedUncommitted(
            TaskEvidence evidence, long rowCount, long byteCount) {
        return false;
    }

    default boolean commitConfirmed(
            TaskEvidence evidence, long rowCount, long byteCount, String commitReference) {
        return false;
    }

    default boolean rollbackConfirmed(TaskEvidence evidence, String errorCode) {
        return false;
    }

    boolean failed(
            TaskEvidence evidence,
            String errorCode,
            boolean attempted,
            boolean rollbackConfirmed,
            boolean continued);

    boolean outcomeUnknown(TaskEvidence evidence, String errorCode);

    record TaskEvidence(
            String runtimePlanHash,
            int taskIndex,
            Task task,
            TaskBinding binding) {
    }
}

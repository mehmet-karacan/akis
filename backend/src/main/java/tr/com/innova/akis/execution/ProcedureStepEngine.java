package tr.com.innova.akis.execution;

import java.util.Objects;

import tr.com.innova.akis.execution.ProcedureExecutionJournalPort.TaskEvidence;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.NotAttempted;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.OutcomeUnknown;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.RowsetHandle;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.SafeFailure;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.Succeeded;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskCommand;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskResult;

/** Pure, bounded and fail-closed ordered execution of a pinned Procedure plan. */
final class ProcedureStepEngine {

    private final ProcedureTaskExecutorPort executor;
    private final ProcedureExecutionJournalPort journal;

    ProcedureStepEngine(
            ProcedureTaskExecutorPort executor,
            ProcedureExecutionJournalPort journal) {
        this.executor = Objects.requireNonNull(executor, "Procedure executor is required.");
        this.journal = Objects.requireNonNull(journal, "Procedure journal is required.");
    }

    RunResult execute(ProcedureRuntimePlan plan) {
        if (plan == null || plan.tasks().isEmpty()
                || plan.tasks().size() > ProcedureRuntimePlan.MAXIMUM_TASKS
                || plan.bindings().size() != plan.tasks().size()) {
            return new StoppedFailClosed(FailureCode.INVALID_RUNTIME_PLAN);
        }
        long rows = 0;
        long bytes = 0;
        int completed = 0;
        int warnings = 0;
        RowsetHandle pending = null;
        for (int index = 0; index < plan.tasks().size(); index++) {
            Task task = plan.tasks().get(index);
            TaskBinding binding = plan.bindings().get(task.id());
            if (!validTask(plan, index, task, binding, pending)) {
                return new StoppedFailClosed(FailureCode.INVALID_RUNTIME_PLAN);
            }
            TaskEvidence evidence = new TaskEvidence(
                    plan.runtimePlanHash(), index + 1, task, binding);
            if (!accepted(() -> journal.started(evidence))) {
                return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
            }

            TaskResult result;
            try {
                result = executor.execute(new TaskCommand(
                        plan, index + 1, task, binding, task.input() == null ? null : pending));
            }
            catch (RuntimeException exception) {
                if (!accepted(() -> journal.outcomeUnknown(evidence, "EXECUTOR_BOUNDARY_FAILED"))) {
                    return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
                }
                return new UnknownOutcome(task.id(), "EXECUTOR_BOUNDARY_FAILED");
            }

            if (result instanceof Succeeded success) {
                if (!validSuccess(plan, task, success)) {
                    if (!accepted(() -> journal.outcomeUnknown(evidence, "INVALID_EXECUTOR_RECEIPT"))) {
                        return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
                    }
                    return new UnknownOutcome(task.id(), "INVALID_EXECUTOR_RECEIPT");
                }
                if (!accepted(() -> journal.succeeded(
                        evidence, success.rowCount(), success.byteCount()))) {
                    return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
                }
                rows += success.rowCount();
                bytes += success.byteCount();
                completed++;
                pending = success.output();
                continue;
            }
            pending = null;
            if (result instanceof OutcomeUnknown unknown) {
                if (!accepted(() -> journal.outcomeUnknown(evidence, unknown.errorCode()))) {
                    return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
                }
                return new UnknownOutcome(task.id(), unknown.errorCode());
            }
            if (result instanceof SafeFailure failure) {
                if (!failure.rollbackConfirmed()) {
                    if (!accepted(() -> journal.outcomeUnknown(
                            evidence, "ROLLBACK_UNCONFIRMED"))) {
                        return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
                    }
                    return new UnknownOutcome(task.id(), "ROLLBACK_UNCONFIRMED");
                }
                boolean continued = task.onError() == ErrorPolicy.CONTINUE;
                if (!accepted(() -> journal.failed(
                        evidence, failure.errorCode(), true, true, continued))) {
                    return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
                }
                if (continued) {
                    warnings++;
                    continue;
                }
                return new FailedSafely(task.id(), failure.errorCode());
            }
            if (result instanceof NotAttempted skipped) {
                boolean continued = task.onError() == ErrorPolicy.CONTINUE;
                if (!accepted(() -> journal.failed(
                        evidence, skipped.errorCode(), false, true, continued))) {
                    return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
                }
                if (continued) {
                    warnings++;
                    continue;
                }
                return new FailedSafely(task.id(), skipped.errorCode());
            }
            if (!accepted(() -> journal.outcomeUnknown(evidence, "INVALID_EXECUTOR_RESULT"))) {
                return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
            }
            return new UnknownOutcome(task.id(), "INVALID_EXECUTOR_RESULT");
        }
        if (pending != null) {
            return new StoppedFailClosed(FailureCode.INVALID_RUNTIME_PLAN);
        }
        return new Completed(completed, warnings, rows, bytes);
    }

    private boolean validTask(
            ProcedureRuntimePlan plan,
            int index,
            Task task,
            TaskBinding binding,
            RowsetHandle pending) {
        if (task == null || binding == null || !task.id().equals(binding.taskId())
                || task.connectionRole() != binding.role()) {
            return false;
        }
        if (task.input() == null) {
            return pending == null;
        }
        return pending != null
                && pending.runtimePlanHash().equals(plan.runtimePlanHash())
                && pending.producerTaskId().equals(task.input().fromTask())
                && pending.rowCount() <= ProcedureRuntimePlan.MAXIMUM_ROWSET_ROWS
                && index > 0
                && plan.tasks().get(index - 1).id().equals(task.input().fromTask());
    }

    private boolean validSuccess(
            ProcedureRuntimePlan plan, Task task, Succeeded success) {
        if (success == null) return false;
        RowsetHandle output = success.output();
        if (task.output() == null) return output == null;
        return output != null
                && output.runtimePlanHash().equals(plan.runtimePlanHash())
                && output.producerTaskId().equals(task.id())
                && output.rowCount() == success.rowCount()
                && output.byteCount() == success.byteCount()
                && output.rowCount() <= task.output().maximumRows();
    }

    private boolean accepted(BooleanOperation operation) {
        try {
            return operation.run();
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    sealed interface RunResult
            permits Completed, FailedSafely, UnknownOutcome, StoppedFailClosed {
    }

    record Completed(int completedTasks, int warnings, long rowCount, long byteCount)
            implements RunResult {
    }

    record FailedSafely(String taskId, String errorCode) implements RunResult {
    }

    record UnknownOutcome(String taskId, String errorCode) implements RunResult {
    }

    record StoppedFailClosed(FailureCode failure) implements RunResult {
    }

    enum FailureCode {
        INVALID_RUNTIME_PLAN,
        CONTROL_PLANE_UNCONFIRMED
    }

    @FunctionalInterface
    private interface BooleanOperation {
        boolean run();
    }
}

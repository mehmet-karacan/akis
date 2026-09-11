package tr.com.innova.akis.execution;

import java.util.UUID;
import java.util.regex.Pattern;

import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;

/** Oracle boundary for one already-pinned Procedure task. */
interface ProcedureTaskExecutorPort {

    TaskResult execute(TaskCommand command);

    record TaskCommand(
            ProcedureRuntimePlan plan,
            int taskIndex,
            Task task,
            TaskBinding binding,
            RowsetHandle input) {
    }

    record RowsetHandle(
            UUID uuid,
            String runtimePlanHash,
            String producerTaskId,
            int rowCount,
            long byteCount) {

        public RowsetHandle {
            if (uuid == null || !hash(runtimePlanHash)
                    || producerTaskId == null || producerTaskId.isBlank()
                    || rowCount < 0 || rowCount > ProcedureRuntimePlan.MAXIMUM_ROWSET_ROWS
                    || byteCount < 0) {
                throw new IllegalArgumentException("Procedure rowset handle is invalid.");
            }
        }
    }

    sealed interface TaskResult
            permits Succeeded, SafeFailure, OutcomeUnknown, NotAttempted {
    }

    record Succeeded(long rowCount, long byteCount, RowsetHandle output)
            implements TaskResult {

        public Succeeded {
            if (rowCount < 0 || byteCount < 0) {
                throw new IllegalArgumentException("Procedure task metrics cannot be negative.");
            }
        }
    }

    record SafeFailure(String errorCode, boolean rollbackConfirmed)
            implements TaskResult {

        public SafeFailure {
            requireErrorCode(errorCode);
        }
    }

    record OutcomeUnknown(String errorCode) implements TaskResult {

        public OutcomeUnknown {
            requireErrorCode(errorCode);
        }
    }

    record NotAttempted(String errorCode) implements TaskResult {

        public NotAttempted {
            requireErrorCode(errorCode);
        }
    }

    private static boolean hash(String value) {
        return value != null && Pattern.matches("[0-9a-f]{64}", value);
    }

    private static void requireErrorCode(String value) {
        if (value == null || !Pattern.matches("[A-Z][A-Z0-9_]{0,99}", value)) {
            throw new IllegalArgumentException("Procedure task error code is invalid.");
        }
    }
}

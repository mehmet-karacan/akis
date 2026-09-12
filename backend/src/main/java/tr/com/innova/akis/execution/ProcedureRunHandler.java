package tr.com.innova.akis.execution;

import java.util.Objects;

import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;

/** Executes one already-authorized Procedure run without owning worker lifecycle. */
final class ProcedureRunHandler {

    private final JournalSessionFactory journals;
    private final ProcedureTaskExecutorSessionFactory executors;

    ProcedureRunHandler(
            JournalSessionFactory journals,
            ProcedureTaskExecutorSessionFactory executors) {
        this.journals = Objects.requireNonNull(journals, "Procedure journals are required.");
        this.executors = Objects.requireNonNull(executors, "Procedure executors are required.");
    }

    RunResult execute(ActiveExecutionToken token, ProcedureRuntimePlan plan) {
        if (token == null || plan == null) {
            return stopped(FailureCode.INVALID_EXECUTION_CONTEXT, null);
        }

        ProcedureExecutionJournalSession journal;
        try {
            journal = journals.forExecution(token, plan);
        }
        catch (RuntimeException exception) {
            return stopped(FailureCode.JOURNAL_SESSION_UNAVAILABLE, null);
        }
        if (journal == null) {
            return stopped(FailureCode.JOURNAL_SESSION_UNAVAILABLE, null);
        }
        if (!exactAcknowledgement(journal::prepareRun)) {
            return stopped(FailureCode.PREPARE_UNCONFIRMED, null);
        }

        ProcedureTaskExecutorSession executor;
        try {
            executor = executors.forExecution(token, plan);
        }
        catch (RuntimeException exception) {
            return stopped(FailureCode.EXECUTOR_SESSION_UNAVAILABLE, null);
        }
        if (executor == null) {
            return stopped(FailureCode.EXECUTOR_SESSION_UNAVAILABLE, null);
        }

        ProcedureStepEngine.RunResult engineResult;
        boolean engineReturned = false;
        boolean completingTransactions = false;
        try {
            engineResult = new ProcedureStepEngine(executor, journal).execute(plan);
            engineReturned = true;
            if (engineResult instanceof ProcedureStepEngine.Completed) {
                completingTransactions = true;
                executor.complete();
            }
            else {
                executor.abort();
            }
        }
        catch (RuntimeException exception) {
            try { executor.abort(); } catch (RuntimeException ignored) { }
            try { executor.close(); } catch (RuntimeException ignored) { }
            if (completingTransactions) {
                return new UnknownOutcome("TRANSACTION", "PROCEDURE_TRANSACTION_COMMIT_UNKNOWN");
            }
            return stopped(
                    engineReturned
                            ? FailureCode.EXECUTOR_SESSION_CLOSE_UNCONFIRMED
                            : FailureCode.ENGINE_BOUNDARY_FAILED,
                    null);
        }
        catch (Error error) {
            try { executor.abort(); } catch (RuntimeException ignored) { }
            try { executor.close(); } catch (RuntimeException ignored) { }
            throw error;
        }
        try {
            executor.close();
        }
        catch (RuntimeException exception) {
            return stopped(FailureCode.EXECUTOR_SESSION_CLOSE_UNCONFIRMED, null);
        }
        if (engineResult instanceof ProcedureStepEngine.Completed completed) {
            if (!exactAcknowledgement(journal::completeRun)) {
                return stopped(FailureCode.COMPLETION_UNCONFIRMED, null);
            }
            return new Completed(
                    completed.completedTasks(), completed.warnings(),
                    completed.rowCount(), completed.byteCount());
        }
        if (engineResult instanceof ProcedureStepEngine.FailedSafely failed) {
            return new FailedSafely(failed.taskId(), failed.errorCode());
        }
        if (engineResult instanceof ProcedureStepEngine.UnknownOutcome unknown) {
            return new UnknownOutcome(unknown.taskId(), unknown.errorCode());
        }
        ProcedureStepEngine.StoppedFailClosed stopped =
                (ProcedureStepEngine.StoppedFailClosed) engineResult;
        return stopped(FailureCode.ENGINE_STOPPED_FAIL_CLOSED, stopped.failure());
    }

    private boolean exactAcknowledgement(Acknowledgement operation) {
        try {
            return operation.read();
        }
        catch (RuntimeException first) {
            try {
                return operation.read();
            }
            catch (RuntimeException second) {
                return false;
            }
        }
    }

    private StoppedFailClosed stopped(
            FailureCode failure, ProcedureStepEngine.FailureCode engineFailure) {
        return new StoppedFailClosed(failure, engineFailure);
    }

    @FunctionalInterface
    interface JournalSessionFactory {

        ProcedureExecutionJournalSession forExecution(
                ActiveExecutionToken token, ProcedureRuntimePlan plan);
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

    record StoppedFailClosed(
            FailureCode failure,
            ProcedureStepEngine.FailureCode engineFailure) implements RunResult {
    }

    enum FailureCode {
        INVALID_EXECUTION_CONTEXT,
        JOURNAL_SESSION_UNAVAILABLE,
        PREPARE_UNCONFIRMED,
        EXECUTOR_SESSION_UNAVAILABLE,
        EXECUTOR_SESSION_CLOSE_UNCONFIRMED,
        ENGINE_BOUNDARY_FAILED,
        COMPLETION_UNCONFIRMED,
        ENGINE_STOPPED_FAIL_CLOSED
    }

    @FunctionalInterface
    private interface Acknowledgement {

        boolean read();
    }
}

package tr.com.innova.akis.execution;

/** Fail-closed placeholder used until the controlled Procedure executor is enabled. */
final class DisabledProcedureTaskExecutor implements ProcedureTaskExecutorPort {

    private static final NotAttempted DISABLED =
            new NotAttempted("PROCEDURE_EXECUTOR_DISABLED");

    @Override
    public TaskResult execute(TaskCommand ignored) {
        return DISABLED;
    }
}

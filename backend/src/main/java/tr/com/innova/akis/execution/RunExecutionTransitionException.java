package tr.com.innova.akis.execution;

final class RunExecutionTransitionException extends RuntimeException {

    RunExecutionTransitionException() {
        super("PostgreSQL execution transition could not be confirmed.");
    }
}

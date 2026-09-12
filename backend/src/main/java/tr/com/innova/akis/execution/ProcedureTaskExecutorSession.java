package tr.com.innova.akis.execution;

/** One execution-scoped Procedure task executor and its transient rowset lifecycle. */
interface ProcedureTaskExecutorSession extends ProcedureTaskExecutorPort, AutoCloseable {

    /** Commits every still-open managed transaction after a successful procedure. */
    default void complete() {
    }

    /** Rolls back every still-open managed transaction after a failed procedure. */
    default void abort() {
    }

    @Override
    void close();
}

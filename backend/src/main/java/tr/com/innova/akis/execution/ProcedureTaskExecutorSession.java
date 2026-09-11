package tr.com.innova.akis.execution;

/** One execution-scoped Procedure task executor and its transient rowset lifecycle. */
interface ProcedureTaskExecutorSession extends ProcedureTaskExecutorPort, AutoCloseable {

    @Override
    void close();
}

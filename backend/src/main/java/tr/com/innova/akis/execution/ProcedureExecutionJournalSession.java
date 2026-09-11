package tr.com.innova.akis.execution;

/** Run-bound Procedure journal including preparation and terminal exact ACKs. */
interface ProcedureExecutionJournalSession extends ProcedureExecutionJournalPort {

    boolean prepareRun();

    boolean completeRun();
}

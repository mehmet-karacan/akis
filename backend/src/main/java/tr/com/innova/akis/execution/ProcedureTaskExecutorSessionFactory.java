package tr.com.innova.akis.execution;

import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;

/** Creates exactly one task executor session for an already-authorized Procedure run. */
@FunctionalInterface
interface ProcedureTaskExecutorSessionFactory {

    ProcedureTaskExecutorSession forExecution(
            ActiveExecutionToken token, ProcedureRuntimePlan plan);
}

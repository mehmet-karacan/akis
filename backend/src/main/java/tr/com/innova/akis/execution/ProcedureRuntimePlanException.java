package tr.com.innova.akis.execution;

enum ProcedurePlanFailure {
    UNSUPPORTED_DEFINITION_TYPE,
    UNSUPPORTED_PROCEDURE_SHAPE,
    INVALID_PUBLICATION_MANIFEST,
    SCENARIO_PLAN_INTEGRITY_FAILED,
    RELEASE_INTEGRITY_FAILED,
    RELEASE_PLAN_MISMATCH,
    RUNTIME_PLAN_INTEGRITY_FAILED,
    DEFINITION_BINDING_MISMATCH
}

public final class ProcedureRuntimePlanException extends RuntimeException {

    private final ProcedurePlanFailure failure;

    ProcedureRuntimePlanException(ProcedurePlanFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    ProcedurePlanFailure failure() {
        return failure;
    }
}

package tr.com.innova.akis.execution;

enum PilotPlanFailure {
    UNSUPPORTED_DEFINITION_TYPE,
    UNSUPPORTED_MAPPING_SHAPE,
    UNSUPPORTED_WRITE_STRATEGY,
    INVALID_PUBLICATION_MANIFEST,
    SCENARIO_PLAN_INTEGRITY_FAILED,
    RELEASE_INTEGRITY_FAILED,
    RELEASE_PLAN_MISMATCH,
    RUNTIME_PLAN_INTEGRITY_FAILED,
    DEFINITION_BINDING_MISMATCH
}

public final class PilotRuntimePlanException extends RuntimeException {

    private final PilotPlanFailure failure;

    PilotRuntimePlanException(PilotPlanFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    PilotPlanFailure failure() {
        return failure;
    }
}

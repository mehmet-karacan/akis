package tr.com.innova.akis.execution;

final class RuntimeOracleConnectionException extends RuntimeException {

    private final Failure failure;

    RuntimeOracleConnectionException(Failure failure) {
        super("Runtime Oracle connection operation failed safely.");
        this.failure = failure;
    }

    Failure failure() {
        return failure;
    }

    enum Failure {
        INVALID_CONTRACT,
        METADATA_NOT_FOUND,
        UNSUPPORTED_PROFILE,
        CREDENTIAL_UNAVAILABLE,
        CONNECTION_FAILED,
        SESSION_OPERATION_FAILED,
        ROLLBACK_NOT_CONFIRMED
    }
}

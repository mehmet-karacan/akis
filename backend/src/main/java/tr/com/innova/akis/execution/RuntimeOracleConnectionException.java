package tr.com.innova.akis.execution;

final class RuntimeOracleConnectionException extends RuntimeException {

    private final Failure failure;
    private final Integer vendorCode;

    RuntimeOracleConnectionException(Failure failure) {
        this(failure, null);
    }

    RuntimeOracleConnectionException(Failure failure, Integer vendorCode) {
        super("Runtime Oracle connection operation failed safely.");
        this.failure = failure;
        this.vendorCode = vendorCode != null && vendorCode > 0 ? vendorCode : null;
    }

    Failure failure() {
        return failure;
    }

    Integer vendorCode() { return vendorCode; }

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

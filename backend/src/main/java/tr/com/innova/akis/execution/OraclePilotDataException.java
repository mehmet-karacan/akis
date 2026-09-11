package tr.com.innova.akis.execution;

/**
 * Sanitized, fail-closed failure raised by the bounded Oracle pilot data path.
 * JDBC exception messages and connection details are deliberately not retained.
 */
final class OraclePilotDataException extends RuntimeException {

    private final Failure failure;
    private final String sqlState;
    private final int vendorCode;

    OraclePilotDataException(Failure failure, String message) {
        this(failure, message, null, 0);
    }

    OraclePilotDataException(
            Failure failure, String message, String sqlState, int vendorCode) {
        super(message);
        this.failure = failure;
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    Failure failure() {
        return failure;
    }

    String sqlState() {
        return sqlState;
    }

    int vendorCode() {
        return vendorCode;
    }

    enum Failure {
        INVALID_PLAN,
        INVALID_BATCH,
        INVALID_CONNECTION,
        UNSUPPORTED_SOURCE_TYPE,
        SOURCE_CELL_LIMIT_EXCEEDED,
        SOURCE_BATCH_LIMIT_EXCEEDED,
        SOURCE_ROW_LIMIT_EXCEEDED,
        SOURCE_READ_FAILED,
        TARGET_IDENTITY_MISMATCH,
        TARGET_LOCK_FAILED,
        TARGET_WRITE_FAILED,
        TARGET_VERIFICATION_FAILED
    }
}

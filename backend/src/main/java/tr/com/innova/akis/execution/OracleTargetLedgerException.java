package tr.com.innova.akis.execution;

enum OracleLedgerFailure {
    INVALID_LEDGER_INPUT,
    FENCE_NOT_FOUND,
    FENCE_OWNERSHIP_MISMATCH,
    STALE_FENCE_TOKEN,
    FENCE_OWNER_CONFLICT,
    TRANSACTION_PROTOCOL_REJECTED,
    BATCH_EVIDENCE_CONFLICT,
    PUBLISH_EVIDENCE_CONFLICT,
    LEDGER_UNAVAILABLE
}

abstract class OracleTargetLedgerException extends RuntimeException {

    private final OracleLedgerFailure failure;
    private final Integer oracleErrorCode;

    OracleTargetLedgerException(
            OracleLedgerFailure failure, Integer oracleErrorCode, Throwable cause) {
        super("Oracle target ledger rejected the operation: " + failure, cause);
        this.failure = failure;
        this.oracleErrorCode = oracleErrorCode;
    }

    OracleLedgerFailure failure() {
        return failure;
    }

    Integer oracleErrorCode() {
        return oracleErrorCode;
    }
}

final class OracleFenceRejectedException extends OracleTargetLedgerException {

    OracleFenceRejectedException(
            OracleLedgerFailure failure, int oracleErrorCode, Throwable cause) {
        super(failure, oracleErrorCode, cause);
    }
}

final class OracleLedgerConflictException extends OracleTargetLedgerException {

    OracleLedgerConflictException(
            OracleLedgerFailure failure, int oracleErrorCode, Throwable cause) {
        super(failure, oracleErrorCode, cause);
    }
}

final class OracleLedgerTransactionException extends OracleTargetLedgerException {

    OracleLedgerTransactionException(Integer oracleErrorCode, Throwable cause) {
        super(OracleLedgerFailure.TRANSACTION_PROTOCOL_REJECTED, oracleErrorCode, cause);
    }
}

final class OracleLedgerInputException extends OracleTargetLedgerException {

    OracleLedgerInputException(int oracleErrorCode, Throwable cause) {
        super(OracleLedgerFailure.INVALID_LEDGER_INPUT, oracleErrorCode, cause);
    }
}

final class OracleLedgerUnavailableException extends OracleTargetLedgerException {

    OracleLedgerUnavailableException(Throwable cause) {
        super(OracleLedgerFailure.LEDGER_UNAVAILABLE, null, cause);
    }
}

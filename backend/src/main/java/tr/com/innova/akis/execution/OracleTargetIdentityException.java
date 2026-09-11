package tr.com.innova.akis.execution;

final class OracleTargetIdentityException extends RuntimeException {

    OracleTargetIdentityException(String message) {
        super(message);
    }

    OracleTargetIdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}

package tr.com.innova.akis.execution;

final class OracleSchemaPreflightException extends RuntimeException {

    private final OracleSchemaPreflightFailure failure;

    OracleSchemaPreflightException(OracleSchemaPreflightFailure failure) {
        super(switch (failure) {
            case INVALID_CONTRACT -> "Oracle schema preflight contract is invalid.";
            case SNAPSHOT_FINGERPRINT_MISMATCH ->
                    "Oracle schema preflight snapshot fingerprint is not trusted.";
            case LIVE_SCHEMA_DRIFT -> "Oracle live schema does not match the pinned snapshot.";
            case UNSUPPORTED_SCHEMA ->
                    "Oracle schema contains metadata that this preflight cannot verify.";
            case METADATA_UNAVAILABLE -> "Oracle schema metadata could not be verified.";
        });
        this.failure = failure;
    }

    OracleSchemaPreflightFailure failure() {
        return failure;
    }
}

enum OracleSchemaPreflightFailure {
    INVALID_CONTRACT,
    SNAPSHOT_FINGERPRINT_MISMATCH,
    LIVE_SCHEMA_DRIFT,
    UNSUPPORTED_SCHEMA,
    METADATA_UNAVAILABLE
}

package tr.com.innova.akis.oracle;

/** Raised when Oracle dictionary metadata cannot be represented without ambiguity or loss. */
public final class OracleSchemaSnapshotCodecException extends RuntimeException {

    public OracleSchemaSnapshotCodecException(String message) {
        super(message);
    }
}

package tr.com.innova.akis.execution;

final class PinnedSchemaSnapshotException extends RuntimeException {

    private final Failure failure;

    PinnedSchemaSnapshotException(Failure failure) {
        super("Pinned schema snapshot could not be loaded safely: " + failure);
        this.failure = failure;
    }

    Failure failure() {
        return failure;
    }

    enum Failure {
        INVALID_CONTRACT,
        BINDING_NOT_FOUND,
        AMBIGUOUS_BINDING,
        STORED_BODY_INVALID,
        FINGERPRINT_MISMATCH,
        CROSS_PUBLICATION_BINDING,
        METADATA_UNAVAILABLE
    }
}

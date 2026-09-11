package tr.com.innova.akis.execution;

import java.util.UUID;

/** Read-only application boundary for resolving an ambiguous Oracle publish. */
interface OraclePublishReconciliationReadPort {

    Result reconcile(UUID runUuid);

    record Result(Outcome outcome, Failure failure) {

        static Result published() {
            return new Result(Outcome.PUBLISHED, null);
        }

        static Result notPublished() {
            return new Result(Outcome.NOT_PUBLISHED, null);
        }

        static Result conflict(Failure failure) {
            return new Result(Outcome.CONFLICT, failure);
        }

        static Result unknown(Failure failure) {
            return new Result(Outcome.OUTCOME_UNKNOWN, failure);
        }
    }

    enum Outcome {
        PUBLISHED,
        NOT_PUBLISHED,
        CONFLICT,
        OUTCOME_UNKNOWN
    }

    enum Failure {
        INVALID_REQUEST,
        PINNED_EVIDENCE_NOT_FOUND,
        PINNED_EVIDENCE_CONFLICT,
        CONTROL_PLANE_UNAVAILABLE,
        CONNECTION_UNAVAILABLE,
        INVALID_SESSION_PURPOSE,
        TARGET_IDENTITY_MISMATCH,
        FENCE_BARRIER_NOT_COMMITTED,
        FENCE_BARRIER_OUTCOME_UNKNOWN,
        FENCE_BARRIER_CONFLICT,
        LEDGER_EVIDENCE_CONFLICT,
        ORACLE_READ_UNCONFIRMED,
        ROLLBACK_NOT_CONFIRMED,
        SESSION_CLOSE_UNCONFIRMED
    }
}

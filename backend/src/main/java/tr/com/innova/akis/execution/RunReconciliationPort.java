package tr.com.innova.akis.execution;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

/** PostgreSQL control-plane boundary for resolving one ambiguous run outcome. */
interface RunReconciliationPort {

    Optional<ReconciliationLeaseToken> claim(
            UUID runUuid, WorkerIdentity worker, Duration lease);

    HeartbeatResult heartbeat(ReconciliationLeaseToken token, Duration lease);

    CompletionResult complete(
            ReconciliationLeaseToken token, ReconciliationCompletion completion);

    record ReconciliationLeaseToken(
            UUID runUuid,
            String workerReference,
            long runGeneration,
            UUID targetResourceUuid,
            long targetGeneration,
            OffsetDateTime leaseDeadline) {
    }

    sealed interface ReconciliationCompletion
            permits Published, NotPublished, Conflict {
    }

    record Published(PublishEvidence evidence) implements ReconciliationCompletion {
    }

    record NotPublished() implements ReconciliationCompletion {
    }

    record Conflict() implements ReconciliationCompletion {
    }

    record PublishEvidence(
            String runtimePlanHash,
            String publishKeyHash,
            String payloadHash,
            long rowCount,
            long byteCount) {
    }

    enum MutationOutcome {
        ACCEPTED,
        REJECTED_FAIL_CLOSED
    }

    record HeartbeatResult(
            MutationOutcome outcome,
            ReconciliationLeaseToken refreshedToken) {

        static HeartbeatResult accepted(ReconciliationLeaseToken token) {
            return new HeartbeatResult(MutationOutcome.ACCEPTED, token);
        }

        static HeartbeatResult rejected() {
            return new HeartbeatResult(MutationOutcome.REJECTED_FAIL_CLOSED, null);
        }
    }

    record CompletionResult(MutationOutcome outcome) {

        static CompletionResult accepted() {
            return new CompletionResult(MutationOutcome.ACCEPTED);
        }

        static CompletionResult rejected() {
            return new CompletionResult(MutationOutcome.REJECTED_FAIL_CLOSED);
        }
    }
}

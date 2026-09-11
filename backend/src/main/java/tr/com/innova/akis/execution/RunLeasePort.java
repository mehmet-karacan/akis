package tr.com.innova.akis.execution;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

interface RunLeasePort {

    Optional<ClaimedRun> claimForPreflight(WorkerIdentity worker, Duration lease);

    HeartbeatResult heartbeat(RunLeaseToken token, Duration lease);

    TargetFenceToken acquireTarget(
            RunLeaseToken token, String canonicalTargetHash, int identityVersion);

    record WorkerIdentity(String reference, UUID profileUuid) {
    }

    record RunLeaseToken(
            UUID runUuid,
            String workerReference,
            long generation,
            OffsetDateTime leaseDeadline) {
    }

    record TargetFenceToken(
            UUID runUuid,
            String workerReference,
            long runGeneration,
            UUID targetResourceUuid,
            long targetGeneration) {
    }

    record ClaimedRun(
            RunLeaseToken token,
            String releaseHash,
            String planHash) {
    }

    enum HeartbeatOutcome {
        ACCEPTED,
        REJECTED_FAIL_CLOSED
    }

    record HeartbeatResult(
            HeartbeatOutcome outcome,
            RunLeaseToken refreshedToken) {

        static HeartbeatResult accepted(RunLeaseToken token) {
            return new HeartbeatResult(HeartbeatOutcome.ACCEPTED, token);
        }

        static HeartbeatResult rejected() {
            return new HeartbeatResult(HeartbeatOutcome.REJECTED_FAIL_CLOSED, null);
        }
    }
}

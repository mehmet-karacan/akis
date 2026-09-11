package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Boundary for the future worker process. No implementation is registered until
 * target-local ledger and fencing support exists.
 */
interface RunClaimPort {

    Optional<ClaimedRun> claim(WorkerIdentity worker, Duration lease);

    boolean heartbeat(RunToken token, Duration lease);

    record WorkerIdentity(String reference, UUID profileUuid) {
    }

    record RunToken(
            UUID runUuid,
            String workerReference,
            long leaseGeneration,
            long targetFenceGeneration) {
    }

    record ClaimedRun(RunToken token, String planHash) {
    }
}

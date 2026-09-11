package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

/** Immutable PostgreSQL evidence committed before any Oracle business DML. */
interface PilotPublishIntentPort {

    /** Returns evidence only while the exact run and target leases remain publish-active. */
    Optional<PilotPublishIntent> find(UUID runUuid);

    record PilotPublishIntent(
            UUID projectUuid,
            UUID publicationUuid,
            UUID jobRequestUuid,
            UUID runUuid,
            int attemptNumber,
            long runGeneration,
            String workerReference,
            UUID targetResourceUuid,
            long targetGeneration,
            String canonicalTargetHash,
            int targetIdentityVersion,
            String releaseHash,
            String planHash,
            String runtimePlanHash,
            String publishKeyHash,
            String payloadHash,
            long rowCount,
            long byteCount) {
    }
}

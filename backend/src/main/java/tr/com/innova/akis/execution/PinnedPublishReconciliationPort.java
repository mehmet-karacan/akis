package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;

/**
 * Trusted control-plane aggregate for reconciliation. Implementations must
 * build this from immutable publish intent and the claimed next fence-barrier
 * generation; the facade proves the committed barrier directly from Oracle.
 * Callers of the facade may provide only the run UUID.
 */
interface PinnedPublishReconciliationPort {

    Optional<PinnedReconciliation> find(UUID runUuid);

    record PinnedReconciliation(
            MappingExecutionContract plan,
            PinnedExecutionContext execution,
            PilotPublishIntent originalPublish,
            BarrierEvidence barrier) {
    }

    /**
     * Keeps the original publish token and the current barrier token distinct.
     * The barrier token is exactly one greater than the original publish token.
     */
    record BarrierEvidence(
            UUID runUuid,
            long reconciliationRunGeneration,
            String reconciliationWorkerReference,
            UUID targetResourceUuid,
            long originalPublishTargetGeneration,
            long barrierTargetGeneration,
            String canonicalTargetHash,
            int targetIdentityVersion) {
    }
}

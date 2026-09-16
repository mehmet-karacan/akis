package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;

interface ExecutionRecoveryStore {
    Optional<RecoveryApplication> find(
            UUID projectUuid, UUID parentRunUuid, long actorId, String idempotencyKeyHash);

    RecoveryApplication create(
            RunRow parent,
            Actor actor,
            RecoveryAction action,
            String idempotencyKeyHash,
            String requestHash,
            RecoveryPlan plan);

    record RecoveryApplication(
            UUID requestUuid, RunRow run, String requestHash, boolean created) { }
}

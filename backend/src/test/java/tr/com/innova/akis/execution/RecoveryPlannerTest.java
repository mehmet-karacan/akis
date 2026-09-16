package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.RunStepRow;
import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;
import tr.com.innova.akis.execution.RecoveryUnit.Decision;

class RecoveryPlannerTest {

    private final RecoveryPlanner planner = new RecoveryPlanner();

    @Test
    void unknownCommitAllowsOnlyReconciliation() {
        RecoveryPlan plan = planner.plan(
                run("SONUC_BELIRSIZ"),
                List.of(step("LOAD", "SONUC_BELIRSIZ", "TARGET", "DML", "OUTCOME_UNKNOWN")),
                true, true);

        assertEquals(Set.of(RecoveryAction.RECONCILE), plan.allowedActions());
        assertTrue(plan.reconciliationRequired());
        assertTrue(plan.reasonCodes().contains("TX_OUTCOME_UNKNOWN"));
        assertEquals(Decision.RECONCILE_REQUIRED, plan.units().getFirst().decision());
    }

    @Test
    void failedRunWithPinnedInputCanResumeWithoutReplayingCommittedUnit() {
        RecoveryPlan plan = planner.plan(
                run("BASARISIZ"),
                List.of(
                        step("TRUNCATE", "BASARILI", "TARGET", "DML", "COMMITTED"),
                        step("LOAD", "BASARISIZ", "TARGET", "DML", "ROLLBACK_CONFIRMED")),
                true, true);

        assertTrue(plan.allowedActions().containsAll(Set.of(
                RecoveryAction.RETRY_FAILED_UNIT, RecoveryAction.RESUME,
                RecoveryAction.RESTART)));
        assertEquals(Decision.SKIP_WITH_EVIDENCE, plan.units().get(0).decision());
        assertEquals(Decision.RUN, plan.units().get(1).decision());
        assertFalse(plan.reconciliationRequired());
    }

    @Test
    void legacyRunWithoutImmutableInputFailsClosed() {
        RecoveryPlan plan = planner.plan(
                run("BASARISIZ"), List.of(), false, true);

        assertTrue(plan.allowedActions().isEmpty());
        assertTrue(plan.reasonCodes().contains("LEGACY_NO_RECOVERY_EVIDENCE"));
    }

    @Test
    void disabledCapabilityNeverLeaksAnAction() {
        RecoveryPlan plan = planner.plan(
                run("BASARISIZ"), List.of(), true, false);

        assertTrue(plan.allowedActions().isEmpty());
        assertTrue(plan.reasonCodes().contains("CAPABILITY_DISABLED"));
    }

    @Test
    void skippedRecoveryUnitRequiresDurableEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryUnit(
                "a".repeat(64), UUID.randomUUID(), "STEP",
                RecoveryUnit.UnitKind.TRANSACTION_GROUP,
                Decision.SKIP_WITH_EVIDENCE,
                RecoveryUnit.TransactionOutcome.COMMIT_CONFIRMED,
                null, null));
    }

    private RunRow run(String status) {
        return new RunRow(1, 2, 3, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "ILK", status, "a".repeat(64),
                "b".repeat(64), 7, OffsetDateTime.now(), null, null, null);
    }

    private RunStepRow step(
            String code, String status, String role, String risk, String tx) {
        return new RunStepRow(UUID.randomUUID(), null, code, "PROSEDUR", 1, code,
                status, role, risk, null, null, null, null, null, null, tx);
    }
}

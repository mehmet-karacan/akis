package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.RunStepRow;
import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;
import tr.com.innova.akis.execution.RecoveryUnit.Decision;
import tr.com.innova.akis.execution.RecoveryUnit.TransactionOutcome;
import tr.com.innova.akis.execution.RecoveryUnit.UnitKind;

/** Read-only, deterministic, fail-closed recovery planner. */
final class RecoveryPlanner {

    static final int EVIDENCE_VERSION = 1;

    RecoveryPlan plan(RunRow run, List<RunStepRow> steps, boolean inputSnapshotComplete,
            boolean capabilityEnabled) {
        if (run == null) throw new IllegalArgumentException("Run is required.");
        List<RunStepRow> safeSteps = List.copyOf(steps == null ? List.of() : steps);
        List<String> reasons = new ArrayList<>();
        List<RecoveryUnit> units = new ArrayList<>();
        EnumSet<RecoveryAction> actions = EnumSet.noneOf(RecoveryAction.class);

        if (!capabilityEnabled) reasons.add("CAPABILITY_DISABLED");
        if (!inputSnapshotComplete) reasons.add("LEGACY_NO_RECOVERY_EVIDENCE");

        boolean unknown = isUnknownStatus(run.status());
        boolean active = isActive(run.status());
        boolean recoverableFailure = Set.of("BASARISIZ", "YENIDEN_DENENEBILIR", "IPTAL")
                .contains(run.status());

        for (RunStepRow step : safeSteps) {
            boolean mutating = step.risk() != null
                    && !"READ_ONLY".equals(step.risk());
            TransactionOutcome outcome = outcome(step, run.status());
            Decision decision;
            String reason = null;
            if (outcome == TransactionOutcome.OUTCOME_UNKNOWN) {
                unknown = true;
                decision = Decision.RECONCILE_REQUIRED;
                reason = "TX_OUTCOME_UNKNOWN";
            }
            else if ("BASARILI".equals(step.status())
                    && (!mutating || outcome == TransactionOutcome.COMMIT_CONFIRMED)) {
                decision = Decision.SKIP_WITH_EVIDENCE;
            }
            else if ("SOURCE".equals(step.connectionRole())) {
                decision = Decision.REPLAY_DEPENDENCY;
            }
            else if (recoverableFailure) {
                decision = Decision.RUN;
            }
            else {
                decision = Decision.BLOCKED;
                reason = active ? "RUN_STILL_ACTIVE" : "RECOVERY_NOT_APPLICABLE";
            }
            units.add(new RecoveryUnit(
                    workUnitKey(run, step), step.uuid(), step.code(),
                    "SOURCE".equals(step.connectionRole())
                            ? UnitKind.READ_DEPENDENCY : UnitKind.TRANSACTION_GROUP,
                    decision, outcome,
                    decision == Decision.SKIP_WITH_EVIDENCE
                            ? "run:" + run.runUuid() + "/step:" + step.uuid() : null,
                    reason));
        }

        if (unknown) {
            reasons.remove("RECOVERY_NOT_APPLICABLE");
            if (!reasons.contains("TX_OUTCOME_UNKNOWN")) reasons.add("TX_OUTCOME_UNKNOWN");
            if (capabilityEnabled) actions.add(RecoveryAction.RECONCILE);
        }
        else if (active) {
            if (capabilityEnabled) actions.add(RecoveryAction.CANCEL);
        }
        else if (recoverableFailure && inputSnapshotComplete && capabilityEnabled) {
            actions.add(RecoveryAction.RETRY_FAILED_UNIT);
            actions.add(RecoveryAction.RESUME);
            actions.add(RecoveryAction.RESTART);
        }
        else if ("BASARILI".equals(run.status())) {
            reasons.add("RUN_ALREADY_SUCCEEDED");
        }
        else if (reasons.isEmpty()) {
            reasons.add("RECOVERY_NOT_APPLICABLE");
        }

        String fingerprint = run.runUuid() + "|" + run.lastEventNumber() + "|"
                + run.status() + "|" + run.planHash() + "|" + inputSnapshotComplete + "|"
                + actions + "|" + reasons + "|" + units;
        return new RecoveryPlan(
                EVIDENCE_VERSION, run.runUuid(), run.lastEventNumber(), sha256(fingerprint),
                actions, reasons, units, unknown, true,
                actions.contains(RecoveryAction.RESTART));
    }

    private TransactionOutcome outcome(RunStepRow step, String runStatus) {
        if ("SOURCE".equals(step.connectionRole()) || "READ_ONLY".equals(step.risk())) {
            return "BASARILI".equals(step.status())
                    ? TransactionOutcome.COMMIT_CONFIRMED : TransactionOutcome.NOT_ATTEMPTED;
        }
        return switch (nullToEmpty(step.transactionState()).toUpperCase(Locale.ROOT)) {
            case "COMMITTED", "COMMIT_CONFIRMED" -> TransactionOutcome.COMMIT_CONFIRMED;
            case "EXECUTED_UNCOMMITTED" -> TransactionOutcome.EXECUTED_UNCOMMITTED;
            case "ROLLBACK_CONFIRMED" -> TransactionOutcome.ROLLBACK_CONFIRMED;
            case "OUTCOME_UNKNOWN" -> TransactionOutcome.OUTCOME_UNKNOWN;
            default -> isUnknownStatus(runStatus)
                    ? TransactionOutcome.OUTCOME_UNKNOWN : TransactionOutcome.NOT_ATTEMPTED;
        };
    }

    private boolean isUnknownStatus(String status) {
        return Set.of("SONUC_BELIRSIZ", "SONUCU_BILINMIYOR", "MUTABAKAT",
                "MUDAHALE_GEREKLI").contains(status);
    }

    private boolean isActive(String status) {
        return Set.of("BEKLIYOR", "SAHIPLENILDI", "CALISIYOR", "YAYINLANIYOR",
                "IPTAL_ISTENDI").contains(status);
    }

    private String workUnitKey(RunRow run, RunStepRow step) {
        return sha256(run.jobRequestUuid() + "|" + run.releaseHash() + "|"
                + run.planHash() + "|" + step.code());
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

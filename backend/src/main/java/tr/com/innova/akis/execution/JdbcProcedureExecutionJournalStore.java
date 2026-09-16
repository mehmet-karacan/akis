package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tr.com.innova.akis.execution.ProcedureExecutionJournalPort.TaskEvidence;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RiskClass;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;

/** PostgreSQL exact-acknowledgement adapter for Procedure run and task events. */
@Repository
@ConditionalOnProperty(
        name = "akis.execution.procedure-runtime-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class JdbcProcedureExecutionJournalStore {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STEP_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern ERROR_CODE =
            Pattern.compile("[A-Z0-9][A-Z0-9_.:-]{0,99}");

    private final JdbcClient jdbc;
    private final TransactionTemplate requiresNew;
    private final ProcedureOperationKeyV1 operationKeys;

    public JdbcProcedureExecutionJournalStore(
            JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcClient is required.");
        Objects.requireNonNull(transactionManager, "Transaction manager is required.");
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.operationKeys = new ProcedureOperationKeyV1();
    }

    /** Binds an immutable run lease/target fence tuple to all task acknowledgements. */
    ProcedureExecutionJournalSession forExecution(
            ActiveExecutionToken token, ProcedureRuntimePlan plan) {
        return new ExecutionJournal(
                this, required(token), required(plan));
    }

    private boolean prepareRun(
            ActiveExecutionToken token, ProcedureRuntimePlan plan) {
        ActiveExecutionToken safeToken = required(token);
        return invoke(() -> bindToken(jdbc.sql("""
                select akis.prosedur_calistirmayi_baslat(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :runtimePlanHash)
                """), safeToken)
                .param("runtimePlanHash", plan.runtimePlanHash())
                .query(Boolean.class)
                .single());
    }

    private boolean completeRun(
            ActiveExecutionToken token, ProcedureRuntimePlan plan) {
        ActiveExecutionToken safeToken = required(token);
        return invoke(() -> bindToken(jdbc.sql("""
                select akis.prosedur_calistirmayi_basarili_tamamla(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :runtimePlanHash)
                """), safeToken)
                .param("runtimePlanHash", plan.runtimePlanHash())
                .query(Boolean.class)
                .single());
    }

    private boolean start(
            ActiveExecutionToken token,
            ProcedureRuntimePlan plan,
            TaskEvidence evidence) {
        TaskEvidence safe = required(evidence, plan);
        String operationKey = safe.task().riskClass() == RiskClass.READ_ONLY
                ? null : operationKeys.create(token, safe);
        return invoke(() -> bindTask(jdbc.sql("""
                select akis.prosedur_adimini_baslat(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :stepCode, :operationKeyHash)
                """), token, safe)
                .param("operationKeyHash", operationKey)
                .query(Boolean.class)
                .single());
    }

    private boolean succeed(
            ActiveExecutionToken token, ProcedureRuntimePlan plan, TaskEvidence evidence,
            long rowCount, long byteCount) {
        TaskEvidence safe = required(evidence, plan);
        if (rowCount < 0 || byteCount < 0) {
            throw new IllegalArgumentException("Procedure task counts are invalid.");
        }
        return invoke(() -> bindTask(jdbc.sql("""
                select akis.prosedur_adimini_basarili_tamamla(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :stepCode, :rowCount, :byteCount)
                """), token, safe)
                .param("rowCount", rowCount)
                .param("byteCount", byteCount)
                .query(Boolean.class)
                .single());
    }

    private boolean executedUncommitted(
            ActiveExecutionToken token, ProcedureRuntimePlan plan, TaskEvidence evidence,
            long rowCount, long byteCount) {
        TaskEvidence safe = required(evidence, plan);
        if (rowCount < 0 || byteCount < 0 || safe.task().riskClass() == RiskClass.READ_ONLY) {
            throw new IllegalArgumentException("Uncommitted Procedure evidence is invalid.");
        }
        return invoke(() -> bindTask(jdbc.sql("""
                select akis.prosedur_adimini_yurutuldu_isaretle(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :stepCode, :rowCount, :byteCount)
                """), token, safe)
                .param("rowCount", rowCount)
                .param("byteCount", byteCount)
                .query(Boolean.class).single());
    }

    private boolean commitConfirmed(
            ActiveExecutionToken token, ProcedureRuntimePlan plan, TaskEvidence evidence,
            long rowCount, long byteCount, String commitReference) {
        TaskEvidence safe = required(evidence, plan);
        if (rowCount < 0 || byteCount < 0 || commitReference == null
                || commitReference.isBlank() || commitReference.length() > 200) {
            throw new IllegalArgumentException("Procedure commit evidence is invalid.");
        }
        return invoke(() -> bindTask(jdbc.sql("""
                select akis.prosedur_adimini_commit_ile_tamamla(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :stepCode,
                    :rowCount, :byteCount, :commitReference)
                """), token, safe)
                .param("rowCount", rowCount).param("byteCount", byteCount)
                .param("commitReference", commitReference)
                .query(Boolean.class).single());
    }

    private boolean rollbackConfirmed(
            ActiveExecutionToken token, ProcedureRuntimePlan plan, TaskEvidence evidence,
            String errorCode) {
        TaskEvidence safe = required(evidence, plan);
        String safeError = requiredErrorCode(errorCode);
        return invoke(() -> bindTask(jdbc.sql("""
                select akis.prosedur_adimini_rollback_ile_tamamla(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :stepCode, :errorCode)
                """), token, safe)
                .param("errorCode", safeError)
                .query(Boolean.class).single());
    }

    private boolean fail(
            ActiveExecutionToken token,
            ProcedureRuntimePlan plan,
            TaskEvidence evidence,
            String errorCode,
            boolean attempted,
            boolean rollbackConfirmed,
            boolean continued) {
        TaskEvidence safe = required(evidence, plan);
        String safeError = requiredErrorCode(errorCode);
        boolean expectedContinue = safe.task().onError() == ErrorPolicy.CONTINUE;
        if (!rollbackConfirmed || continued != expectedContinue) {
            throw new IllegalArgumentException(
                    "Safe Procedure failure evidence is inconsistent.");
        }
        // attempted distinguishes executor receipts, while the durable start event already
        // proves whether the Oracle boundary was entered. V013 therefore needs no extra bind.
        return invoke(() -> bindTask(jdbc.sql("""
                select akis.prosedur_adimini_basarisiz_tamamla(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :stepCode, :errorCode)
                """), token, safe)
                .param("errorCode", safeError)
                .query(Boolean.class)
                .single());
    }

    private boolean unknown(
            ActiveExecutionToken token,
            ProcedureRuntimePlan plan,
            TaskEvidence evidence,
            String errorCode) {
        TaskEvidence safe = required(evidence, plan);
        if (safe.task().riskClass() == RiskClass.READ_ONLY) {
            throw new IllegalArgumentException(
                    "Read-only Procedure tasks cannot have an unknown outcome.");
        }
        String safeError = requiredErrorCode(errorCode);
        return invoke(() -> bindTask(jdbc.sql("""
                select akis.prosedur_adimini_sonuc_belirsiz_isaretle(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :stepCode, :errorCode)
                """), token, safe)
                .param("errorCode", safeError)
                .query(Boolean.class)
                .single());
    }

    private JdbcClient.StatementSpec bindTask(
            JdbcClient.StatementSpec statement,
            ActiveExecutionToken token,
            TaskEvidence evidence) {
        return bindToken(statement, token).param("stepCode", evidence.task().id());
    }

    private JdbcClient.StatementSpec bindToken(
            JdbcClient.StatementSpec statement, ActiveExecutionToken token) {
        return statement
                .param("runUuid", token.run().runUuid())
                .param("workerReference", token.run().workerReference())
                .param("runGeneration", token.run().generation())
                .param("targetUuid", token.target().targetResourceUuid())
                .param("targetGeneration", token.target().targetGeneration());
    }

    private boolean invoke(Acknowledgement acknowledgement) {
        try {
            Boolean accepted = requiresNew.execute(status -> acknowledgement.read());
            return Boolean.TRUE.equals(accepted);
        }
        catch (RuntimeException exception) {
            // Do not retain SQL diagnostics; they can contain endpoint/data details.
            throw new ProcedureExecutionJournalException();
        }
    }

    private ActiveExecutionToken required(ActiveExecutionToken token) {
        if (token == null || token.target().canonicalTargetHash() == null
                || !HASH.matcher(token.target().canonicalTargetHash()).matches()
                || token.target().targetIdentityVersion() <= 0) {
            throw new IllegalArgumentException("Active Procedure execution token is invalid.");
        }
        return token;
    }

    private ProcedureRuntimePlan required(ProcedureRuntimePlan plan) {
        if (plan == null || plan.planVersion() != ProcedureRuntimePlan.CURRENT_VERSION
                || !HASH.matcher(nullToEmpty(plan.runtimePlanHash())).matches()
                || !HASH.matcher(nullToEmpty(plan.releaseHash())).matches()
                || !HASH.matcher(nullToEmpty(plan.scenarioPlanHash())).matches()
                || plan.definitionUuid() == null || plan.definitionVersionUuid() == null
                || plan.tasks() == null || plan.tasks().isEmpty()
                || plan.tasks().size() > ProcedureRuntimePlan.MAXIMUM_TASKS
                || plan.bindings() == null
                || plan.bindings().size() != plan.tasks().size()) {
            throw new IllegalArgumentException("Procedure runtime plan is invalid.");
        }
        for (int index = 0; index < plan.tasks().size(); index++) {
            TaskEvidence evidence = new TaskEvidence(
                    plan.runtimePlanHash(), index + 1, plan.tasks().get(index),
                    plan.tasks().get(index) == null
                            ? null : plan.bindings().get(plan.tasks().get(index).id()));
            required(evidence, plan);
        }
        return plan;
    }

    private TaskEvidence required(TaskEvidence evidence, ProcedureRuntimePlan plan) {
        if (evidence == null || evidence.task() == null || evidence.binding() == null
                || evidence.taskIndex() < 1
                || evidence.taskIndex() > ProcedureRuntimePlan.MAXIMUM_TASKS
                || !HASH.matcher(nullToEmpty(evidence.runtimePlanHash())).matches()
                || !STEP_CODE.matcher(nullToEmpty(evidence.task().id())).matches()
                || !HASH.matcher(nullToEmpty(evidence.task().commandHash())).matches()
                || evidence.task().command() == null
                || !Objects.equals(
                        evidence.task().commandHash(), sha256(evidence.task().command()))
                || evidence.task().type() == null
                || evidence.task().connectionRole() == null
                || evidence.task().riskClass() == null
                || evidence.task().onError() == null
                || evidence.task().timeoutSeconds() < 1
                || evidence.task().timeoutSeconds()
                        > ProcedureRuntimePlan.MAXIMUM_TIMEOUT_SECONDS
                || !Objects.equals(evidence.task().id(), evidence.binding().taskId())
                || evidence.task().connectionRole() != evidence.binding().role()
                || evidence.binding().definitionDataObjectUuid() == null
                || evidence.binding().dataObjectUuid() == null
                || evidence.binding().environmentSchemaBindingUuid() == null
                || evidence.binding().physicalSchemaUuid() == null
                || evidence.binding().connectionVersionUuid() == null
                || evidence.binding().schemaSnapshotUuid() == null
                || evidence.binding().bindingVersion() < 1
                || !HASH.matcher(nullToEmpty(
                        evidence.binding().schemaSnapshotFingerprint())).matches()
                || plan == null
                || !Objects.equals(plan.runtimePlanHash(), evidence.runtimePlanHash())
                || evidence.taskIndex() > plan.tasks().size()
                || !Objects.equals(
                        plan.tasks().get(evidence.taskIndex() - 1), evidence.task())
                || !Objects.equals(
                        plan.bindings().get(evidence.task().id()), evidence.binding())) {
            throw new IllegalArgumentException("Procedure task evidence is invalid.");
        }
        return evidence;
    }

    private String requiredErrorCode(String value) {
        if (value == null || !ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("Procedure error code is invalid.");
        }
        return value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    @FunctionalInterface
    private interface Acknowledgement {
        Boolean read();
    }

    private record ExecutionJournal(
            JdbcProcedureExecutionJournalStore store,
            ActiveExecutionToken token,
            ProcedureRuntimePlan plan) implements ProcedureExecutionJournalSession {

        @Override
        public boolean prepareRun() {
            return store.prepareRun(token, plan);
        }

        @Override
        public boolean completeRun() {
            return store.completeRun(token, plan);
        }

        @Override
        public boolean started(TaskEvidence evidence) {
            return store.start(token, plan, evidence);
        }

        @Override
        public boolean succeeded(TaskEvidence evidence, long rowCount, long byteCount) {
            return store.succeed(token, plan, evidence, rowCount, byteCount);
        }

        @Override
        public boolean executedUncommitted(
                TaskEvidence evidence, long rowCount, long byteCount) {
            return store.executedUncommitted(token, plan, evidence, rowCount, byteCount);
        }

        @Override
        public boolean commitConfirmed(
                TaskEvidence evidence, long rowCount, long byteCount,
                String commitReference) {
            return store.commitConfirmed(
                    token, plan, evidence, rowCount, byteCount, commitReference);
        }

        @Override
        public boolean rollbackConfirmed(TaskEvidence evidence, String errorCode) {
            return store.rollbackConfirmed(token, plan, evidence, errorCode);
        }

        @Override
        public boolean failed(
                TaskEvidence evidence,
                String errorCode,
                boolean attempted,
                boolean rollbackConfirmed,
                boolean continued) {
            return store.fail(
                    token, plan, evidence, errorCode,
                    attempted, rollbackConfirmed, continued);
        }

        @Override
        public boolean outcomeUnknown(TaskEvidence evidence, String errorCode) {
            return store.unknown(token, plan, evidence, errorCode);
        }
    }
}

final class ProcedureExecutionJournalException extends RuntimeException {

    private static final long serialVersionUID = 1L;
}

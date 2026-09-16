package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;
import tr.com.innova.akis.execution.JdbcPinnedSchemaSnapshotStore.PinnedProcedureTarget;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.MutationOutcome;
import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;

/** Claims and executes one active, published Procedure run with lease supervision. */
@Component
@ConditionalOnProperty(name = "akis.execution.procedure-runtime-enabled", havingValue = "true")
final class ProcedureWorkerOrchestrator {

    private final WorkerLeaseService leases;
    private final PinnedExecutionContextPort executions;
    private final ProcedureRuntimePlanResolver plans;
    private final JdbcPinnedSchemaSnapshotStore snapshots;
    private final RuntimeOracleConnectionProvider connections;
    private final ObjectMapper objectMapper;
    private final RunExecutionTransitionPort transitions;
    private final ProcedureRunHandler handler;
    private StagedWorkerOrchestrator staged;
    @org.springframework.beans.factory.annotation.Autowired
    void configureStagedWorker(StagedWorkerOrchestrator worker) { this.staged=worker; }

    ProcedureWorkerOrchestrator(
            WorkerLeaseService leases,
            PinnedExecutionContextPort executions,
            ProcedureRuntimePlanResolver plans,
            JdbcPinnedSchemaSnapshotStore snapshots,
            RuntimeOracleConnectionProvider connections,
            ObjectMapper objectMapper,
            RunExecutionTransitionPort transitions,
            JdbcProcedureExecutionJournalStore journals,
            ProcedureTaskExecutorSessionFactory executors) {
        this.leases = leases;
        this.executions = executions;
        this.plans = plans;
        this.snapshots = snapshots;
        this.connections = connections;
        this.objectMapper = objectMapper;
        this.transitions = transitions;
        this.handler = new ProcedureRunHandler(journals::forExecution, executors);
    }

    RunOnceResult runOnce(WorkerIdentity worker, Duration lease) {
        Optional<ClaimedRun> claimed;
        try {
            claimed = leases.claimForPreflight(worker, lease);
        }
        catch (RuntimeException exception) {
            return new StoppedFailClosed("CLAIM_UNCONFIRMED");
        }
        if (claimed.isEmpty()) return new Idle();
        LeaseGate gate;
        try {
            gate = HeartbeatSupervisor.start(leases, claimed.get().token(), lease);
        }
        catch (RuntimeException exception) {
            return new StoppedFailClosed("HEARTBEAT_UNAVAILABLE");
        }
        try (gate) {
            return runClaimed(claimed.get(), gate);
        }
        catch (RuntimeException exception) {
            return new StoppedFailClosed("WORKER_BOUNDARY_FAILED");
        }
    }

    private RunOnceResult runClaimed(ClaimedRun claimed, LeaseGate gate) {
        PinnedExecutionContext context;
        ProcedureRuntimePlan plan;
        ProcedureRuntimePlan.Task targetTask;
        ProcedureRuntimePlan.TaskBinding binding;
        PinnedProcedureTarget pinned;
        try {
            context = executions.find(claimed.token().runUuid()).orElseThrow();
            if (!Objects.equals(context.releaseHash(), claimed.releaseHash())
                    || !Objects.equals(context.planHash(), claimed.planHash())) {
                throw new IllegalStateException();
            }
            if(StagedRuntimePlanResolver.CAPABILITY.equals(context.physicalManifest().path("runtimeCapability").asText())) {
                if(staged==null) throw new IllegalStateException();
                return staged.run(context,gate);
            }
            plan = plans.resolve(claimed.releaseHash(), claimed.planHash(),
                    context.scenarioPlan(), context.physicalManifest());
            targetTask = representativeTarget(plan);
            binding = plan.bindings().get(targetTask.id());
            pinned = snapshots.loadProcedureTarget(plan, targetTask, binding, "AKTIF");
        }
        catch (RuntimeException exception) {
            failPreflight(gate, "PROCEDURE_PINNED_CONTEXT_INVALID");
            return new FailedSafely("PROCEDURE_PINNED_CONTEXT_INVALID");
        }

        CanonicalTargetIdentity identity;
        RuntimeOracleSession session = null;
        try {
            PilotRuntimePlan.DatasetBinding adapted = ProcedureOracleBindingAdapter.target(binding);
            gate.checkpoint();
            session = connections.openTargetIdentityRead(adapted);
            new JdbcOracleSchemaPreflight(objectMapper).verifyTarget(
                    targetPlan(plan, adapted), session.connection(),
                    new ExpectedSnapshot(pinned.snapshot().schemaSnapshotUuid(),
                            pinned.snapshot().body()));
            identity = new JdbcOracleTargetIdentityReader().read(
                    session.connection(), binding.owner(), "TABLE", binding.objectName());
            gate.checkpoint();
        }
        catch (RuntimeException exception) {
            failPreflight(gate, "PROCEDURE_TARGET_IDENTITY_FAILED");
            return new FailedSafely("PROCEDURE_TARGET_IDENTITY_FAILED");
        }
        finally {
            if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        }

        TargetFenceToken target;
        try {
            target = gate.execute(run -> leases.acquireTarget(run,
                    identity.canonicalTargetHash(), identity.targetIdentityVersion()));
        }
        catch (RuntimeException exception) {
            return new StoppedFailClosed("TARGET_FENCE_UNCONFIRMED");
        }

        ActiveExecutionToken active = new ActiveExecutionToken(gate.checkpoint(), target);
        ProcedureRunHandler.RunResult result = handler.execute(active, plan);
        if (result instanceof ProcedureRunHandler.Completed completed) {
            return new Succeeded(completed.completedTasks(), completed.warnings(),
                    completed.rowCount(), completed.byteCount());
        }
        if (result instanceof ProcedureRunHandler.FailedSafely failed) {
            return new FailedSafely(failed.errorCode());
        }
        if (result instanceof ProcedureRunHandler.UnknownOutcome unknown) {
            return new OutcomeUnknown(unknown.errorCode());
        }
        return new StoppedFailClosed("PROCEDURE_HANDLER_STOPPED");
    }

    private void failPreflight(LeaseGate gate, String code) {
        try {
            gate.completeTerminal(token -> transitions.failPreflightSafely(token, code),
                    result -> result != null && result.outcome() == MutationOutcome.ACCEPTED);
        }
        catch (RuntimeException ignored) {
            // Lease expiry reaper remains the authoritative recovery path.
        }
    }

    private ProcedureRuntimePlan.Task representativeTarget(ProcedureRuntimePlan plan) {
        List<ProcedureRuntimePlan.Task> tasks = plan.tasks().stream()
                .filter(task -> task.connectionRole() == ProcedureRuntimePlan.ConnectionRole.TARGET)
                .filter(task -> task.riskClass() == ProcedureRuntimePlan.RiskClass.DML)
                .filter(task -> task.input() != null).toList();
        if (tasks.size() != 1) throw new IllegalArgumentException();
        return tasks.getFirst();
    }

    private PilotRuntimePlan targetPlan(
            ProcedureRuntimePlan plan, PilotRuntimePlan.DatasetBinding target) {
        return new PilotRuntimePlan(PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(), plan.releaseHash(), plan.scenarioPlanHash(),
                plan.definitionUuid(), plan.definitionVersionUuid(), 1, null, target,
                List.of(), PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                plan.canonicalPlan());
    }

    sealed interface RunOnceResult permits Idle, Succeeded, FailedSafely,
            OutcomeUnknown, StoppedFailClosed { }
    record Idle() implements RunOnceResult { }
    record Succeeded(int completedTasks, int warnings, long rowCount, long byteCount)
            implements RunOnceResult { }
    record FailedSafely(String errorCode) implements RunOnceResult { }
    record OutcomeUnknown(String errorCode) implements RunOnceResult { }
    record StoppedFailClosed(String errorCode) implements RunOnceResult { }
}

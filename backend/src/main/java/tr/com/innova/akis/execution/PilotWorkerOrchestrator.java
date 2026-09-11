package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import tr.com.innova.akis.execution.OracleAtomicPublishPort.AlreadyRecorded;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.CommitConfirmed;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.EvidenceConflict;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.FencedOut;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OracleAtomicPublishCommand;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OracleAtomicPublishResult;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.PublishReceipt;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.ReadSucceeded;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.SourceReadCommand;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.SourceReadResult;
import tr.com.innova.akis.execution.OracleTargetFencePort.FenceReceipt;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceCommand;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceResult;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.IdentityReadSucceeded;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityEvidence;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityReadCommand;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityReadResult;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.MutationOutcome;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.MutationResult;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.PublishIntentEvidence;
import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

/** Explicit, bounded execution of at most one claimed Oracle pilot run. */
final class PilotWorkerOrchestrator {

    private final WorkerLeaseService leases;
    private final PinnedExecutionContextPort executions;
    private final RuntimePlanLoader plans;
    private final PinnedSchemaSnapshotPort snapshots;
    private final OracleTargetIdentityReadPort targetIdentities;
    private final OraclePilotSourceReadPort sourceReads;
    private final PilotPublishKeyV1 publishKeys;
    private final RunExecutionTransitionPort transitions;
    private final OracleTargetFencePort targetFences;
    private final OracleAtomicPublishPort publications;
    private final LeaseGateFactory gates;

    PilotWorkerOrchestrator(
            WorkerLeaseService leases,
            PinnedExecutionContextPort executions,
            PilotRuntimePlanResolver plans,
            PinnedSchemaSnapshotPort snapshots,
            OracleTargetIdentityReadPort targetIdentities,
            OraclePilotSourceReadPort sourceReads,
            PilotPublishKeyV1 publishKeys,
            RunExecutionTransitionPort transitions,
            OracleTargetFencePort targetFences,
            OracleAtomicPublishPort publications) {
        this(
                leases,
                executions,
                runtimePlanLoader(plans),
                snapshots,
                targetIdentities,
                sourceReads,
                publishKeys,
                transitions,
                targetFences,
                publications,
                (token, lease) -> HeartbeatSupervisor.start(leases, token, lease));
    }

    PilotWorkerOrchestrator(
            WorkerLeaseService leases,
            PinnedExecutionContextPort executions,
            RuntimePlanLoader plans,
            PinnedSchemaSnapshotPort snapshots,
            OracleTargetIdentityReadPort targetIdentities,
            OraclePilotSourceReadPort sourceReads,
            PilotPublishKeyV1 publishKeys,
            RunExecutionTransitionPort transitions,
            OracleTargetFencePort targetFences,
            OracleAtomicPublishPort publications,
            LeaseGateFactory gates) {
        this.leases = Objects.requireNonNull(leases, "Worker leases are required.");
        this.executions = Objects.requireNonNull(executions, "Pinned executions are required.");
        this.plans = Objects.requireNonNull(plans, "Runtime plans are required.");
        this.snapshots = Objects.requireNonNull(snapshots, "Pinned snapshots are required.");
        this.targetIdentities = Objects.requireNonNull(
                targetIdentities, "Target identity reads are required.");
        this.sourceReads = Objects.requireNonNull(sourceReads, "Source reads are required.");
        this.publishKeys = Objects.requireNonNull(publishKeys, "Publish keys are required.");
        this.transitions = Objects.requireNonNull(transitions, "Run transitions are required.");
        this.targetFences = Objects.requireNonNull(targetFences, "Target fences are required.");
        this.publications = Objects.requireNonNull(publications, "Publications are required.");
        this.gates = Objects.requireNonNull(gates, "Lease gate factory is required.");
    }

    RunOnceResult runOnce(WorkerIdentity worker, Duration lease) {
        Optional<ClaimedRun> claimed;
        try {
            claimed = exactOneRetry(() -> leases.claimForPreflight(worker, lease));
        }
        catch (RuntimeException exception) {
            return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
        }
        if (claimed == null) {
            return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
        }
        if (claimed.isEmpty()) {
            return new Idle();
        }

        LeaseGate gate;
        try {
            gate = gates.open(claimed.get().token(), lease);
            if (gate == null) {
                return new StoppedFailClosed(FailureCode.LEASE_GATE_UNAVAILABLE);
            }
        }
        catch (RuntimeException exception) {
            return new StoppedFailClosed(FailureCode.LEASE_GATE_UNAVAILABLE);
        }

        try {
            return runClaimed(claimed.get(), gate);
        }
        catch (LeaseGateException exception) {
            return stoppedForGate(exception);
        }
        catch (RuntimeException exception) {
            return new StoppedFailClosed(FailureCode.CONTROL_PLANE_UNCONFIRMED);
        }
        finally {
            closeQuietly(gate);
        }
    }

    private RunOnceResult runClaimed(ClaimedRun claimed, LeaseGate gate) {
        PinnedExecutionContext execution;
        PilotRuntimePlan plan;
        PinnedSnapshots pinnedSnapshots;
        try {
            execution = executions.find(claimed.token().runUuid()).orElse(null);
            if (!matches(claimed, execution)) {
                return failPreflight(gate, FailureCode.PINNED_CONTEXT_INVALID);
            }
            plan = plans.load(claimed, execution);
            if (!matches(plan, claimed, execution)) {
                return failPreflight(gate, FailureCode.RUNTIME_PLAN_INVALID);
            }
            pinnedSnapshots = snapshots.load(plan);
            if (!matches(pinnedSnapshots, plan, execution)) {
                return failPreflight(gate, FailureCode.PINNED_SNAPSHOT_INVALID);
            }
        }
        catch (RuntimeException exception) {
            return failPreflight(gate, FailureCode.PINNED_AGGREGATE_UNAVAILABLE);
        }

        TargetIdentityReadResult identityResult;
        try {
            identityResult = longOracleCall(
                    gate,
                    () -> targetIdentities.read(new TargetIdentityReadCommand(
                            plan, execution, pinnedSnapshots.target())));
        }
        catch (OracleBoundaryException exception) {
            return failPreflight(gate, FailureCode.TARGET_IDENTITY_CALL_FAILED);
        }
        if (!(identityResult instanceof IdentityReadSucceeded identitySuccess)) {
            return failPreflight(
                    gate,
                    identityResult instanceof OracleTargetIdentityReadPort.NotAttempted
                            ? FailureCode.TARGET_IDENTITY_NOT_ATTEMPTED
                            : FailureCode.TARGET_IDENTITY_FAILED_SAFE);
        }
        TargetIdentityEvidence identity = identitySuccess.evidence();
        if (!matches(identity, plan)) {
            return failPreflight(gate, FailureCode.TARGET_IDENTITY_INVALID);
        }

        TargetFenceToken target;
        try {
            target = gate.execute(run -> exactOneRetry(() -> leases.acquireTarget(
                    run, identity.canonicalTargetHash(), identity.targetIdentityVersion())));
        }
        catch (LeaseGateException exception) {
            return stoppedForGate(exception);
        }
        if (!matches(target, claimed.token(), identity)) {
            return new StoppedFailClosed(FailureCode.TARGET_CLAIM_INVALID);
        }

        SourceReadResult sourceResult;
        try {
            sourceResult = longOracleCall(
                    gate,
                    () -> sourceReads.read(new SourceReadCommand(
                            plan, execution, pinnedSnapshots)));
        }
        catch (OracleBoundaryException exception) {
            return failActive(gate, target, FailureCode.SOURCE_CALL_FAILED);
        }
        if (!(sourceResult instanceof ReadSucceeded readSucceeded)) {
            FailureCode failure = sourceResult instanceof OraclePilotSourceReadPort.NotAttempted
                    ? FailureCode.SOURCE_NOT_ATTEMPTED
                    : sourceResult instanceof OraclePilotSourceReadPort.OutcomeUnknown
                    ? FailureCode.SOURCE_OUTCOME_UNKNOWN
                    : FailureCode.SOURCE_FAILED_SAFE;
            return failActive(gate, target, failure);
        }
        OraclePilotBatch batch = readSucceeded.batch();
        if (!matches(batch, plan)) {
            return failActive(gate, target, FailureCode.SOURCE_BATCH_INVALID);
        }

        MutationResult preflight = gate.execute(run -> exactOneRetry(
                () -> transitions.completePreflight(active(run, target))));
        if (!accepted(preflight)) {
            return new StoppedFailClosed(FailureCode.PREFLIGHT_TRANSITION_REJECTED);
        }

        PublishIntentEvidence evidence;
        try {
            String publishKeyHash = publishKeys.create(
                    execution.jobRequestUuid(), plan.runtimePlanHash(),
                    target.canonicalTargetHash(), PilotPublishKeyV1.PILOT_STEP_CODE);
            evidence = new PublishIntentEvidence(
                    plan.runtimePlanHash(), publishKeyHash, batch.payloadHash(),
                    batch.rows().size(), batch.byteCount());
        }
        catch (RuntimeException exception) {
            return failActive(gate, target, FailureCode.PUBLISH_EVIDENCE_INVALID);
        }

        gate.checkpoint();
        MutationResult publishIntent = gate.execute(run -> exactOneRetry(
                () -> transitions.beginPublish(active(run, target), evidence)));
        if (!accepted(publishIntent)) {
            return new StoppedFailClosed(FailureCode.PUBLISH_INTENT_REJECTED);
        }

        OracleTargetFenceResult fenceResult;
        try {
            fenceResult = longOracleCall(
                    gate,
                    () -> targetFences.acquire(
                            new OracleTargetFenceCommand(plan, execution, target)));
        }
        catch (OracleBoundaryException exception) {
            return markUnknown(gate, target, FailureCode.FENCE_CALL_FAILED);
        }
        if (fenceResult instanceof OracleTargetFencePort.OutcomeUnknown) {
            return markUnknown(gate, target, FailureCode.FENCE_OUTCOME_UNKNOWN);
        }
        if (fenceResult instanceof OracleTargetFencePort.NotAttempted) {
            return failActive(gate, target, FailureCode.FENCE_NOT_ATTEMPTED);
        }
        if (fenceResult instanceof OracleTargetFencePort.SafeFailure) {
            return failActive(gate, target, FailureCode.FENCE_FAILED_SAFE);
        }
        if (fenceResult instanceof OracleTargetFencePort.FencedOut) {
            return failActive(gate, target, FailureCode.FENCE_FENCED_OUT);
        }
        if (!(fenceResult instanceof OracleTargetFencePort.CommitConfirmed fenceSuccess)
                || !matches(fenceSuccess.receipt(), target)) {
            return markUnknown(gate, target, FailureCode.FENCE_RECEIPT_INVALID);
        }

        OracleAtomicPublishResult publishResult;
        try {
            publishResult = longOracleCall(
                    gate,
                    () -> publications.publish(new OracleAtomicPublishCommand(
                            plan, execution, target, pinnedSnapshots, batch)));
        }
        catch (OracleBoundaryException exception) {
            return markUnknown(gate, target, FailureCode.PUBLISH_CALL_FAILED);
        }
        if (publishResult instanceof OracleAtomicPublishPort.OutcomeUnknown) {
            return markUnknown(gate, target, FailureCode.PUBLISH_OUTCOME_UNKNOWN);
        }
        if (publishResult instanceof OracleAtomicPublishPort.NotAttempted) {
            return failActive(gate, target, FailureCode.PUBLISH_NOT_ATTEMPTED);
        }
        if (publishResult instanceof OracleAtomicPublishPort.SafeFailure) {
            return failActive(gate, target, FailureCode.PUBLISH_FAILED_SAFE);
        }
        if (publishResult instanceof EvidenceConflict) {
            return failActive(gate, target, FailureCode.PUBLISH_EVIDENCE_CONFLICT);
        }
        if (publishResult instanceof FencedOut) {
            return failActive(gate, target, FailureCode.PUBLISH_FENCED_OUT);
        }

        PublishReceipt receipt = publishResult instanceof CommitConfirmed committed
                ? committed.receipt()
                : publishResult instanceof AlreadyRecorded recorded
                ? recorded.receipt() : null;
        if (!matches(receipt, evidence)) {
            return markUnknown(gate, target, FailureCode.PUBLISH_RECEIPT_INVALID);
        }
        return completeSuccessfully(gate, target, evidence);
    }

    private RunOnceResult failPreflight(LeaseGate gate, FailureCode failure) {
        return terminal(
                gate,
                token -> exactOneRetry(
                        () -> transitions.failPreflightSafely(token, failure.name())),
                new FailedSafely(failure));
    }

    private RunOnceResult failActive(
            LeaseGate gate, TargetFenceToken target, FailureCode failure) {
        return terminal(
                gate,
                token -> exactOneRetry(
                        () -> transitions.failSafely(active(token, target), failure.name())),
                new FailedSafely(failure));
    }

    private RunOnceResult markUnknown(
            LeaseGate gate, TargetFenceToken target, FailureCode failure) {
        return terminal(
                gate,
                token -> exactOneRetry(
                        () -> transitions.markOutcomeUnknown(active(token, target))),
                new OutcomeUnknownRecorded(failure));
    }

    private RunOnceResult completeSuccessfully(
            LeaseGate gate,
            TargetFenceToken target,
            PublishIntentEvidence evidence) {
        return terminal(
                gate,
                token -> exactOneRetry(() -> transitions.completeSuccessfully(
                        active(token, target), evidence)),
                new Succeeded());
    }

    private RunOnceResult terminal(
            LeaseGate gate,
            LeaseGate.TerminalOperation<MutationResult> operation,
            RunOnceResult acceptedResult) {
        MutationResult result = gate.completeTerminal(operation, this::accepted);
        return accepted(result)
                ? acceptedResult
                : new StoppedFailClosed(FailureCode.TERMINAL_TRANSITION_REJECTED);
    }

    private <T> T longOracleCall(LeaseGate gate, Operation<T> operation) {
        gate.checkpoint();
        T result = null;
        boolean failed = false;
        try {
            result = operation.run();
        }
        catch (RuntimeException exception) {
            failed = true;
        }
        gate.checkpoint();
        if (failed) {
            throw new OracleBoundaryException();
        }
        return result;
    }

    private <T> T exactOneRetry(Operation<T> operation) {
        try {
            return operation.run();
        }
        catch (RuntimeException first) {
            try {
                return operation.run();
            }
            catch (RuntimeException second) {
                throw new AcknowledgementUnconfirmedException();
            }
        }
    }

    private ActiveExecutionToken active(
            RunLeaseToken run, TargetFenceToken target) {
        return new ActiveExecutionToken(run, target);
    }

    private boolean accepted(MutationResult result) {
        return result != null && result.outcome() == MutationOutcome.ACCEPTED;
    }

    private boolean matches(ClaimedRun claimed, PinnedExecutionContext execution) {
        return claimed != null && claimed.token() != null && execution != null
                && Objects.equals(claimed.token().runUuid(), execution.runUuid())
                && equalHash(claimed.releaseHash(), execution.releaseHash())
                && equalHash(claimed.planHash(), execution.planHash());
    }

    private boolean matches(
            PilotRuntimePlan plan, ClaimedRun claimed, PinnedExecutionContext execution) {
        return plan != null && plan.source() != null && plan.target() != null
                && equalHash(plan.releaseHash(), claimed.releaseHash())
                && equalHash(plan.scenarioPlanHash(), claimed.planHash())
                && Objects.equals(execution.runUuid(), claimed.token().runUuid());
    }

    private boolean matches(
            PinnedSnapshots pinned, PilotRuntimePlan plan, PinnedExecutionContext execution) {
        return pinned != null && pinned.projectUuid() != null
                && Objects.equals(pinned.publicationUuid(), execution.publicationUuid())
                && pinned.source() != null && pinned.target() != null
                && Objects.equals(
                        pinned.source().schemaSnapshotUuid(),
                        plan.source().schemaSnapshotUuid())
                && Objects.equals(
                        pinned.target().schemaSnapshotUuid(),
                        plan.target().schemaSnapshotUuid())
                && equalHash(
                        pinned.source().verifiedFingerprint(),
                        plan.source().schemaSnapshotFingerprint())
                && equalHash(
                        pinned.target().verifiedFingerprint(),
                        plan.target().schemaSnapshotFingerprint());
    }

    private boolean matches(TargetIdentityEvidence identity, PilotRuntimePlan plan) {
        return identity != null
                && identity.targetIdentityVersion()
                        == OracleTargetIdentityV1.TARGET_IDENTITY_VERSION
                && identity.databaseUniqueName() != null
                && !identity.databaseUniqueName().isBlank()
                && identity.containerName() != null
                && !identity.containerName().isBlank()
                && Objects.equals(identity.owner(), plan.target().owner())
                && Objects.equals(identity.objectType(), plan.target().dataObjectType().name())
                && Objects.equals(identity.objectName(), plan.target().objectName())
                && hash(identity.canonicalTargetHash());
    }

    private boolean matches(
            TargetFenceToken target,
            RunLeaseToken claimed,
            TargetIdentityEvidence identity) {
        return target != null && target.targetResourceUuid() != null
                && target.targetGeneration() > 0
                && Objects.equals(target.runUuid(), claimed.runUuid())
                && Objects.equals(target.workerReference(), claimed.workerReference())
                && target.runGeneration() == claimed.generation()
                && target.targetIdentityVersion() == identity.targetIdentityVersion()
                && equalHash(target.canonicalTargetHash(), identity.canonicalTargetHash());
    }

    private boolean matches(OraclePilotBatch batch, PilotRuntimePlan plan) {
        return batch != null && batch.rows() != null && batch.columns() != null
                && batch.rows().size() <= plan.maximumSourceRows()
                && batch.byteCount() >= 0 && hash(batch.payloadHash())
                && equalHash(batch.runtimePlanHash(), plan.runtimePlanHash());
    }

    private boolean matches(FenceReceipt receipt, TargetFenceToken target) {
        return receipt != null
                && Objects.equals(receipt.targetResourceUuid(), target.targetResourceUuid())
                && receipt.targetGeneration() == target.targetGeneration()
                && equalHash(receipt.canonicalTargetHash(), target.canonicalTargetHash());
    }

    private boolean matches(PublishReceipt receipt, PublishIntentEvidence evidence) {
        return receipt != null
                && equalHash(receipt.publishKeyHash(), evidence.publishKeyHash())
                && equalHash(receipt.payloadHash(), evidence.payloadHash())
                && receipt.rowCount() == evidence.rowCount()
                && receipt.byteCount() == evidence.byteCount();
    }

    private boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean equalHash(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static RuntimePlanLoader runtimePlanLoader(PilotRuntimePlanResolver plans) {
        PilotRuntimePlanResolver safe = Objects.requireNonNull(
                plans, "Runtime plan resolver is required.");
        return (claimed, execution) -> safe.resolve(
                claimed.releaseHash(), claimed.planHash(),
                execution.scenarioPlan(), execution.physicalManifest());
    }

    private RunOnceResult stoppedForGate(LeaseGateException exception) {
        FailureCode failure = exception.failure() == LeaseGateException.Failure.LEASE_AUTHORITY_LOST
                ? FailureCode.LEASE_AUTHORITY_LOST
                : FailureCode.CONTROL_PLANE_UNCONFIRMED;
        return new StoppedFailClosed(failure);
    }

    private void closeQuietly(LeaseGate gate) {
        try {
            gate.close();
        }
        catch (RuntimeException ignored) {
            // The returned result is already sanitized and no work continues.
        }
    }

    sealed interface RunOnceResult
            permits Idle, Succeeded, FailedSafely, OutcomeUnknownRecorded,
                    StoppedFailClosed {
    }

    record Idle() implements RunOnceResult {
    }

    record Succeeded() implements RunOnceResult {
    }

    record FailedSafely(FailureCode failure) implements RunOnceResult {
    }

    record OutcomeUnknownRecorded(FailureCode failure) implements RunOnceResult {
    }

    record StoppedFailClosed(FailureCode failure) implements RunOnceResult {
    }

    enum FailureCode {
        CONTROL_PLANE_UNCONFIRMED,
        LEASE_GATE_UNAVAILABLE,
        LEASE_AUTHORITY_LOST,
        PINNED_CONTEXT_INVALID,
        RUNTIME_PLAN_INVALID,
        PINNED_SNAPSHOT_INVALID,
        PINNED_AGGREGATE_UNAVAILABLE,
        TARGET_IDENTITY_CALL_FAILED,
        TARGET_IDENTITY_NOT_ATTEMPTED,
        TARGET_IDENTITY_FAILED_SAFE,
        TARGET_IDENTITY_INVALID,
        TARGET_CLAIM_INVALID,
        SOURCE_CALL_FAILED,
        SOURCE_NOT_ATTEMPTED,
        SOURCE_FAILED_SAFE,
        SOURCE_OUTCOME_UNKNOWN,
        SOURCE_BATCH_INVALID,
        PREFLIGHT_TRANSITION_REJECTED,
        PUBLISH_EVIDENCE_INVALID,
        PUBLISH_INTENT_REJECTED,
        FENCE_CALL_FAILED,
        FENCE_NOT_ATTEMPTED,
        FENCE_FAILED_SAFE,
        FENCE_FENCED_OUT,
        FENCE_OUTCOME_UNKNOWN,
        FENCE_RECEIPT_INVALID,
        PUBLISH_CALL_FAILED,
        PUBLISH_NOT_ATTEMPTED,
        PUBLISH_FAILED_SAFE,
        PUBLISH_EVIDENCE_CONFLICT,
        PUBLISH_FENCED_OUT,
        PUBLISH_OUTCOME_UNKNOWN,
        PUBLISH_RECEIPT_INVALID,
        TERMINAL_TRANSITION_REJECTED
    }

    @FunctionalInterface
    interface RuntimePlanLoader {

        PilotRuntimePlan load(ClaimedRun claimed, PinnedExecutionContext execution);
    }

    @FunctionalInterface
    interface LeaseGateFactory {

        LeaseGate open(RunLeaseToken initialToken, Duration lease);
    }

    @FunctionalInterface
    private interface Operation<T> {

        T run();
    }

    private static final class AcknowledgementUnconfirmedException
            extends RuntimeException {
    }

    private static final class OracleBoundaryException extends RuntimeException {
    }
}

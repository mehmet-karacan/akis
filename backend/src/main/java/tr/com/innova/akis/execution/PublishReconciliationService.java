package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Outcome;
import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Result;
import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.BarrierEvidence;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.PinnedReconciliation;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;
import tr.com.innova.akis.execution.RunReconciliationPort.CompletionResult;
import tr.com.innova.akis.execution.RunReconciliationPort.Conflict;
import tr.com.innova.akis.execution.RunReconciliationPort.HeartbeatResult;
import tr.com.innova.akis.execution.RunReconciliationPort.MutationOutcome;
import tr.com.innova.akis.execution.RunReconciliationPort.NotPublished;
import tr.com.innova.akis.execution.RunReconciliationPort.PublishEvidence;
import tr.com.innova.akis.execution.RunReconciliationPort.Published;
import tr.com.innova.akis.execution.RunReconciliationPort.ReconciliationCompletion;
import tr.com.innova.akis.execution.RunReconciliationPort.ReconciliationLeaseToken;

/**
 * Composes PostgreSQL reconciliation ownership with the fresh Oracle evidence
 * read. No scheduler or endpoint invokes this service yet.
 */
@Service
final class PublishReconciliationService {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final RunReconciliationPort runs;
    private final PinnedPublishReconciliationPort pinnedEvidence;
    private final OraclePublishReconciliationReadPort oracle;

    PublishReconciliationService(
            RunReconciliationPort runs,
            PinnedPublishReconciliationPort pinnedEvidence,
            OraclePublishReconciliationReadPort oracle) {
        this.runs = Objects.requireNonNull(runs, "Run reconciliation port is required.");
        this.pinnedEvidence = Objects.requireNonNull(
                pinnedEvidence, "Pinned reconciliation evidence is required.");
        this.oracle = Objects.requireNonNull(
                oracle, "Oracle reconciliation reader is required.");
    }

    ServiceResult reconcile(UUID runUuid, WorkerIdentity worker, Duration lease) {
        if (!validRequest(runUuid, worker, lease)) {
            return ServiceResult.failed(Failure.INVALID_REQUEST);
        }

        Optional<ReconciliationLeaseToken> claimed;
        try {
            claimed = runs.claim(runUuid, worker, lease);
        }
        catch (RuntimeException exception) {
            return ServiceResult.failed(Failure.CONTROL_PLANE_UNAVAILABLE);
        }
        if (claimed.isEmpty()) {
            return ServiceResult.failed(Failure.CLAIM_NOT_ACQUIRED);
        }
        ReconciliationLeaseToken claim = claimed.get();

        Optional<PinnedReconciliation> loaded;
        try {
            loaded = pinnedEvidence.find(runUuid);
        }
        catch (RuntimeException exception) {
            return ServiceResult.failed(Failure.CONTROL_PLANE_UNAVAILABLE);
        }
        if (loaded.isEmpty()) {
            return ServiceResult.failed(Failure.PINNED_EVIDENCE_NOT_FOUND);
        }
        PinnedReconciliation aggregate = loaded.get();
        if (!exactBarrier(runUuid, worker, claim, aggregate)) {
            return ServiceResult.failed(Failure.BARRIER_MISMATCH);
        }

        Result observed;
        try {
            observed = oracle.reconcile(runUuid);
        }
        catch (RuntimeException exception) {
            return ServiceResult.unknown(Failure.ORACLE_OUTCOME_UNKNOWN);
        }
        if (!validOracleResult(observed)) {
            return ServiceResult.unknown(Failure.ORACLE_OUTCOME_UNKNOWN);
        }
        if (observed.outcome() == Outcome.OUTCOME_UNKNOWN) {
            return ServiceResult.unknown(Failure.ORACLE_OUTCOME_UNKNOWN);
        }

        HeartbeatResult heartbeat;
        try {
            heartbeat = runs.heartbeat(claim, lease);
        }
        catch (RuntimeException exception) {
            return ServiceResult.failed(Failure.CONTROL_PLANE_UNAVAILABLE);
        }
        if (heartbeat == null || heartbeat.outcome() != MutationOutcome.ACCEPTED
                || !sameLease(claim, heartbeat.refreshedToken())) {
            return ServiceResult.failed(Failure.STALE_RECONCILIATION_LEASE);
        }

        ReconciliationCompletion completion = completion(observed.outcome(), aggregate);
        CompletionResult completed;
        try {
            completed = runs.complete(heartbeat.refreshedToken(), completion);
        }
        catch (RuntimeException exception) {
            try {
                // The first transaction may have committed while its response was
                // lost. Retry exactly once with the identical idempotency tuple;
                // the adapter performs exact terminal-state readback.
                completed = runs.complete(heartbeat.refreshedToken(), completion);
            }
            catch (RuntimeException retryException) {
                return ServiceResult.failed(Failure.CONTROL_PLANE_UNAVAILABLE);
            }
        }
        if (completed == null || completed.outcome() != MutationOutcome.ACCEPTED) {
            return ServiceResult.failed(Failure.COMPLETION_REJECTED);
        }
        return switch (observed.outcome()) {
            case PUBLISHED -> ServiceResult.completed(ServiceOutcome.PUBLISHED);
            case NOT_PUBLISHED -> ServiceResult.completed(ServiceOutcome.NOT_PUBLISHED);
            case CONFLICT -> ServiceResult.completed(ServiceOutcome.CONFLICT);
            case OUTCOME_UNKNOWN -> throw new IllegalStateException(
                    "Unknown Oracle outcome cannot be completed.");
        };
    }

    private boolean exactBarrier(
            UUID requestedRunUuid,
            WorkerIdentity worker,
            ReconciliationLeaseToken token,
            PinnedReconciliation pinned) {
        if (token == null || token.runUuid() == null || token.workerReference() == null
                || token.runGeneration() < 1 || token.targetResourceUuid() == null
                || token.targetGeneration() < 1 || token.leaseDeadline() == null
                || pinned == null || pinned.plan() == null
                || pinned.execution() == null || pinned.originalPublish() == null
                || pinned.barrier() == null) {
            return false;
        }
        PilotRuntimePlan plan = pinned.plan();
        PinnedExecutionContext execution = pinned.execution();
        PilotPublishIntent original = pinned.originalPublish();
        BarrierEvidence barrier = pinned.barrier();
        return execution.jobRequestUuid() != null
                && execution.runUuid() != null
                && execution.publicationUuid() != null
                && execution.attemptNumber() > 0
                && original.projectUuid() != null
                && original.publicationUuid() != null
                && original.jobRequestUuid() != null
                && original.runUuid() != null
                && original.attemptNumber() > 0
                && original.targetResourceUuid() != null
                && original.targetGeneration() > 0
                && barrier.targetResourceUuid() != null
                && barrier.reconciliationWorkerReference() != null
                && !barrier.reconciliationWorkerReference().isBlank()
                && requestedRunUuid.equals(token.runUuid())
                && worker.reference().equals(token.workerReference())
                && requestedRunUuid.equals(barrier.runUuid())
                && token.runGeneration() == barrier.reconciliationRunGeneration()
                && token.workerReference().equals(barrier.reconciliationWorkerReference())
                && token.targetResourceUuid().equals(barrier.targetResourceUuid())
                && token.targetGeneration() == barrier.barrierTargetGeneration()
                && barrier.originalPublishTargetGeneration() > 0
                && barrier.barrierTargetGeneration()
                        == barrier.originalPublishTargetGeneration() + 1
                && requestedRunUuid.equals(original.runUuid())
                && requestedRunUuid.equals(execution.runUuid())
                && Objects.equals(original.jobRequestUuid(), execution.jobRequestUuid())
                && Objects.equals(original.publicationUuid(), execution.publicationUuid())
                && original.attemptNumber() == execution.attemptNumber()
                && Objects.equals(original.targetResourceUuid(),
                        barrier.targetResourceUuid())
                && original.targetGeneration()
                        == barrier.originalPublishTargetGeneration()
                && original.runGeneration() > 0
                && original.runGeneration() != Long.MAX_VALUE
                && barrier.reconciliationRunGeneration()
                        == original.runGeneration() + 1
                && original.targetIdentityVersion()
                        == OracleTargetIdentityV1.TARGET_IDENTITY_VERSION
                && original.targetIdentityVersion() == barrier.targetIdentityVersion()
                && hash(original.canonicalTargetHash())
                && equalHash(original.canonicalTargetHash(),
                        barrier.canonicalTargetHash())
                && hash(original.runtimePlanHash())
                && hash(original.publishKeyHash())
                && hash(original.payloadHash())
                && original.rowCount() >= 0 && original.byteCount() >= 0
                && equalHash(original.releaseHash(), execution.releaseHash())
                && equalHash(original.planHash(), execution.planHash())
                && equalHash(original.releaseHash(), plan.releaseHash())
                && equalHash(original.planHash(), plan.scenarioPlanHash())
                && equalHash(original.runtimePlanHash(), plan.runtimePlanHash());
    }

    private boolean sameLease(
            ReconciliationLeaseToken claimed,
            ReconciliationLeaseToken refreshed) {
        return refreshed != null
                && Objects.equals(claimed.runUuid(), refreshed.runUuid())
                && Objects.equals(claimed.workerReference(), refreshed.workerReference())
                && claimed.runGeneration() == refreshed.runGeneration()
                && Objects.equals(claimed.targetResourceUuid(),
                        refreshed.targetResourceUuid())
                && claimed.targetGeneration() == refreshed.targetGeneration()
                && refreshed.leaseDeadline() != null
                && claimed.leaseDeadline() != null
                && refreshed.leaseDeadline().isAfter(claimed.leaseDeadline());
    }

    private boolean validOracleResult(Result result) {
        if (result == null || result.outcome() == null) {
            return false;
        }
        return switch (result.outcome()) {
            case PUBLISHED, NOT_PUBLISHED -> result.failure() == null;
            case CONFLICT, OUTCOME_UNKNOWN -> result.failure() != null;
        };
    }

    private ReconciliationCompletion completion(
            Outcome outcome, PinnedReconciliation aggregate) {
        return switch (outcome) {
            case PUBLISHED -> {
                PilotPublishIntent intent = aggregate.originalPublish();
                yield new Published(new PublishEvidence(
                        intent.runtimePlanHash(), intent.publishKeyHash(),
                        intent.payloadHash(), intent.rowCount(), intent.byteCount()));
            }
            case NOT_PUBLISHED -> new NotPublished();
            case CONFLICT -> new Conflict();
            case OUTCOME_UNKNOWN -> throw new IllegalStateException(
                    "Unknown Oracle outcome cannot be completed.");
        };
    }

    private boolean validRequest(UUID runUuid, WorkerIdentity worker, Duration lease) {
        return runUuid != null && worker != null && worker.profileUuid() != null
                && worker.reference() != null && !worker.reference().isBlank()
                && worker.reference().equals(worker.reference().trim())
                && worker.reference().length() <= 200
                && lease != null && lease.getNano() == 0
                && lease.getSeconds() >= 30 && lease.getSeconds() <= 300;
    }

    private boolean hash(String value) {
        return value != null && HASH.matcher(value).matches();
    }

    private boolean equalHash(String left, String right) {
        if (!hash(left) || !hash(right)) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    record ServiceResult(ServiceOutcome outcome, Failure failure) {

        static ServiceResult completed(ServiceOutcome outcome) {
            return new ServiceResult(outcome, null);
        }

        static ServiceResult failed(Failure failure) {
            return new ServiceResult(ServiceOutcome.FAILED_CLOSED, failure);
        }

        static ServiceResult unknown(Failure failure) {
            return new ServiceResult(ServiceOutcome.OUTCOME_UNKNOWN, failure);
        }
    }

    enum ServiceOutcome {
        PUBLISHED,
        NOT_PUBLISHED,
        CONFLICT,
        OUTCOME_UNKNOWN,
        FAILED_CLOSED
    }

    enum Failure {
        INVALID_REQUEST,
        CLAIM_NOT_ACQUIRED,
        PINNED_EVIDENCE_NOT_FOUND,
        BARRIER_MISMATCH,
        CONTROL_PLANE_UNAVAILABLE,
        ORACLE_OUTCOME_UNKNOWN,
        STALE_RECONCILIATION_LEASE,
        COMPLETION_REJECTED
    }
}

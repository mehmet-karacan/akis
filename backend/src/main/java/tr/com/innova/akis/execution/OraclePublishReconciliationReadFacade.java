package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Failure;
import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Result;
import tr.com.innova.akis.execution.OracleTargetFencePort.CommitConfirmed;
import tr.com.innova.akis.execution.OracleTargetFencePort.FencedOut;
import tr.com.innova.akis.execution.OracleTargetFencePort.NotAttempted;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceCommand;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceResult;
import tr.com.innova.akis.execution.OracleTargetFencePort.OutcomeUnknown;
import tr.com.innova.akis.execution.OracleTargetFencePort.SafeFailure;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.RecordedEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.FenceEvidence;
import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.BarrierEvidence;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.PinnedReconciliation;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

/**
 * Commits the exact next Oracle fence and then opens a different, fresh Oracle
 * reconciliation session to verify evidence derived from immutable PostgreSQL
 * state. The only external input is the run UUID.
 */
@Component
final class OraclePublishReconciliationReadFacade
        implements OraclePublishReconciliationReadPort {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final PinnedPublishReconciliationPort evidenceStore;
    private final OracleTargetLedgerPort ledger;
    private final OracleTargetFencePort fences;
    private final ReconciliationSessionOpener sessions;
    private final JdbcOracleTargetIdentityReader identityReader;
    private final PilotPublishKeyV1 publishKeys;

    @Autowired
    OraclePublishReconciliationReadFacade(
            RuntimeOracleConnectionProvider connections,
            PinnedPublishReconciliationPort evidenceStore,
            OracleTargetLedgerPort ledger,
            OracleTargetFencePort fences) {
        this(
                evidenceStore,
                ledger,
                fences,
                binding -> session(connections.openTargetReconciliation(binding)),
                new JdbcOracleTargetIdentityReader(),
                new PilotPublishKeyV1());
    }

    OraclePublishReconciliationReadFacade(
            PinnedPublishReconciliationPort evidenceStore,
            OracleTargetLedgerPort ledger,
            OracleTargetFencePort fences,
            ReconciliationSessionOpener sessions,
            JdbcOracleTargetIdentityReader identityReader,
            PilotPublishKeyV1 publishKeys) {
        this.evidenceStore = Objects.requireNonNull(
                evidenceStore, "Pinned reconciliation evidence is required.");
        this.ledger = Objects.requireNonNull(ledger, "Target ledger is required.");
        this.fences = Objects.requireNonNull(fences, "Target fence facade is required.");
        this.sessions = Objects.requireNonNull(sessions, "Reconciliation sessions are required.");
        this.identityReader = Objects.requireNonNull(
                identityReader, "Target identity reader is required.");
        this.publishKeys = Objects.requireNonNull(publishKeys, "Publish keys are required.");
    }

    @Override
    public Result reconcile(UUID runUuid) {
        if (runUuid == null) {
            return Result.conflict(Failure.INVALID_REQUEST);
        }

        Optional<PinnedReconciliation> loaded;
        try {
            loaded = evidenceStore.find(runUuid);
        }
        catch (RuntimeException exception) {
            return Result.unknown(Failure.CONTROL_PLANE_UNAVAILABLE);
        }
        if (loaded.isEmpty()) {
            return Result.conflict(Failure.PINNED_EVIDENCE_NOT_FOUND);
        }

        VerifiedEvidence verified;
        try {
            verified = verifyPinned(runUuid, loaded.get());
        }
        catch (RuntimeException exception) {
            return Result.conflict(Failure.PINNED_EVIDENCE_CONFLICT);
        }

        Result barrierFailure = commitBarrier(verified);
        if (barrierFailure != null) {
            return barrierFailure;
        }

        ReconciliationSessionHandle session;
        try {
            session = sessions.open(verified.plan().target());
        }
        catch (RuntimeException exception) {
            return Result.unknown(Failure.CONNECTION_UNAVAILABLE);
        }

        Result purposeFailure = verifySessionPurpose(session);
        if (purposeFailure != null) {
            return purposeFailure;
        }

        Result observed;
        try {
            Connection connection = session.connection();
            verifyTargetIdentity(connection, verified);
            OracleTargetLedgerPort.ReconciliationSession ledgerSession =
                    ledger.bindReconciliation(connection, verified.barrierContext());
            Optional<FenceEvidence> fence = ledgerSession.readFence();
            if (fence.isEmpty() || !barrierMatches(
                    fence.get(), verified.barrierContext())) {
                observed = Result.conflict(Failure.FENCE_BARRIER_CONFLICT);
            }
            else {
                Optional<RecordedEvidence> recorded = ledgerSession.verifyPublish(
                        verified.publishEvidence());
                observed = recorded
                        .map(value -> recordedResult(value, verified.original()))
                        .orElseGet(Result::notPublished);
            }
        }
        catch (OracleLedgerConflictException exception) {
            observed = Result.conflict(Failure.LEDGER_EVIDENCE_CONFLICT);
        }
        catch (TargetIdentityMismatchException exception) {
            observed = Result.conflict(Failure.TARGET_IDENTITY_MISMATCH);
        }
        catch (RuntimeException exception) {
            observed = Result.unknown(Failure.ORACLE_READ_UNCONFIRMED);
        }

        boolean rollbackConfirmed = rollback(session);
        boolean closeConfirmed = close(session);
        if (!rollbackConfirmed) {
            return Result.unknown(Failure.ROLLBACK_NOT_CONFIRMED);
        }
        if (!closeConfirmed) {
            return Result.unknown(Failure.SESSION_CLOSE_UNCONFIRMED);
        }
        return observed;
    }

    private Result commitBarrier(VerifiedEvidence verified) {
        OracleTargetFenceResult result;
        try {
            result = fences.acquire(new OracleTargetFenceCommand(
                    verified.plan(), verified.execution(), verified.barrierFence()));
        }
        catch (RuntimeException exception) {
            return Result.unknown(Failure.FENCE_BARRIER_NOT_COMMITTED);
        }
        if (result instanceof CommitConfirmed confirmed) {
            var receipt = confirmed.receipt();
            if (receipt == null
                    || !Objects.equals(receipt.targetResourceUuid(),
                            verified.barrierFence().targetResourceUuid())
                    || receipt.targetGeneration()
                            != verified.barrierFence().targetGeneration()
                    || !equalHash(receipt.canonicalTargetHash(),
                            verified.barrierFence().canonicalTargetHash())) {
                return Result.conflict(Failure.PINNED_EVIDENCE_CONFLICT);
            }
            return null;
        }
        if (result instanceof FencedOut) {
            return Result.conflict(Failure.FENCE_BARRIER_CONFLICT);
        }
        if (result instanceof OutcomeUnknown) {
            return Result.unknown(Failure.FENCE_BARRIER_OUTCOME_UNKNOWN);
        }
        if (result instanceof SafeFailure) {
            return Result.unknown(Failure.FENCE_BARRIER_NOT_COMMITTED);
        }
        if (result instanceof NotAttempted notAttempted
                && notAttempted.failure()
                        == OracleTargetFencePort.FailureCode.INVALID_CONTRACT) {
            return Result.conflict(Failure.PINNED_EVIDENCE_CONFLICT);
        }
        return Result.unknown(Failure.FENCE_BARRIER_NOT_COMMITTED);
    }

    private Result verifySessionPurpose(ReconciliationSessionHandle session) {
        RuntimeOracleConnectionProvider.SessionPurpose purpose;
        try {
            purpose = session.purpose();
        }
        catch (RuntimeException exception) {
            close(session);
            return Result.unknown(Failure.ORACLE_READ_UNCONFIRMED);
        }
        if (purpose == RuntimeOracleConnectionProvider.SessionPurpose.TARGET_RECONCILIATION) {
            return null;
        }

        boolean rollbackConfirmed = purpose == RuntimeOracleConnectionProvider.SessionPurpose.SOURCE_READ
                || rollback(session);
        boolean closeConfirmed = close(session);
        if (!rollbackConfirmed) {
            return Result.unknown(Failure.ROLLBACK_NOT_CONFIRMED);
        }
        if (!closeConfirmed) {
            return Result.unknown(Failure.SESSION_CLOSE_UNCONFIRMED);
        }
        return Result.conflict(Failure.INVALID_SESSION_PURPOSE);
    }

    private VerifiedEvidence verifyPinned(
            UUID requestedRunUuid, PinnedReconciliation pinned) {
        if (pinned == null || pinned.plan() == null || pinned.execution() == null
                || pinned.originalPublish() == null || pinned.barrier() == null) {
            throw invalid();
        }
        PilotRuntimePlan plan = pinned.plan();
        PinnedExecutionContext execution = pinned.execution();
        PilotPublishIntent original = pinned.originalPublish();
        BarrierEvidence barrier = pinned.barrier();
        if (!requestedRunUuid.equals(original.runUuid())
                || !requestedRunUuid.equals(barrier.runUuid())
                || original.projectUuid() == null || original.publicationUuid() == null
                || original.jobRequestUuid() == null || original.attemptNumber() < 1
                || original.runGeneration() < 1 || blank(original.workerReference())
                || original.targetResourceUuid() == null || original.targetGeneration() < 1
                || original.targetIdentityVersion()
                        != OracleTargetIdentityV1.TARGET_IDENTITY_VERSION
                || !hash(original.canonicalTargetHash()) || !hash(original.releaseHash())
                || !hash(original.planHash()) || !hash(original.runtimePlanHash())
                || !hash(original.publishKeyHash()) || !hash(original.payloadHash())
                || original.rowCount() < 0 || original.byteCount() < 0
                || !Objects.equals(execution.jobRequestUuid(), original.jobRequestUuid())
                || !Objects.equals(execution.runUuid(), original.runUuid())
                || !Objects.equals(execution.publicationUuid(), original.publicationUuid())
                || execution.attemptNumber() != original.attemptNumber()
                || !equalHash(execution.releaseHash(), original.releaseHash())
                || !equalHash(execution.planHash(), original.planHash())
                || plan.target() == null
                || plan.target().role() != PilotRuntimePlan.DatasetRole.TARGET
                || !equalHash(plan.releaseHash(), original.releaseHash())
                || !equalHash(plan.scenarioPlanHash(), original.planHash())
                || !equalHash(plan.runtimePlanHash(), original.runtimePlanHash())
                || original.runGeneration() == Long.MAX_VALUE
                || barrier.reconciliationRunGeneration() != original.runGeneration() + 1
                || blank(barrier.reconciliationWorkerReference())
                || !Objects.equals(barrier.targetResourceUuid(), original.targetResourceUuid())
                || barrier.originalPublishTargetGeneration() != original.targetGeneration()
                || original.targetGeneration() == Long.MAX_VALUE
                || barrier.barrierTargetGeneration() != original.targetGeneration() + 1
                || !equalHash(barrier.canonicalTargetHash(), original.canonicalTargetHash())
                || barrier.targetIdentityVersion() != original.targetIdentityVersion()) {
            throw invalid();
        }
        String expectedPublishKey = publishKeys.create(
                original.jobRequestUuid(), original.runtimePlanHash(),
                original.canonicalTargetHash(), PilotPublishKeyV1.PILOT_STEP_CODE);
        if (!equalHash(expectedPublishKey, original.publishKeyHash())) {
            throw invalid();
        }
        TargetLedgerContext barrierContext = new TargetLedgerContext(
                original.canonicalTargetHash(), barrier.barrierTargetGeneration(),
                original.jobRequestUuid(), original.runUuid(), original.attemptNumber(),
                original.releaseHash(), original.planHash());
        TargetFenceToken barrierFence = new TargetFenceToken(
                original.runUuid(), barrier.reconciliationWorkerReference(),
                barrier.reconciliationRunGeneration(), barrier.targetResourceUuid(),
                barrier.barrierTargetGeneration(), barrier.canonicalTargetHash(),
                barrier.targetIdentityVersion());
        PublishEvidence publishEvidence = new PublishEvidence(
                PilotPublishKeyV1.PILOT_STEP_CODE,
                original.publishKeyHash(),
                original.payloadHash(),
                original.rowCount(),
                original.rowCount(),
                0,
                null,
                null);
        return new VerifiedEvidence(
                plan, execution, original, barrierFence, barrierContext, publishEvidence);
    }

    private void verifyTargetIdentity(
            Connection connection, VerifiedEvidence verified) {
        try {
            var actual = identityReader.read(
                    connection,
                    verified.plan().target().owner(),
                    "TABLE",
                    verified.plan().target().objectName());
            if (actual.targetIdentityVersion()
                        != verified.original().targetIdentityVersion()
                    || !equalHash(actual.canonicalTargetHash(),
                            verified.original().canonicalTargetHash())) {
                throw new TargetIdentityMismatchException();
            }
        }
        catch (OracleTargetIdentityException exception) {
            throw new TargetIdentityMismatchException();
        }
    }

    private Result recordedResult(
            RecordedEvidence recorded, PilotPublishIntent original) {
        if (recorded == null || !Objects.equals(recorded.runUuid(), original.runUuid())
                || recorded.attemptNumber() != original.attemptNumber()
                || recorded.fenceToken() != original.targetGeneration()
                || recorded.evidenceAt() == null) {
            return Result.conflict(Failure.LEDGER_EVIDENCE_CONFLICT);
        }
        return Result.published();
    }

    private boolean barrierMatches(
            FenceEvidence fence, TargetLedgerContext expected) {
        return fence.fenceToken() == expected.fenceToken()
                && Objects.equals(fence.jobRequestUuid(), expected.jobRequestUuid())
                && Objects.equals(fence.runUuid(), expected.runUuid())
                && fence.attemptNumber() == expected.attemptNumber()
                && equalHash(fence.releaseHash(), expected.releaseHash())
                && equalHash(fence.planHash(), expected.planHash())
                && fence.updatedAt() != null;
    }

    private boolean rollback(ReconciliationSessionHandle session) {
        try {
            session.rollbackConfirmed();
            return true;
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean close(ReconciliationSessionHandle session) {
        try {
            session.close();
            return true;
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hash(String value) {
        return value != null && HASH.matcher(value).matches();
    }

    private boolean equalHash(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Pinned reconciliation evidence is invalid.");
    }

    private static ReconciliationSessionHandle session(RuntimeOracleSession session) {
        return new ReconciliationSessionHandle() {
            @Override
            public Connection connection() {
                return session.connection();
            }

            @Override
            public RuntimeOracleConnectionProvider.SessionPurpose purpose() {
                return session.purpose();
            }

            @Override
            public void rollbackConfirmed() {
                session.rollbackConfirmed();
            }

            @Override
            public void close() {
                session.close();
            }
        };
    }

    @FunctionalInterface
    interface ReconciliationSessionOpener {
        ReconciliationSessionHandle open(PilotRuntimePlan.DatasetBinding target);
    }

    interface ReconciliationSessionHandle extends AutoCloseable {
        RuntimeOracleConnectionProvider.SessionPurpose purpose();

        Connection connection();

        void rollbackConfirmed();

        @Override
        void close();
    }

    private record VerifiedEvidence(
            PilotRuntimePlan plan,
            PinnedExecutionContext execution,
            PilotPublishIntent original,
            TargetFenceToken barrierFence,
            TargetLedgerContext barrierContext,
            PublishEvidence publishEvidence) {
    }

    private static final class TargetIdentityMismatchException extends RuntimeException {
    }
}

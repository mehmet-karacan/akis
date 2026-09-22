package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.OracleAtomicPublishPort.AlreadyRecorded;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.CommitConfirmed;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.EvidenceConflict;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.FailureCode;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.FencedOut;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.NotAttempted;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OracleAtomicPublishCommand;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OracleAtomicPublishResult;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OutcomeUnknown;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.PublishReceipt;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.SafeFailure;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;

/**
 * Owns the one physical Oracle transaction used for PREPARE, target refresh,
 * verification, ledger RECORD and COMMIT. No caller-supplied connection or
 * ledger evidence can cross this boundary.
 */
@Component
final class OracleAtomicPublishFacade implements OracleAtomicPublishPort {

    private final PilotPublishIntentPort intents;
    private final TargetLedgerPort ledger;
    private final OracleAtomicRefreshWriterPort writer;
    private final TargetSessionOpener sessions;
    private final LockedTargetPreflight lockedTargetPreflight;
    private final PilotPublishKeyV1 publishKeys;

    @Autowired
    OracleAtomicPublishFacade(
            RuntimeOracleConnectionProvider connections,
            PilotPublishIntentPort intents,
            TargetLedgerPort ledger,
            ObjectMapper objectMapper) {
        this(
                intents,
                ledger,
                new JdbcOracleAtomicRefreshWriter(),
                target -> connections.openTargetData(target, PublishPermit.INSTANCE),
                preflight(new JdbcOracleSchemaPreflight(objectMapper)),
                new PilotPublishKeyV1());
    }

    OracleAtomicPublishFacade(
            PilotPublishIntentPort intents,
            TargetLedgerPort ledger,
            OracleAtomicRefreshWriterPort writer,
            RuntimeOracleConnectionProvider connections,
            LockedTargetPreflight lockedTargetPreflight,
            PilotPublishKeyV1 publishKeys) {
        this(
                intents,
                ledger,
                writer,
                target -> connections.openTargetData(target, PublishPermit.INSTANCE),
                lockedTargetPreflight,
                publishKeys);
    }

    OracleAtomicPublishFacade(
            PilotPublishIntentPort intents,
            TargetLedgerPort ledger,
            OracleAtomicRefreshWriterPort writer,
            TargetSessionOpener sessions,
            LockedTargetPreflight lockedTargetPreflight,
            PilotPublishKeyV1 publishKeys) {
        this.intents = Objects.requireNonNull(intents, "Publish intent port is required.");
        this.ledger = Objects.requireNonNull(ledger, "Target ledger is required.");
        this.writer = Objects.requireNonNull(writer, "Target writer is required.");
        this.sessions = Objects.requireNonNull(sessions, "Target sessions are required.");
        this.lockedTargetPreflight = Objects.requireNonNull(
                lockedTargetPreflight, "Locked target preflight is required.");
        this.publishKeys = Objects.requireNonNull(publishKeys, "Publish keys are required.");
    }

    @Override
    public OracleAtomicPublishResult publish(OracleAtomicPublishCommand command) {
        Contract contract = contract(command);
        if (contract.failure() != null) {
            return contract.failure();
        }

        Optional<PilotPublishIntent> loaded;
        try {
            loaded = intents.find(command.execution().runUuid());
        }
        catch (RuntimeException exception) {
            return new NotAttempted(FailureCode.CONTROL_PLANE_UNAVAILABLE);
        }
        if (loaded.isEmpty()) {
            return new EvidenceConflict(FailureCode.INTENT_NOT_FOUND);
        }
        PilotPublishIntent intent = loaded.get();
        OracleAtomicPublishResult intentFailure = verifyIntent(command, intent, contract);
        if (intentFailure != null) {
            return intentFailure;
        }

        PublishEvidence evidence = new PublishEvidence(
                PilotPublishKeyV1.PILOT_STEP_CODE,
                contract.publishKeyHash(),
                command.batch().payloadHash(),
                command.batch().rows().size(),
                command.batch().rows().size(),
                0,
                null,
                null);
        PublishReceipt receipt = new PublishReceipt(
                contract.publishKeyHash(), command.batch().payloadHash(),
                command.batch().rows().size(), command.batch().byteCount());

        RuntimeOracleSession session;
        try {
            session = sessions.open(command.plan().target());
        }
        catch (RuntimeException exception) {
            return new NotAttempted(FailureCode.CONNECTION_UNAVAILABLE);
        }
        if (session == null
                || session.purpose()
                        != RuntimeOracleConnectionProvider.SessionPurpose.TARGET_DATA) {
            if (session != null) {
                closeAndReturn(session, new NotAttempted(FailureCode.INVALID_CONTRACT));
            }
            return new NotAttempted(FailureCode.INVALID_CONTRACT);
        }

        boolean commitAttempted = false;
        boolean prepareAttempted = false;
        boolean absenceConfirmed = false;
        OracleAtomicPublishResult result;
        try {
            Connection connection = session.connection();
            DataLedgerSession dataLedger = ledger.bindData(
                    connection,
                    TargetLedgerContext.from(command.fence(), command.execution()));

            // PREPARE is deliberately the first transaction-opening Oracle call.
            prepareAttempted = true;
            PublishPreparation preparation = dataLedger.preparePublish(evidence);
            if (preparation.alreadyRecorded()) {
                if (!rollback(session)) {
                    return closeAndReturn(
                            session,
                            new OutcomeUnknown(FailureCode.ROLLBACK_NOT_CONFIRMED));
                }
                return closeAndReturn(session, new AlreadyRecorded(receipt));
            }
            absenceConfirmed = true;

            OraclePilotWriteResult writeResult = writer.write(
                    connection,
                    command.plan(),
                    command.fence(),
                    command.batch(),
                    locked -> lockedTargetPreflight.verify(
                            command.plan(), locked, command.snapshots()));
            verifyWrite(command.batch(), writeResult);
            dataLedger.recordPublish(preparation);

            commitAttempted = true;
            session.commitConfirmed();
            result = new CommitConfirmed(receipt);
        }
        catch (RuntimeException exception) {
            if (commitAttempted) {
                rollbackQuietly(session);
                result = new OutcomeUnknown(FailureCode.COMMIT_OUTCOME_UNKNOWN);
            }
            else if (!rollback(session)) {
                result = new OutcomeUnknown(FailureCode.ROLLBACK_NOT_CONFIRMED);
            }
            else if (prepareAttempted && !absenceConfirmed
                    && !isDeterministicPrepareRejection(exception)) {
                result = new OutcomeUnknown(FailureCode.PREPARE_OUTCOME_UNKNOWN);
            }
            else {
                result = classifySafeFailure(exception);
            }
        }
        return closeAndReturn(session, result);
    }

    private Contract contract(OracleAtomicPublishCommand command) {
        if (command == null || command.plan() == null || command.execution() == null
                || command.fence() == null || command.snapshots() == null
                || command.batch() == null) {
            return Contract.failed(new EvidenceConflict(FailureCode.INVALID_CONTRACT));
        }
        try {
            String publishKey = publishKeys.create(
                    command.execution().jobRequestUuid(),
                    command.plan().runtimePlanHash(),
                    command.fence().canonicalTargetHash(),
                    PilotPublishKeyV1.PILOT_STEP_CODE);
            if (!same(command.execution().runUuid(), command.fence().runUuid())
                    || command.fence().runGeneration() <= 0
                    || command.fence().targetGeneration() <= 0
                    || command.fence().targetResourceUuid() == null
                    || command.fence().workerReference() == null
                    || command.fence().workerReference().isBlank()
                    || command.fence().targetIdentityVersion()
                            != OracleTargetIdentityV1.TARGET_IDENTITY_VERSION) {
                return Contract.failed(new FencedOut(FailureCode.STALE_FENCE));
            }
            if (!equalHash(command.plan().releaseHash(), command.execution().releaseHash())
                    || !equalHash(command.plan().scenarioPlanHash(),
                            command.execution().planHash())
                    || !equalHash(command.plan().runtimePlanHash(),
                            command.batch().runtimePlanHash())
                    || !snapshotMatches(command.snapshots(), command)) {
                return Contract.failed(new EvidenceConflict(FailureCode.INVALID_CONTRACT));
            }
            return new Contract(publishKey, null);
        }
        catch (RuntimeException exception) {
            return Contract.failed(new EvidenceConflict(FailureCode.INVALID_CONTRACT));
        }
    }

    private OracleAtomicPublishResult verifyIntent(
            OracleAtomicPublishCommand command,
            PilotPublishIntent intent,
            Contract contract) {
        if (!same(intent.runUuid(), command.fence().runUuid())
                || intent.runGeneration() != command.fence().runGeneration()
                || !Objects.equals(intent.workerReference(),
                        command.fence().workerReference())
                || !same(intent.targetResourceUuid(),
                        command.fence().targetResourceUuid())
                || intent.targetGeneration() != command.fence().targetGeneration()
                || !equalHash(intent.canonicalTargetHash(),
                        command.fence().canonicalTargetHash())
                || intent.targetIdentityVersion()
                        != command.fence().targetIdentityVersion()) {
            return new FencedOut(FailureCode.STALE_FENCE);
        }
        if (!same(intent.projectUuid(), command.snapshots().projectUuid())
                || !same(intent.publicationUuid(), command.execution().publicationUuid())
                || !same(intent.publicationUuid(), command.snapshots().publicationUuid())
                || !same(intent.jobRequestUuid(), command.execution().jobRequestUuid())
                || !same(intent.runUuid(), command.execution().runUuid())
                || intent.attemptNumber() != command.execution().attemptNumber()
                || !equalHash(intent.releaseHash(), command.execution().releaseHash())
                || !equalHash(intent.planHash(), command.execution().planHash())
                || !equalHash(intent.runtimePlanHash(), command.plan().runtimePlanHash())
                || !equalHash(intent.publishKeyHash(), contract.publishKeyHash())
                || !equalHash(intent.payloadHash(), command.batch().payloadHash())
                || intent.rowCount() != command.batch().rows().size()
                || intent.byteCount() != command.batch().byteCount()) {
            return new EvidenceConflict(FailureCode.LEDGER_EVIDENCE_CONFLICT);
        }
        return null;
    }

    private boolean snapshotMatches(
            PinnedSnapshots snapshots, OracleAtomicPublishCommand command) {
        PinnedSnapshot source = snapshots.source();
        PinnedSnapshot target = snapshots.target();
        return same(snapshots.publicationUuid(), command.execution().publicationUuid())
                && source != null && target != null
                && same(source.schemaSnapshotUuid(),
                        command.plan().source().schemaSnapshotUuid())
                && same(target.schemaSnapshotUuid(),
                        command.plan().target().schemaSnapshotUuid())
                && equalHash(source.verifiedFingerprint(),
                        command.plan().source().schemaSnapshotFingerprint())
                && equalHash(target.verifiedFingerprint(),
                        command.plan().target().schemaSnapshotFingerprint());
    }

    private void verifyWrite(OraclePilotBatch batch, OraclePilotWriteResult result) {
        if (result == null
                || result.insertedRows() != batch.rows().size()
                || result.verifiedRows() != batch.rows().size()
                || result.verifiedByteCount() != batch.byteCount()
                || !equalHash(result.verifiedPayloadHash(), batch.payloadHash())) {
            throw new PublishProtocolException();
        }
    }

    private OracleAtomicPublishResult classifySafeFailure(RuntimeException exception) {
        if (exception instanceof OracleTargetLedgerException ledgerFailure) {
            return switch (ledgerFailure.failure()) {
                case PUBLISH_EVIDENCE_CONFLICT ->
                        new EvidenceConflict(FailureCode.LEDGER_EVIDENCE_CONFLICT);
                case FENCE_NOT_FOUND, FENCE_OWNERSHIP_MISMATCH,
                        STALE_FENCE_TOKEN, FENCE_OWNER_CONFLICT ->
                        new FencedOut(FailureCode.STALE_FENCE);
                default -> new SafeFailure(FailureCode.ORACLE_OPERATION_REJECTED);
            };
        }
        if (exception instanceof PublishProtocolException
                || exception instanceof OraclePilotDataException dataFailure
                    && dataFailure.failure()
                            == OraclePilotDataException.Failure.TARGET_VERIFICATION_FAILED) {
            return new SafeFailure(FailureCode.WRITE_VERIFICATION_FAILED);
        }
        return new SafeFailure(FailureCode.ORACLE_OPERATION_REJECTED);
    }

    private boolean isDeterministicPrepareRejection(RuntimeException exception) {
        if (!(exception instanceof OracleTargetLedgerException ledgerFailure)) {
            return false;
        }
        return switch (ledgerFailure.failure()) {
            case PUBLISH_EVIDENCE_CONFLICT, FENCE_NOT_FOUND,
                    FENCE_OWNERSHIP_MISMATCH, STALE_FENCE_TOKEN,
                    FENCE_OWNER_CONFLICT -> true;
            default -> false;
        };
    }

    private boolean rollback(RuntimeOracleSession session) {
        try {
            session.rollbackConfirmed();
            return true;
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    private void rollbackQuietly(RuntimeOracleSession session) {
        try {
            session.rollbackConfirmed();
        }
        catch (RuntimeException ignored) {
            // A failed commit is ambiguous regardless of a later rollback response.
        }
    }

    private OracleAtomicPublishResult closeAndReturn(
            RuntimeOracleSession session, OracleAtomicPublishResult result) {
        try {
            session.close();
        }
        catch (RuntimeException ignored) {
            // Commit/rollback outcome was already classified above.
        }
        return result;
    }

    private boolean same(Object left, Object right) {
        return Objects.equals(left, right);
    }

    private boolean equalHash(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static LockedTargetPreflight preflight(JdbcOracleSchemaPreflight preflight) {
        return (plan, connection, snapshots) -> preflight.verifyLockedTarget(
                plan,
                connection,
                new JdbcOracleSchemaPreflight.ExpectedSnapshot(
                        snapshots.source().schemaSnapshotUuid(),
                        snapshots.source().body()),
                new JdbcOracleSchemaPreflight.ExpectedSnapshot(
                        snapshots.target().schemaSnapshotUuid(),
                        snapshots.target().body()));
    }

    @FunctionalInterface
    interface TargetSessionOpener
            extends Function<PilotRuntimePlan.DatasetBinding, RuntimeOracleSession> {

        RuntimeOracleSession open(PilotRuntimePlan.DatasetBinding target);

        @Override
        default RuntimeOracleSession apply(PilotRuntimePlan.DatasetBinding target) {
            return open(target);
        }
    }

    @FunctionalInterface
    interface LockedTargetPreflight {

        void verify(
                PilotRuntimePlan plan,
                Connection lockedTargetConnection,
                PinnedSnapshots snapshots);
    }

    private record Contract(
            String publishKeyHash, OracleAtomicPublishResult failure) {

        private static Contract failed(OracleAtomicPublishResult failure) {
            return new Contract(null, failure);
        }
    }

    private static final class PublishProtocolException extends RuntimeException {
    }

    static final class PublishPermit
            implements RuntimeOracleConnectionProvider.TargetDataPermit {

        private static final PublishPermit INSTANCE = new PublishPermit();

        private PublishPermit() {
        }
    }
}

package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.OracleTargetFencePort.CommitConfirmed;
import tr.com.innova.akis.execution.OracleTargetFencePort.FailureCode;
import tr.com.innova.akis.execution.OracleTargetFencePort.FenceReceipt;
import tr.com.innova.akis.execution.OracleTargetFencePort.FencedOut;
import tr.com.innova.akis.execution.OracleTargetFencePort.NotAttempted;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceCommand;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceResult;
import tr.com.innova.akis.execution.OracleTargetFencePort.OutcomeUnknown;
import tr.com.innova.akis.execution.OracleTargetFencePort.SafeFailure;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.SessionPurpose;

/** Owns the fresh Oracle transaction that makes a target fence durable. */
@Component
final class OracleTargetFenceFacade implements OracleTargetFencePort {

    private final TargetLedgerPort ledger;
    private final TargetFenceSessionOpener sessions;
    private final TargetIdentityReader identityReader;

    private final TargetTechnologyRegistry targets;

    @Autowired
    OracleTargetFenceFacade(
            RuntimeOracleConnectionProvider connections,
            TargetLedgerPort ledger,
            TargetTechnologyRegistry targets) {
        this(ledger, connections::openTargetFence, new JdbcOracleTargetIdentityReader()::read, targets);
    }

    OracleTargetFenceFacade(
            TargetLedgerPort ledger,
            TargetFenceSessionOpener sessions) {
        this(ledger, sessions, new JdbcOracleTargetIdentityReader()::read);
    }

    OracleTargetFenceFacade(
            TargetLedgerPort ledger,
            TargetFenceSessionOpener sessions,
            TargetIdentityReader identityReader) {
        this(ledger, sessions, identityReader, null);
    }

    OracleTargetFenceFacade(
            TargetLedgerPort ledger,
            TargetFenceSessionOpener sessions,
            TargetIdentityReader identityReader,
            TargetTechnologyRegistry targets) {
        this.targets = targets;
        this.ledger = Objects.requireNonNull(ledger, "Target ledger is required.");
        this.sessions = Objects.requireNonNull(sessions, "Target sessions are required.");
        this.identityReader = Objects.requireNonNull(
                identityReader, "Target identity reader is required.");
    }

    @Override
    public OracleTargetFenceResult acquire(OracleTargetFenceCommand command) {
        NotAttempted invalid = validate(command);
        if (invalid != null) {
            return invalid;
        }

        RuntimeOracleSession session;
        try {
            session = sessions.open(command.plan().target());
        }
        catch (RuntimeException exception) {
            return new NotAttempted(FailureCode.CONNECTION_UNAVAILABLE);
        }
        if (session == null) {
            return new NotAttempted(FailureCode.CONNECTION_UNAVAILABLE);
        }
        if (session.purpose() != SessionPurpose.TARGET_FENCE) {
            return rejectWrongPurpose(session);
        }

        boolean commitAttempted = false;
        OracleTargetFenceResult result;
        try {
            Connection connection = session.connection();
            verifyTargetIdentity(connection, command);
            FenceSession fenceSession = ledgerFor(command).bindFence(
                    connection,
                    TargetLedgerContext.from(command.fence(), command.execution()));
            fenceSession.acquireFence();
            commitAttempted = true;
            session.commitConfirmed();
            result = new CommitConfirmed(new FenceReceipt(
                    command.fence().targetResourceUuid(),
                    command.fence().targetGeneration(),
                    command.fence().canonicalTargetHash()));
        }
        catch (RuntimeException exception) {
            if (commitAttempted) {
                rollbackQuietly(session);
                result = new OutcomeUnknown(FailureCode.COMMIT_OUTCOME_UNKNOWN);
            }
            else if (rollback(session)) {
                result = exception instanceof TargetIdentityMismatchException
                        ? new SafeFailure(FailureCode.TARGET_IDENTITY_MISMATCH)
                        : deterministicFenceRejection(exception)
                        ? new FencedOut(FailureCode.STALE_FENCE)
                        : new SafeFailure(FailureCode.ORACLE_OPERATION_REJECTED);
            }
            else {
                result = new OutcomeUnknown(FailureCode.ROLLBACK_NOT_CONFIRMED);
            }
        }
        return closeAndReturn(session, result);
    }

    /** Without a registry (unit fixtures) only Oracle is fenced; wired, every registered target technology is. */
    private boolean supported(PilotRuntimePlan.DatabaseType technology) {
        if (targets == null) return technology == PilotRuntimePlan.DatabaseType.ORACLE;
        try { targets.of(technology); return true; }
        catch (RuntimeException unsupported) { return false; }
    }

    private NotAttempted validate(OracleTargetFenceCommand command) {
        if (command == null || command.plan() == null
                || command.execution() == null || command.fence() == null
                || command.plan().target() == null
                || command.plan().target().role() != DatasetRole.TARGET
                || !supported(command.plan().target().databaseType())
                || command.plan().target().dataObjectType()
                        != PilotRuntimePlan.DataObjectType.TABLE
                || command.plan().target().connectionVersionUuid() == null
                || !hash(command.plan().runtimePlanHash())
                || !hash(command.plan().releaseHash())
                || !hash(command.plan().scenarioPlanHash())
                || command.execution().jobRequestUuid() == null
                || command.execution().runUuid() == null
                || command.execution().publicationUuid() == null
                || command.execution().attemptNumber() <= 0
                || !hash(command.execution().releaseHash())
                || !hash(command.execution().planHash())
                || command.fence().runUuid() == null
                || !command.fence().runUuid().equals(command.execution().runUuid())
                || command.fence().workerReference() == null
                || command.fence().workerReference().isBlank()
                || command.fence().runGeneration() <= 0
                || command.fence().targetResourceUuid() == null
                || command.fence().targetGeneration() <= 0
                || command.fence().targetIdentityVersion()
                        != OracleTargetIdentityV1.TARGET_IDENTITY_VERSION
                || !hash(command.fence().canonicalTargetHash())
                || !equalHash(command.plan().releaseHash(),
                        command.execution().releaseHash())
                || !equalHash(command.plan().scenarioPlanHash(),
                        command.execution().planHash())) {
            return new NotAttempted(FailureCode.INVALID_CONTRACT);
        }
        return null;
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

    private OracleTargetFenceResult rejectWrongPurpose(RuntimeOracleSession session) {
        OracleTargetFenceResult result;
        SessionPurpose purpose = session.purpose();
        if ((purpose != null && purpose.readOnly()) || rollback(session)) {
            result = new NotAttempted(FailureCode.INVALID_CONTRACT);
        }
        else {
            result = new OutcomeUnknown(FailureCode.ROLLBACK_NOT_CONFIRMED);
        }
        return closeAndReturn(session, result);
    }

    /** Without a registry (unit fixtures) the Oracle ledger and reader are used; wired, the target's own bundle is. */
    private TargetLedgerPort ledgerFor(OracleTargetFenceCommand command) {
        return targets == null ? ledger : targets.of(command.plan().target().databaseType()).ledger();
    }

    private TargetIdentityReader identityReaderFor(OracleTargetFenceCommand command) {
        return targets == null ? identityReader : targets.of(command.plan().target().databaseType()).identity()::read;
    }

    private void verifyTargetIdentity(
            Connection connection, OracleTargetFenceCommand command) {
        try {
            CanonicalTargetIdentity actual = identityReaderFor(command).read(
                    connection,
                    command.plan().target().owner(),
                    command.plan().target().dataObjectType().name(),
                    command.plan().target().objectName());
            if (actual == null
                    || actual.targetIdentityVersion()
                            != command.fence().targetIdentityVersion()
                    || !Objects.equals(actual.owner(), command.plan().target().owner())
                    || !Objects.equals(actual.objectType(),
                            command.plan().target().dataObjectType().name())
                    || !Objects.equals(actual.objectName(),
                            command.plan().target().objectName())
                    || !equalHash(actual.canonicalTargetHash(),
                            command.fence().canonicalTargetHash())) {
                throw new TargetIdentityMismatchException();
            }
        }
        catch (RuntimeException exception) {
            if (exception instanceof TargetIdentityMismatchException) {
                throw exception;
            }
            throw new TargetIdentityMismatchException();
        }
    }

    private boolean deterministicFenceRejection(RuntimeException exception) {
        if (!(exception instanceof OracleTargetLedgerException ledgerFailure)) {
            return false;
        }
        return switch (ledgerFailure.failure()) {
            case FENCE_NOT_FOUND, FENCE_OWNERSHIP_MISMATCH,
                    STALE_FENCE_TOKEN, FENCE_OWNER_CONFLICT -> true;
            default -> false;
        };
    }

    private void rollbackQuietly(RuntimeOracleSession session) {
        try {
            session.rollbackConfirmed();
        }
        catch (RuntimeException ignored) {
            // A failed commit remains ambiguous regardless of rollback response.
        }
    }

    private OracleTargetFenceResult closeAndReturn(
            RuntimeOracleSession session, OracleTargetFenceResult result) {
        try {
            session.close();
        }
        catch (RuntimeException ignored) {
            // The transaction outcome was classified before physical close.
        }
        return result;
    }

    private boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean equalHash(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    @FunctionalInterface
    interface TargetFenceSessionOpener {

        RuntimeOracleSession open(PilotRuntimePlan.DatasetBinding target);
    }

    @FunctionalInterface
    interface TargetIdentityReader {

        CanonicalTargetIdentity read(
                Connection connection, String owner, String objectType, String objectName);
    }

    private static final class TargetIdentityMismatchException extends RuntimeException {
    }
}

package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.Failure;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.IdentityReadSucceeded;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.NotAttempted;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.SafeFailure;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityEvidence;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityReadCommand;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityReadResult;
import tr.com.innova.akis.execution.TargetIdentityPort.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;

/**
 * Owns one fresh Oracle target identity session. The live schema and canonical
 * physical identity are read on that same read-only, auto-commit connection.
 */
@Component
final class OracleTargetIdentityReadFacade implements OracleTargetIdentityReadPort {

    private final TargetIdentitySessionOpener sessions;
    private final TargetIdentityReader identityReader;
    private final TargetSchemaPreflight preflight;
    private final SchemaFingerprint fingerprint;

    @Autowired
    OracleTargetIdentityReadFacade(
            RuntimeOracleConnectionProvider connections,
            ObjectMapper objectMapper) {
        this(
                binding -> session(connections.openTargetIdentityRead(binding)),
                new JdbcOracleTargetIdentityReader()::read,
                (plan, connection, snapshot) ->
                        new JdbcOracleSchemaPreflight(objectMapper).verifyTarget(
                                plan,
                                connection,
                                new ExpectedSnapshot(
                                        snapshot.schemaSnapshotUuid(), snapshot.body())),
                new SchemaFingerprint(objectMapper));
    }

    OracleTargetIdentityReadFacade(
            TargetIdentitySessionOpener sessions,
            TargetIdentityReader identityReader,
            TargetSchemaPreflight preflight,
            SchemaFingerprint fingerprint) {
        this.sessions = Objects.requireNonNull(sessions, "Target sessions are required.");
        this.identityReader = Objects.requireNonNull(
                identityReader, "Target identity reader is required.");
        this.preflight = Objects.requireNonNull(
                preflight, "Target schema preflight is required.");
        this.fingerprint = Objects.requireNonNull(
                fingerprint, "Schema fingerprint is required.");
    }

    @Override
    public TargetIdentityReadResult read(TargetIdentityReadCommand command) {
        PilotRuntimePlan plan;
        try {
            plan = verifyPinned(command);
        }
        catch (RuntimeException exception) {
            return new NotAttempted(Failure.INVALID_CONTRACT);
        }

        TargetIdentitySessionHandle session;
        try {
            session = sessions.open(plan.target());
        }
        catch (RuntimeException exception) {
            return new NotAttempted(Failure.CONNECTION_UNAVAILABLE);
        }
        if (session == null) {
            return new NotAttempted(Failure.CONNECTION_UNAVAILABLE);
        }

        TargetIdentityReadResult observed;
        try {
            if (session.purpose()
                    != RuntimeOracleConnectionProvider.SessionPurpose.TARGET_IDENTITY_READ) {
                observed = new NotAttempted(Failure.INVALID_SESSION_PURPOSE);
            }
            else {
                Connection connection = session.connection();
                if (!readOnlyState(connection)) {
                    observed = new NotAttempted(Failure.INVALID_SESSION_STATE);
                }
                else {
                    preflight.verify(plan, connection, command.targetSnapshot());
                    CanonicalTargetIdentity identity = identityReader.read(
                            connection,
                            plan.target().owner(),
                            plan.target().dataObjectType().name(),
                            plan.target().objectName());
                    observed = valid(identity, plan)
                            ? new IdentityReadSucceeded(evidence(identity))
                            : new SafeFailure(Failure.TARGET_IDENTITY_REJECTED);
                }
            }
        }
        catch (OracleSchemaPreflightException exception) {
            observed = new SafeFailure(Failure.TARGET_SCHEMA_REJECTED);
        }
        catch (OracleTargetIdentityException exception) {
            observed = new SafeFailure(Failure.TARGET_IDENTITY_REJECTED);
        }
        catch (RuntimeException exception) {
            observed = new SafeFailure(Failure.TARGET_READ_FAILED);
        }

        if (!close(session)) {
            return new SafeFailure(Failure.SESSION_CLOSE_UNCONFIRMED);
        }
        return observed;
    }

    private PilotRuntimePlan verifyPinned(TargetIdentityReadCommand command) {
        if (command == null || command.plan() == null || command.execution() == null
                || command.targetSnapshot() == null) {
            throw invalid();
        }
        PilotRuntimePlan plan = command.plan();
        var execution = command.execution();
        PinnedSnapshot snapshot = command.targetSnapshot();
        if (plan.source() == null || plan.target() == null
                || plan.source().role() != PilotRuntimePlan.DatasetRole.SOURCE
                || plan.target().role() != PilotRuntimePlan.DatasetRole.TARGET
                || execution.jobRequestUuid() == null || execution.runUuid() == null
                || execution.publicationUuid() == null || execution.attemptNumber() < 1
                || execution.scenarioPlan() == null || execution.physicalManifest() == null
                || !equalHash(execution.releaseHash(), plan.releaseHash())
                || !equalHash(execution.planHash(), plan.scenarioPlanHash())
                || snapshot.body() == null
                || !Objects.equals(
                        snapshot.schemaSnapshotUuid(), plan.target().schemaSnapshotUuid())
                || !equalHash(
                        snapshot.verifiedFingerprint(),
                        plan.target().schemaSnapshotFingerprint())) {
            throw invalid();
        }
        String calculated;
        try {
            calculated = fingerprint.calculate(snapshot.body());
        }
        catch (RuntimeException exception) {
            throw invalid();
        }
        if (!equalHash(calculated, snapshot.verifiedFingerprint())) {
            throw invalid();
        }
        return plan;
    }

    private boolean readOnlyState(Connection connection) {
        try {
            return connection != null && !connection.isClosed()
                    && connection.isReadOnly() && connection.getAutoCommit();
        }
        catch (SQLException | RuntimeException exception) {
            return false;
        }
    }

    private boolean valid(CanonicalTargetIdentity identity, PilotRuntimePlan plan) {
        return identity != null
                && identity.targetIdentityVersion()
                        == OracleTargetIdentityV1.TARGET_IDENTITY_VERSION
                && Objects.equals(identity.owner(), plan.target().owner())
                && Objects.equals(identity.objectType(), plan.target().dataObjectType().name())
                && Objects.equals(identity.objectName(), plan.target().objectName())
                && identity.site() != null
                && !identity.site().isBlank()
                && identity.container() != null
                && !identity.container().isBlank()
                && identity.canonicalTargetHash() != null
                && identity.canonicalTargetHash().matches("[0-9a-f]{64}");
    }

    private TargetIdentityEvidence evidence(CanonicalTargetIdentity identity) {
        return new TargetIdentityEvidence(
                identity.targetIdentityVersion(),
                identity.site(),
                identity.container(),
                identity.owner(),
                identity.objectType(),
                identity.objectName(),
                identity.canonicalTargetHash());
    }

    private boolean close(TargetIdentitySessionHandle session) {
        try {
            session.close();
            return true;
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean equalHash(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Pinned target identity contract is invalid.");
    }

    private static TargetIdentitySessionHandle session(RuntimeOracleSession session) {
        return new TargetIdentitySessionHandle() {
            @Override
            public RuntimeOracleConnectionProvider.SessionPurpose purpose() {
                return session.purpose();
            }

            @Override
            public Connection connection() {
                return session.connection();
            }

            @Override
            public void close() {
                session.close();
            }
        };
    }

    @FunctionalInterface
    interface TargetIdentitySessionOpener {
        TargetIdentitySessionHandle open(PilotRuntimePlan.DatasetBinding target);
    }

    @FunctionalInterface
    interface TargetIdentityReader {
        CanonicalTargetIdentity read(
                Connection connection, String owner, String objectType, String objectName);
    }

    @FunctionalInterface
    interface TargetSchemaPreflight {
        void verify(PilotRuntimePlan plan, Connection connection, PinnedSnapshot snapshot);
    }

    interface TargetIdentitySessionHandle extends AutoCloseable {
        RuntimeOracleConnectionProvider.SessionPurpose purpose();

        Connection connection();

        @Override
        void close();
    }
}

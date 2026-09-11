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
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.Failure;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.NotAttempted;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.OutcomeUnknown;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.ReadSucceeded;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.SafeFailure;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.SourceReadCommand;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.SourceReadResult;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;

/**
 * Owns the purpose-specific Oracle source session around one bounded read.
 * Callers provide pinned control-plane evidence, never a JDBC connection.
 */
@Component
final class OraclePilotSourceReadFacade implements OraclePilotSourceReadPort {

    private final SourceSessionOpener sessions;
    private final SourceReader reader;
    private final SourceSchemaPreflight preflight;
    private final SchemaFingerprint fingerprint;

    @Autowired
    OraclePilotSourceReadFacade(
            RuntimeOracleConnectionProvider connections,
            JdbcOraclePilotSourceReader reader,
            ObjectMapper objectMapper) {
        this(
                binding -> session(connections.openSource(binding)),
                reader::read,
                (plan, connection, snapshot) -> new JdbcOracleSchemaPreflight(objectMapper)
                        .verifySource(plan, connection,
                                new ExpectedSnapshot(
                                        snapshot.schemaSnapshotUuid(), snapshot.body())),
                new SchemaFingerprint(objectMapper));
    }

    OraclePilotSourceReadFacade(
            SourceSessionOpener sessions,
            SourceReader reader,
            SourceSchemaPreflight preflight,
            SchemaFingerprint fingerprint) {
        this.sessions = Objects.requireNonNull(sessions, "Source sessions are required.");
        this.reader = Objects.requireNonNull(reader, "Source reader is required.");
        this.preflight = Objects.requireNonNull(
                preflight, "Source schema preflight is required.");
        this.fingerprint = Objects.requireNonNull(
                fingerprint, "Schema fingerprint is required.");
    }

    @Override
    public SourceReadResult read(SourceReadCommand command) {
        PilotRuntimePlan plan;
        try {
            plan = verifyPinned(command);
        }
        catch (RuntimeException exception) {
            return new NotAttempted(Failure.INVALID_CONTRACT);
        }

        SourceSessionHandle session;
        try {
            session = sessions.open(plan.source());
        }
        catch (RuntimeException exception) {
            return new NotAttempted(Failure.CONNECTION_UNAVAILABLE);
        }
        if (session == null) {
            return new NotAttempted(Failure.CONNECTION_UNAVAILABLE);
        }

        SourceReadResult observed;
        try {
            if (session.purpose()
                    != RuntimeOracleConnectionProvider.SessionPurpose.SOURCE_READ) {
                observed = new NotAttempted(Failure.INVALID_SESSION_PURPOSE);
            }
            else {
                Connection connection = session.connection();
                if (!sourceState(connection)) {
                    observed = new NotAttempted(Failure.INVALID_SESSION_STATE);
                }
                else {
                    preflight.verify(plan, connection, command.snapshots().source());
                    OraclePilotBatch batch = reader.read(connection, plan);
                    observed = validBatch(batch, plan)
                            ? new ReadSucceeded(batch)
                            : new SafeFailure(Failure.SOURCE_READ_REJECTED);
                }
            }
        }
        catch (OracleSchemaPreflightException exception) {
            observed = new SafeFailure(Failure.SOURCE_SCHEMA_DRIFT);
        }
        catch (OraclePilotDataException exception) {
            observed = new SafeFailure(Failure.SOURCE_READ_REJECTED);
        }
        catch (RuntimeException exception) {
            observed = new SafeFailure(Failure.SOURCE_READ_FAILED);
        }

        if (!close(session)) {
            return new OutcomeUnknown(Failure.SESSION_CLOSE_UNCONFIRMED);
        }
        return observed;
    }

    private PilotRuntimePlan verifyPinned(SourceReadCommand command) {
        if (command == null || command.plan() == null || command.execution() == null
                || command.snapshots() == null || command.snapshots().source() == null
                || command.snapshots().target() == null) {
            throw invalid();
        }
        PilotRuntimePlan plan = command.plan();
        var execution = command.execution();
        var snapshots = command.snapshots();
        if (plan.source() == null || plan.target() == null
                || plan.source().role() != PilotRuntimePlan.DatasetRole.SOURCE
                || plan.target().role() != PilotRuntimePlan.DatasetRole.TARGET
                || execution.jobRequestUuid() == null || execution.runUuid() == null
                || execution.publicationUuid() == null || execution.attemptNumber() < 1
                || !equalHash(execution.releaseHash(), plan.releaseHash())
                || !equalHash(execution.planHash(), plan.scenarioPlanHash())
                || !Objects.equals(snapshots.publicationUuid(), execution.publicationUuid())
                || snapshots.projectUuid() == null
                || !snapshotMatches(snapshots.source(), plan.source())
                || !snapshotMatches(snapshots.target(), plan.target())) {
            throw invalid();
        }
        return plan;
    }

    private boolean snapshotMatches(
            PinnedSnapshot snapshot, PilotRuntimePlan.DatasetBinding binding) {
        if (snapshot.body() == null
                || !Objects.equals(snapshot.schemaSnapshotUuid(), binding.schemaSnapshotUuid())
                || !equalHash(snapshot.verifiedFingerprint(),
                        binding.schemaSnapshotFingerprint())) {
            return false;
        }
        String calculated;
        try {
            calculated = fingerprint.calculate(snapshot.body());
        }
        catch (RuntimeException exception) {
            return false;
        }
        return equalHash(calculated, snapshot.verifiedFingerprint());
    }

    private boolean sourceState(Connection connection) {
        try {
            return connection != null && !connection.isClosed()
                    && connection.isReadOnly() && connection.getAutoCommit();
        }
        catch (SQLException | RuntimeException exception) {
            return false;
        }
    }

    private boolean validBatch(OraclePilotBatch batch, PilotRuntimePlan plan) {
        return batch != null
                && equalHash(batch.runtimePlanHash(), plan.runtimePlanHash())
                && batch.rows() != null
                && batch.rows().size() <= plan.maximumSourceRows()
                && batch.payloadHash() != null
                && batch.payloadHash().matches("[0-9a-f]{64}")
                && batch.byteCount() >= 0;
    }

    private boolean close(SourceSessionHandle session) {
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
        return new IllegalArgumentException("Pinned source read contract is invalid.");
    }

    private static SourceSessionHandle session(RuntimeOracleSession session) {
        return new SourceSessionHandle() {
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
    interface SourceSessionOpener {
        SourceSessionHandle open(PilotRuntimePlan.DatasetBinding source);
    }

    @FunctionalInterface
    interface SourceReader {
        OraclePilotBatch read(Connection connection, PilotRuntimePlan plan);
    }

    @FunctionalInterface
    interface SourceSchemaPreflight {
        void verify(
                PilotRuntimePlan plan,
                Connection connection,
                PinnedSnapshot snapshot);
    }

    interface SourceSessionHandle extends AutoCloseable {
        RuntimeOracleConnectionProvider.SessionPurpose purpose();

        Connection connection();

        @Override
        void close();
    }
}

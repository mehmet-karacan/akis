package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

/**
 * Performs DELETE plus batched INSERT on one caller-owned transaction.
 * This adapter never commits or rolls back the supplied connection.
 */
final class JdbcOracleAtomicRefreshWriter implements OracleAtomicRefreshWriterPort {

    private static final int BATCH_SIZE = 250;

    private final JdbcOracleTargetIdentityReader identityReader;
    private final OraclePilotPayloadCodec payloadCodec;

    JdbcOracleAtomicRefreshWriter() {
        this(new JdbcOracleTargetIdentityReader(), new OraclePilotPayloadCodec());
    }

    JdbcOracleAtomicRefreshWriter(
            JdbcOracleTargetIdentityReader identityReader,
            OraclePilotPayloadCodec payloadCodec) {
        this.identityReader = Objects.requireNonNull(
                identityReader, "Target identity reader is required.");
        this.payloadCodec = Objects.requireNonNull(
                payloadCodec, "Payload codec is required.");
    }

    @Override
    public OraclePilotWriteResult write(
            Connection connection,
            PilotRuntimePlan plan,
            TargetFenceToken fenceToken,
            OraclePilotBatch batch,
            LockedTargetVerifier lockedTargetVerifier) {
        OraclePilotSqlContract.ValidatedPlan safePlan = OraclePilotSqlContract.validate(plan);
        validateBatch(safePlan, batch);
        Objects.requireNonNull(
                lockedTargetVerifier, "Locked target verifier is required.");
        ensureWritableTransaction(connection);

        String target = OraclePilotSqlContract.qualified(
                safePlan.targetOwner(), safePlan.targetObject());
        lockTarget(connection, target);
        verifyTargetIdentity(connection, safePlan, fenceToken);
        lockedTargetVerifier.verify(connection);
        String deleteSql = "DELETE FROM " + target;
        String insertSql = "INSERT INTO " + target + " ("
                + OraclePilotSqlContract.quotedColumns(safePlan.targetColumns())
                + ") VALUES (" + placeholders(safePlan.targetColumns().size()) + ")";

        try (PreparedStatement delete = connection.prepareStatement(deleteSql);
                PreparedStatement insert = connection.prepareStatement(insertSql)) {
            int deletedRows = delete.executeUpdate();
            int pending = 0;
            for (List<OraclePilotCell> row : batch.rows()) {
                for (int index = 0; index < row.size(); index++) {
                    bind(insert, index + 1, row.get(index));
                }
                insert.addBatch();
                pending++;
                if (pending == BATCH_SIZE) {
                    verifyBatch(insert.executeBatch(), pending);
                    insert.clearBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                verifyBatch(insert.executeBatch(), pending);
                insert.clearBatch();
            }
            OraclePilotBatch verified = verifyTarget(
                    connection, safePlan, target, batch);
            return new OraclePilotWriteResult(
                    deletedRows, batch.rows().size(), verified.rows().size(),
                    verified.byteCount(), verified.payloadHash());
        }
        catch (SQLException exception) {
            throw new OraclePilotDataException(
                    OraclePilotDataException.Failure.TARGET_WRITE_FAILED,
                    "The Oracle pilot target write failed.",
                    exception.getSQLState(), exception.getErrorCode());
        }
    }

    private OraclePilotBatch verifyTarget(
            Connection connection,
            OraclePilotSqlContract.ValidatedPlan plan,
            String target,
            OraclePilotBatch expected) {
        int queryLimit = expected.rows().size() + 1;
        String sql = "SELECT " + OraclePilotSqlContract.quotedColumns(
                plan.targetColumns()) + " FROM " + target
                + " FETCH FIRST " + queryLimit + " ROWS ONLY";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(queryLimit);
            statement.setFetchSize(Math.min(queryLimit, BATCH_SIZE));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<OraclePilotColumn> columns = payloadCodec.columns(
                        resultSet.getMetaData(), plan.sourceColumns());
                OraclePilotPayloadCodec.PayloadBudget budget = payloadCodec.budget(
                        plan.runtimePlanHash(), columns);
                List<List<OraclePilotCell>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    if (rows.size() == expected.rows().size()) {
                        throw targetVerificationFailed();
                    }
                    List<OraclePilotCell> row = new ArrayList<>(columns.size());
                    for (int index = 1; index <= columns.size(); index++) {
                        row.add(payloadCodec.read(
                                resultSet, index, columns.get(index - 1).type()));
                    }
                    budget.accept(row);
                    rows.add(row);
                }
                OraclePilotBatch actual = payloadCodec.batch(
                        plan.runtimePlanHash(), columns, rows);
                if (actual.rows().size() != expected.rows().size()
                        || actual.byteCount() != expected.byteCount()
                        || !constantTimeEquals(actual.payloadHash(), expected.payloadHash())) {
                    throw targetVerificationFailed();
                }
                return actual;
            }
        }
        catch (OraclePilotDataException exception) {
            if (exception.failure()
                    == OraclePilotDataException.Failure.TARGET_VERIFICATION_FAILED) {
                throw exception;
            }
            throw targetVerificationFailed();
        }
        catch (SQLException exception) {
            throw new OraclePilotDataException(
                    OraclePilotDataException.Failure.TARGET_VERIFICATION_FAILED,
                    "The Oracle pilot target content could not be verified.",
                    exception.getSQLState(), exception.getErrorCode());
        }
    }

    private void validateBatch(
            OraclePilotSqlContract.ValidatedPlan plan, OraclePilotBatch batch) {
        if (batch == null || !plan.runtimePlanHash().equals(batch.runtimePlanHash())
                || !plan.sourceColumns().equals(batch.columns().stream()
                        .map(OraclePilotColumn::sourceColumn).toList())
                || batch.rows().size() > plan.maximumSourceRows()
                || batch.rows().stream().anyMatch(
                        row -> row == null || row.size() != plan.targetColumns().size())) {
            throw new OraclePilotDataException(
                    OraclePilotDataException.Failure.INVALID_BATCH,
                    "The Oracle pilot batch does not match the runtime plan.");
        }
        payloadCodec.verify(batch);
    }

    private void verifyTargetIdentity(
            Connection connection,
            OraclePilotSqlContract.ValidatedPlan plan,
            TargetFenceToken token) {
        if (token == null
                || token.targetIdentityVersion()
                        != OracleTargetIdentityV1.TARGET_IDENTITY_VERSION) {
            throw identityMismatch();
        }
        try {
            var actual = identityReader.read(
                    connection, plan.targetOwner(), "TABLE", plan.targetObject());
            if (actual.targetIdentityVersion() != token.targetIdentityVersion()
                    || !constantTimeEquals(
                            actual.canonicalTargetHash(), token.canonicalTargetHash())) {
                throw identityMismatch();
            }
        }
        catch (OracleTargetIdentityException exception) {
            throw identityMismatch();
        }
    }

    private static void lockTarget(Connection connection, String target) {
        try (PreparedStatement statement = connection.prepareStatement(
                "LOCK TABLE " + target + " IN EXCLUSIVE MODE NOWAIT")) {
            statement.execute();
        }
        catch (SQLException exception) {
            throw new OraclePilotDataException(
                    OraclePilotDataException.Failure.TARGET_LOCK_FAILED,
                    "The Oracle pilot target lock could not be acquired.",
                    exception.getSQLState(), exception.getErrorCode());
        }
    }

    private static void bind(
            PreparedStatement statement, int index, OraclePilotCell cell)
            throws SQLException {
        if (cell.isNull()) {
            statement.setNull(index, switch (cell.type()) {
                case NUMBER -> Types.NUMERIC;
                case VARCHAR2 -> Types.VARCHAR;
                case TIMESTAMP -> Types.TIMESTAMP;
            });
            return;
        }
        switch (cell.type()) {
            case NUMBER -> statement.setBigDecimal(
                    index, new BigDecimal(cell.canonicalValue()));
            case VARCHAR2 -> statement.setString(index, cell.canonicalValue());
            case TIMESTAMP -> statement.setTimestamp(index, Timestamp.valueOf(
                    LocalDateTime.parse(cell.canonicalValue(),
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        }
    }

    private static void ensureWritableTransaction(Connection connection) {
        try {
            if (connection == null || connection.isClosed()
                    || connection.getAutoCommit() || connection.isReadOnly()) {
                throw new OraclePilotDataException(
                        OraclePilotDataException.Failure.INVALID_CONNECTION,
                        "The Oracle pilot target requires an open, writable, auto-commit-disabled connection.");
            }
        }
        catch (SQLException exception) {
            throw new OraclePilotDataException(
                    OraclePilotDataException.Failure.INVALID_CONNECTION,
                    "The Oracle pilot target connection could not be verified.",
                    exception.getSQLState(), exception.getErrorCode());
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static OraclePilotDataException identityMismatch() {
        return new OraclePilotDataException(
                OraclePilotDataException.Failure.TARGET_IDENTITY_MISMATCH,
                "The Oracle pilot target identity does not match the acquired fence.");
    }

    private static OraclePilotDataException targetVerificationFailed() {
        return new OraclePilotDataException(
                OraclePilotDataException.Failure.TARGET_VERIFICATION_FAILED,
                "The Oracle pilot target content could not be verified.");
    }

    private static void verifyBatch(int[] counts, int expected) throws SQLException {
        if (counts == null || counts.length != expected) {
            throw new SQLException("Unexpected Oracle batch result.");
        }
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED || count == 0) {
                throw new SQLException("Oracle batch insert was not applied.");
            }
        }
    }
}

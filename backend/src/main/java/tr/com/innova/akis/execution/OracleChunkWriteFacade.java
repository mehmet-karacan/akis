package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import tr.com.innova.akis.execution.TargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;
import tr.com.innova.akis.knowledge.StagedMappingDefinition;

/**
 * Writes exactly one bounded chunk to an owned stage. Business rows and the Oracle
 * receipt share one local transaction; this adapter never retries an uncertain commit.
 */
final class OracleChunkWriteFacade {

    enum Outcome { COMMIT_CONFIRMED, ALREADY_RECORDED, ROLLBACK_CONFIRMED, OUTCOME_UNKNOWN }
    record Result(Outcome outcome, long rowCount, long byteCount) { }

    private final TargetLedgerPort ledger;

    OracleChunkWriteFacade(TargetLedgerPort ledger) {
        this.ledger = Objects.requireNonNull(ledger);
    }

    Result write(Connection connection, TargetLedgerContext context,
            BatchEvidence evidence, TransferExecutionPlan plan,
            StreamingRowReader.Batch batch, Runnable ownershipCheck,
            Runnable leaseCheckpoint, JdbcTransactionBoundary transaction) {
        Objects.requireNonNull(connection); Objects.requireNonNull(context);
        Objects.requireNonNull(evidence); Objects.requireNonNull(plan);
        Objects.requireNonNull(batch); Objects.requireNonNull(ownershipCheck);
        Objects.requireNonNull(leaseCheckpoint); Objects.requireNonNull(transaction);
        validate(evidence, plan, batch);
        boolean prepared = false;
        boolean committing = false;
        try {
            if (connection.getAutoCommit() || connection.isReadOnly()) {
                throw new IllegalArgumentException("Writable local Oracle transaction is required.");
            }
            leaseCheckpoint.run(); ownershipCheck.run();
            var session = ledger.bindData(connection, context);
            BatchPreparation preparation = session.prepareBatch(evidence);
            if (preparation.alreadyRecorded()) {
                transaction.rollback();
                return new Result(Outcome.ALREADY_RECORDED,
                        evidence.rowCount(), evidence.byteCount());
            }
            prepared = true;
            insert(connection, plan, batch);
            leaseCheckpoint.run(); ownershipCheck.run();
            session.recordBatch(preparation);
            leaseCheckpoint.run();
            committing = true;
            transaction.commit();
            return new Result(Outcome.COMMIT_CONFIRMED,
                    evidence.rowCount(), evidence.byteCount());
        }
        catch (SQLException | RuntimeException failure) {
            boolean rolledBack;
            try { transaction.rollback(); rolledBack = true; }
            catch (SQLException | RuntimeException rollbackFailure) { rolledBack = false; }
            return new Result(committing || !rolledBack || !prepared
                    ? Outcome.OUTCOME_UNKNOWN : Outcome.ROLLBACK_CONFIRMED, 0, 0);
        }
    }

    private void insert(Connection connection, TransferExecutionPlan plan,
            StreamingRowReader.Batch batch) throws SQLException {
        if (batch.rows().isEmpty()) return;
        List<TransferExecutionPlan.ColumnMapping> columns = plan.columns();
        String names = String.join(",", columns.stream()
                .map(column -> quote(column.target())).toList());
        String parameters = String.join(",", Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + quote(plan.targetOwner()) + "."
                + quote(plan.targetTable()) + " (" + names + ") VALUES (" + parameters + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (StreamingRowReader.Row row : batch.rows()) {
                if (row.cells().size() != columns.size()) {
                    throw new SQLException("Chunk row shape differs from the pinned target mapping.");
                }
                for (int index = 0; index < columns.size(); index++) {
                    bind(statement, index + 1, columns.get(index).oracleType(),
                            row.cells().get(index).canonicalValue());
                }
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            if (counts.length != batch.rows().size()
                    || Arrays.stream(counts).anyMatch(count -> count != 1)) {
                throw new SQLException("Oracle did not return exact one-row results for the chunk.");
            }
        }
    }

    private void bind(PreparedStatement statement, int index, String type, String value)
            throws SQLException {
        String normalized = type.toUpperCase(Locale.ROOT);
        if (value == null) {
            statement.setNull(index, switch (normalized) {
                case "NUMBER" -> Types.NUMERIC;
                case "VARCHAR2", "CHAR" -> Types.VARCHAR;
                case "NVARCHAR2", "NCHAR" -> Types.NVARCHAR;
                case "DATE", "TIMESTAMP" -> Types.TIMESTAMP;
                default -> throw new SQLException("Unsupported target type: " + normalized);
            });
            return;
        }
        switch (normalized) {
            case "NUMBER" -> statement.setBigDecimal(index, new BigDecimal(value));
            case "VARCHAR2", "CHAR" -> statement.setString(index, value);
            case "NVARCHAR2", "NCHAR" -> statement.setNString(index, value);
            case "DATE", "TIMESTAMP" -> statement.setTimestamp(index,
                    Timestamp.from(OffsetDateTime.parse(value).toInstant()));
            default -> throw new SQLException("Unsupported target type: " + normalized);
        }
    }

    private void validate(BatchEvidence evidence, TransferExecutionPlan plan,
            StreamingRowReader.Batch batch) {
        if (!Objects.equals(evidence.payloadHash(), batch.payloadHash())
                || evidence.rowCount() != batch.rows().size()
                || evidence.byteCount() != batch.canonicalBytes()
                || plan.columns().isEmpty()
                || batch.rows().stream().anyMatch(row -> row.cells().size() != plan.columns().size())) {
            throw new IllegalArgumentException("Chunk receipt does not match the bounded payload.");
        }
    }

    private String quote(String identifier) {
        return "\"" + StagedMappingDefinition.identifier(identifier) + "\"";
    }
}

package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.Column;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.Outcome;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.Result;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.WriteMode;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;
import tr.com.innova.akis.knowledge.StagedMappingDefinition;

/**
 * PostgreSQL publish of a sealed work table. Unlike Oracle, TRUNCATE is transactional here, so the whole publication —
 * ledger preparation, the target write and the ledger record — is one transaction that either commits together or leaves
 * the previous target rows untouched. Faz A implements TRUNCATE_LOAD; Faz B adds MERGE as
 * {@code INSERT ... ON CONFLICT (KEY_COLUMNS) DO UPDATE}, mirroring {@link JdbcStagedAtomicRefreshWriter}'s Oracle MERGE.
 */
final class JdbcPostgresAtomicRefreshWriter {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcPostgresAtomicRefreshWriter.class);
    private final TargetLedgerPort ledger;

    JdbcPostgresAtomicRefreshWriter(TargetLedgerPort ledger) { this.ledger = Objects.requireNonNull(ledger); }

    Result publish(Connection connection, TargetLedgerContext context, PublishEvidence evidence, Table stage, Table target,
            List<Column> columns, int timeoutSeconds, Runnable lockedPreflight, Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction, WriteMode mode) {
        return publish(connection, context, evidence, stage, target, columns, timeoutSeconds, lockedPreflight, leaseCheckpoint,
                transaction, mode, List.of());
    }

    Result publish(Connection connection, TargetLedgerContext context, PublishEvidence evidence, Table stage, Table target,
            List<Column> columns, int timeoutSeconds, Runnable lockedPreflight, Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction, WriteMode mode, List<String> keyColumns) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(mode);
        Objects.requireNonNull(lockedPreflight); Objects.requireNonNull(leaseCheckpoint);
        columns = List.copyOf(columns); keyColumns = List.copyOf(keyColumns);
        boolean invalidKey = false;
        for (String key : keyColumns) { boolean found = false; for (Column column : columns) if (column.target().equals(key)) { found = true; break; } if (!found) { invalidKey = true; break; } }
        if (columns.isEmpty() || columns.size() > 256 || timeoutSeconds < 1 || timeoutSeconds > 3600 || evidence.stageRowCount() < 0
                || columns.stream().map(Column::target).distinct().count() != columns.size()
                || invalidKey
                || mode != WriteMode.APPEND && mode != WriteMode.TRUNCATE_LOAD && mode != WriteMode.MERGE
                || mode == WriteMode.MERGE && keyColumns.isEmpty()
                || evidence.stageRowCount() != evidence.publishedRowCount() || evidence.rejectedRowCount() != 0 || stage.equals(target))
            throw new IllegalArgumentException("Atomik stage yayın sözleşmesi geçersiz.");
        boolean committing = false, prepared = false, targetChanged = false;
        try {
            if (connection.getAutoCommit() || connection.isReadOnly()) throw new IllegalArgumentException("Yazılabilir tek transaction gerekir.");
            leaseCheckpoint.run();
            DataLedgerSession session = ledger.bindData(connection, context);
            PublishPreparation preparation = session.preparePublish(evidence);
            if (preparation.alreadyRecorded()) {
                transaction.rollback();
                return new Result(Outcome.ALREADY_RECORDED, null, evidence.publishedRowCount());
            }
            prepared = true;
            // ACCESS EXCLUSIVE on the target (what TRUNCATE takes anyway) and a read lock on the sealed work table.
            command(connection, "LOCK TABLE " + target.sql() + " IN ACCESS EXCLUSIVE MODE NOWAIT", timeoutSeconds);
            command(connection, "LOCK TABLE " + stage.sql() + " IN SHARE MODE NOWAIT", timeoutSeconds);
            lockedPreflight.run();
            leaseCheckpoint.run();
            if (count(connection, stage, timeoutSeconds) != evidence.stageRowCount()) throw new IllegalStateException("Mühürlü stage satır sayısı değişmiş.");
            long before = mode == WriteMode.APPEND ? count(connection, target, timeoutSeconds) : 0L;
            targetChanged = true;
            if (mode == WriteMode.TRUNCATE_LOAD) command(connection, "TRUNCATE TABLE " + target.sql() + " RESTART IDENTITY CASCADE", timeoutSeconds);
            String targetColumns = String.join(",", columns.stream().map(c -> quote(c.target())).toList());
            String stageColumns = String.join(",", columns.stream().map(c -> quote(c.stage())).toList());
            String sql = mode == WriteMode.MERGE
                    ? upsertSql(stage, target, columns, keyColumns)
                    : "INSERT INTO " + target.sql() + " (" + targetColumns + ") SELECT " + stageColumns + " FROM " + stage.sql();
            long inserted = update(connection, sql, timeoutSeconds);
            long expectedTarget = mode == WriteMode.APPEND ? before + inserted : inserted;
            if (inserted != evidence.stageRowCount() || mode != WriteMode.MERGE && count(connection, target, timeoutSeconds) != expectedTarget)
                throw new IllegalStateException("Hedef satır doğrulaması başarısız.");
            leaseCheckpoint.run();
            session.recordPublish(preparation);
            leaseCheckpoint.run();
            committing = true;
            transaction.commit();
            return new Result(Outcome.COMMITTED, 0L, inserted);
        }
        catch (SQLException | RuntimeException failure) {
            boolean rolledBack;
            try { transaction.rollback(); rolledBack = true; } catch (SQLException | RuntimeException rollback) { rolledBack = false; }
            LOG.warn("PostgreSQL staged publish into {} failed (prepared={}, targetChanged={}, committing={}, rolledBack={}): {}",
                    target.sql(), prepared, targetChanged, committing, rolledBack, failure.toString());
            // A confirmed rollback restores the previous target rows and drops the preparation with them.
            return new Result(committing || !rolledBack ? Outcome.UNKNOWN : Outcome.ROLLED_BACK, null, null);
        }
    }

    private static String upsertSql(Table stage, Table target, List<Column> columns, List<String> keys) {
        String targetColumns = String.join(",", columns.stream().map(c -> quote(c.target())).toList());
        String stageColumns = String.join(",", columns.stream().map(c -> quote(c.stage())).toList());
        String conflictColumns = String.join(",", keys.stream().map(JdbcPostgresAtomicRefreshWriter::quote).toList());
        List<Column> mutable = columns.stream().filter(column -> !keys.contains(column.target())).toList();
        String onConflict = mutable.isEmpty() ? "DO NOTHING"
                : "DO UPDATE SET " + String.join(",", mutable.stream().map(column -> quote(column.target()) + "=EXCLUDED." + quote(column.target())).toList());
        return "INSERT INTO " + target.sql() + " (" + targetColumns + ") SELECT " + stageColumns + " FROM " + stage.sql()
                + " ON CONFLICT (" + conflictColumns + ") " + onConflict;
    }

    private static void command(Connection connection, String sql, int timeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.setQueryTimeout(timeout); statement.execute(); }
    }

    private static long update(Connection connection, String sql, int timeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.setQueryTimeout(timeout); return statement.executeLargeUpdate(); }
    }

    private static long count(Connection connection, Table table, int timeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table.sql())) {
            statement.setQueryTimeout(timeout);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Row count is unavailable.");
                long value = result.getLong(1);
                if (result.next()) throw new SQLException("Row count is ambiguous.");
                return value;
            }
        }
    }

    private static String quote(String name) { return "\"" + StagedMappingDefinition.identifier(name) + "\""; }
}

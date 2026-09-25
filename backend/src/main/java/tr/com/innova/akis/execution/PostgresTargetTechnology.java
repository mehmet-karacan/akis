package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.Column;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.Result;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.WriteMode;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;
import tr.com.innova.akis.knowledge.PostgresCopyStagingTransfer;
import tr.com.innova.akis.knowledge.PostgresWorkStructure;
import tr.com.innova.akis.knowledge.PostgresWorkTableManager;
import tr.com.innova.akis.knowledge.StagingTransferPort;
import tr.com.innova.akis.knowledge.WorkObjectStore;
import tr.com.innova.akis.knowledge.WorkTableManagerPort;

/**
 * PostgreSQL bundle: work tables and COPY staging on the target database, the target-local akis ledger,
 * and APPEND, TRUNCATE_LOAD or MERGE ({@code INSERT ... ON CONFLICT (KEY_COLUMNS) DO UPDATE}) published in a single
 * transaction. ATOMIC_DELETE_INSERT is not supported.
 */
@Component
final class PostgresTargetTechnology implements TargetTechnology {
    private final TargetLedgerPort ledger = new JdbcPostgresTargetLedgerAdapter();
    private final ObjectMapper mapper;

    PostgresTargetTechnology(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public DatabaseType technology() { return DatabaseType.POSTGRESQL; }

    @Override public String databaseIdentity(Connection connection) throws SQLException { return PostgresWorkTableManager.databaseIdentity(connection); }

    @Override public TargetLedgerPort ledger() { return ledger; }

    @Override public TargetIdentityPort identity() { return new JdbcPostgresTargetIdentityReader(); }

    @Override public WorkTableManagerPort workTables(WorkObjectStore store) { return new PostgresWorkTableManager(store); }

    @Override public StagingTransferPort stagingTransfer() { return new PostgresCopyStagingTransfer(); }

    @Override public String expectedWorkStructure(List<WorkTableManagerPort.Column> columns) { return PostgresWorkStructure.expected(columns); }

    @Override
    public void verifyStaged(StagedRuntimePlan plan, Connection sourceConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots, Connection targetConnection) {
        new JdbcPostgresSchemaPreflight(mapper).verifyStaged(plan, sourceConnection, snapshots, targetConnection);
    }

    @Override
    public void verifyLockedTarget(StagedRuntimePlan plan, Connection targetConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots) {
        new JdbcPostgresSchemaPreflight(mapper).verifyLockedStagedTarget(plan, targetConnection, snapshots);
    }

    @Override
    public void verifyWorkObject(Connection connection, WorkTableManagerPort.Created object) {
        try {
            if (!object.databaseIdentity().equals(PostgresWorkTableManager.databaseIdentity(connection))
                    || !object.structureHash().equals(PostgresWorkStructure.read(connection, object.table(), 30))) throw new IllegalStateException();
            try (var statement = connection.prepareStatement(
                    "SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'r'")) {
                statement.setString(1, object.table().owner());
                statement.setString(2, object.table().name());
                statement.setQueryTimeout(30);
                try (var result = statement.executeQuery()) {
                    if (!(result.next() && result.getLong(1) == object.objectId() && !result.next())) throw new IllegalStateException();
                }
            }
        }
        catch (SQLException | RuntimeException failure) { throw new IllegalStateException("KM çalışma nesnesi doğrulanamadı."); }
    }

    @Override public boolean supportsWriteMode(WriteMode mode) { return mode == WriteMode.APPEND || mode == WriteMode.TRUNCATE_LOAD || mode == WriteMode.MERGE; }

    @Override
    public Result publish(Connection connection, TargetLedgerContext context, PublishEvidence evidence, JdbcStagingTransfer.Table stage,
            JdbcStagingTransfer.Table target, List<Column> columns, int timeoutSeconds, Runnable lockedPreflight, Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction, String oracleHint, WriteMode mode, List<String> keyColumns) {
        if (!supportsWriteMode(mode) || mode == WriteMode.TRUNCATE_LOAD && !keyColumns.isEmpty()
                || mode == WriteMode.MERGE && keyColumns.isEmpty())
            throw new IllegalArgumentException("PostgreSQL hedefi bu yazma modunu desteklemiyor.");
        return new JdbcPostgresAtomicRefreshWriter(ledger).publish(connection, context, evidence, stage, target, columns, timeoutSeconds,
                lockedPreflight, leaseCheckpoint, transaction, mode, keyColumns);
    }
}

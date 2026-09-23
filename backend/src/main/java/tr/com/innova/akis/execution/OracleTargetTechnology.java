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
import tr.com.innova.akis.knowledge.OracleWorkStructure;
import tr.com.innova.akis.knowledge.OracleWorkTableManager;
import tr.com.innova.akis.knowledge.StagingTransferPort;
import tr.com.innova.akis.knowledge.WorkObjectStore;
import tr.com.innova.akis.knowledge.WorkTableManagerPort;

/** Oracle bundle: the behaviour the staged runtime had before a second technology existed. */
@Component
final class OracleTargetTechnology implements TargetTechnology {
    private final TargetLedgerPort ledger;
    private final ObjectMapper mapper;

    OracleTargetTechnology(TargetLedgerPort ledger, ObjectMapper mapper) { this.ledger = ledger; this.mapper = mapper; }

    @Override public DatabaseType technology() { return DatabaseType.ORACLE; }

    @Override public String databaseIdentity(Connection connection) throws SQLException { return OracleWorkTableManager.databaseIdentity(connection); }

    @Override public TargetLedgerPort ledger() { return ledger; }

    @Override public TargetIdentityPort identity() { return new JdbcOracleTargetIdentityReader(); }

    @Override public WorkTableManagerPort workTables(WorkObjectStore store) { return new OracleWorkTableManager(store); }

    @Override public StagingTransferPort stagingTransfer() { return new JdbcStagingTransfer(); }

    @Override public String expectedWorkStructure(List<WorkTableManagerPort.Column> columns) { return OracleWorkStructure.expected(columns); }

    @Override
    public void verifyStaged(StagedRuntimePlan plan, Connection sourceConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots, Connection targetConnection) {
        new JdbcOracleSchemaPreflight(mapper).verifyStaged(plan, sourceConnection, snapshots, targetConnection);
    }

    @Override
    public void verifyLockedTarget(StagedRuntimePlan plan, Connection targetConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots) {
        new JdbcOracleSchemaPreflight(mapper).verifyLockedStagedTarget(plan, targetConnection, snapshots);
    }

    @Override
    public void verifyWorkObject(Connection connection, WorkTableManagerPort.Created object) {
        try {
            if (!object.databaseIdentity().equals(OracleWorkTableManager.databaseIdentity(connection))
                    || !object.structureHash().equals(OracleWorkStructure.read(connection, object.table(), 30))) throw new IllegalStateException();
            try (var statement = connection.prepareStatement(
                    "SELECT OBJECT_ID FROM ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE='TABLE' AND SUBOBJECT_NAME IS NULL")) {
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

    @Override public boolean supportsWriteMode(WriteMode mode) { return true; }

    @Override
    public Result publish(Connection connection, TargetLedgerContext context, PublishEvidence evidence, JdbcStagingTransfer.Table stage,
            JdbcStagingTransfer.Table target, List<Column> columns, int timeoutSeconds, Runnable lockedPreflight, Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction, String oracleHint, WriteMode mode, List<String> keyColumns) {
        return new JdbcStagedAtomicRefreshWriter(ledger).publish(connection, context, evidence, stage, target, columns, timeoutSeconds,
                lockedPreflight, leaseCheckpoint, transaction, oracleHint, mode, keyColumns);
    }
}

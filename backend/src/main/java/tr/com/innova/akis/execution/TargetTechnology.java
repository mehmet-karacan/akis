package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.Column;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.Result;
import tr.com.innova.akis.execution.JdbcStagedAtomicRefreshWriter.WriteMode;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;
import tr.com.innova.akis.knowledge.StagingTransferPort;
import tr.com.innova.akis.knowledge.WorkObjectStore;
import tr.com.innova.akis.knowledge.WorkTableManagerPort;

/**
 * Everything the staged runtime needs from one target technology, in one bundle: how its work tables are managed, how a
 * transfer writes into them, how the target is identified and preflighted and how a sealed work table is published.
 * The orchestrator and the publish facade choose the bundle from the pinned target binding, never with an {@code if}.
 */
interface TargetTechnology {

    DatabaseType technology();

    /** Stable hash of the database this session is connected to; compared between target, work and data sessions. */
    String databaseIdentity(Connection connection) throws SQLException;

    /** Target-local publication ledger of this technology. */
    TargetLedgerPort ledger();

    TargetIdentityPort identity();

    WorkTableManagerPort workTables(WorkObjectStore store);

    StagingTransferPort stagingTransfer();

    /** Declared structure hash of the work columns, for adopting a sealed table on RESUME. */
    String expectedWorkStructure(List<WorkTableManagerPort.Column> columns);

    /** Full preflight before any work object exists: live sources, live target and the mapping rules. */
    void verifyStaged(StagedRuntimePlan plan, Connection sourceConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots, Connection targetConnection);

    /** Publish-time preflight on the locked target connection. */
    void verifyLockedTarget(StagedRuntimePlan plan, Connection targetConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots);

    /** Re-verifies the sealed work object on the target's own data session before the target is modified. */
    void verifyWorkObject(Connection connection, WorkTableManagerPort.Created object);

    /** Write modes this technology can publish; the planner and the facade both reject anything else. */
    boolean supportsWriteMode(WriteMode mode);

    Result publish(Connection connection, TargetLedgerContext context, PublishEvidence evidence, JdbcStagingTransfer.Table stage,
            JdbcStagingTransfer.Table target, List<Column> columns, int timeoutSeconds, Runnable lockedPreflight, Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction, String oracleHint, WriteMode mode, List<String> keyColumns);
}

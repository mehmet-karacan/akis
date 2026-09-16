package tr.com.innova.akis.execution;

import java.sql.*;
import java.util.*;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import tr.com.innova.akis.knowledge.StagedMappingDefinition;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.*;

/** One target-local DML+ledger transaction. No CREATE/GRANT/TRUNCATE/DROP and no implicit retry. */
final class JdbcStagedAtomicRefreshWriter {
    enum Outcome { COMMITTED, ALREADY_RECORDED, ROLLED_BACK, UNKNOWN }
    record Result(Outcome outcome,Long deleted,Long inserted) { }
    record Column(String stage,String target) {
        Column { StagedMappingDefinition.identifier(stage); StagedMappingDefinition.identifier(target); }
    }
    private final OracleTargetLedgerPort ledger;
    JdbcStagedAtomicRefreshWriter(OracleTargetLedgerPort ledger) { this.ledger=Objects.requireNonNull(ledger); }
    Result publish(Connection connection,TargetLedgerContext context,PublishEvidence evidence,
            Table stage,Table target,List<Column> columns,int timeoutSeconds,Runnable lockedPreflight,Runnable leaseCheckpoint) {
        return publish(connection, context, evidence, stage, target, columns, timeoutSeconds,
                lockedPreflight, leaseCheckpoint, JdbcTransactionBoundary.direct(connection));
    }
    Result publish(Connection connection,TargetLedgerContext context,PublishEvidence evidence,
            Table stage,Table target,List<Column> columns,int timeoutSeconds,Runnable lockedPreflight,Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction) {
        Objects.requireNonNull(transaction);
        Objects.requireNonNull(lockedPreflight); Objects.requireNonNull(leaseCheckpoint);
        columns=List.copyOf(columns);
        if (columns.isEmpty() || columns.size()>256 || timeoutSeconds<1 || timeoutSeconds>3600 || evidence.stageRowCount()<0
                || columns.stream().map(Column::target).distinct().count()!=columns.size()
                || evidence.stageRowCount()!=evidence.publishedRowCount() || evidence.rejectedRowCount()!=0 || stage.equals(target))
            throw new IllegalArgumentException("Atomik stage yayın sözleşmesi geçersiz.");
        boolean committing=false,prepared=false;
        try {
            if (connection.getAutoCommit() || connection.isReadOnly()) throw new IllegalArgumentException("Yazılabilir tek transaction gerekir.");
            leaseCheckpoint.run();
            DataLedgerSession session=ledger.bindData(connection,context);
            PublishPreparation preparation=session.preparePublish(evidence);
            if (preparation.alreadyRecorded()) {
                transaction.rollback();
                return new Result(Outcome.ALREADY_RECORDED,null,evidence.publishedRowCount());
            }
            prepared=true;
            command(connection,"LOCK TABLE "+target.sql()+" IN EXCLUSIVE MODE NOWAIT",timeoutSeconds);
            command(connection,"LOCK TABLE "+stage.sql()+" IN SHARE MODE NOWAIT",timeoutSeconds);
            lockedPreflight.run();
            leaseCheckpoint.run();
            if (count(connection,stage,timeoutSeconds)!=evidence.stageRowCount()) throw new IllegalStateException("Mühürlü stage satır sayısı değişmiş.");
            long deleted=update(connection,"DELETE FROM "+target.sql(),timeoutSeconds);
            String targetColumns=String.join(",",columns.stream().map(c->quote(c.target())).toList());
            String stageColumns=String.join(",",columns.stream().map(c->quote(c.stage())).toList());
            long inserted=update(connection,"INSERT INTO "+target.sql()+" ("+targetColumns+") SELECT "+stageColumns+" FROM "+stage.sql(),timeoutSeconds);
            if (inserted!=evidence.stageRowCount() || count(connection,target,timeoutSeconds)!=inserted)
                throw new IllegalStateException("Hedef satır doğrulaması başarısız.");
            leaseCheckpoint.run();
            session.recordPublish(preparation);
            leaseCheckpoint.run();
            committing=true;
            transaction.commit();
            return new Result(Outcome.COMMITTED,deleted,inserted);
        } catch (SQLException | RuntimeException failure) {
            boolean rolledBack;
            try { transaction.rollback(); rolledBack=true; } catch (SQLException | RuntimeException rollback) { rolledBack=false; }
            // A failed PREPARE might represent an already-committed publish; do not classify absent evidence optimistically.
            return new Result(committing || !rolledBack || !prepared ? Outcome.UNKNOWN : Outcome.ROLLED_BACK,null,null);
        }
    }
    private static void command(Connection connection,String sql,int timeout) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement(sql)) { statement.setQueryTimeout(timeout); statement.execute(); }
    }
    private static long update(Connection connection,String sql,int timeout) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement(sql)) { statement.setQueryTimeout(timeout); return statement.executeLargeUpdate(); }
    }
    private static long count(Connection connection,Table table,int timeout) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement("SELECT COUNT(*) FROM "+table.sql())) {
            statement.setQueryTimeout(timeout);
            try (ResultSet result=statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing count"); long rows=result.getLong(1);
                if (result.wasNull() || result.next()) throw new SQLException("Ambiguous count"); return rows;
            }
        }
    }
    private static String quote(String name) { return "\""+StagedMappingDefinition.identifier(name)+"\""; }
}

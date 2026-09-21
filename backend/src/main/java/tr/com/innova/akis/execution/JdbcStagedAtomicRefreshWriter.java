package tr.com.innova.akis.execution;

import java.sql.*;
import java.util.*;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import tr.com.innova.akis.knowledge.StagedMappingDefinition;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.*;

/** Target-local DML+ledger publication. TRUNCATE_LOAD is explicitly non-atomic; no implicit retry. */
final class JdbcStagedAtomicRefreshWriter {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(JdbcStagedAtomicRefreshWriter.class);
    enum WriteMode { APPEND, MERGE, TRUNCATE_LOAD, ATOMIC_DELETE_INSERT }
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
                lockedPreflight, leaseCheckpoint, JdbcTransactionBoundary.direct(connection), "");
    }
    Result publish(Connection connection,TargetLedgerContext context,PublishEvidence evidence,
            Table stage,Table target,List<Column> columns,int timeoutSeconds,Runnable lockedPreflight,Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction) {
        return publish(connection,context,evidence,stage,target,columns,timeoutSeconds,lockedPreflight,leaseCheckpoint,transaction,"");
    }
    Result publish(Connection connection,TargetLedgerContext context,PublishEvidence evidence,
            Table stage,Table target,List<Column> columns,int timeoutSeconds,Runnable lockedPreflight,Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction,String oracleHint) {
        return publish(connection,context,evidence,stage,target,columns,timeoutSeconds,lockedPreflight,leaseCheckpoint,transaction,oracleHint,WriteMode.ATOMIC_DELETE_INSERT,List.of());
    }
    Result publish(Connection connection,TargetLedgerContext context,PublishEvidence evidence,
            Table stage,Table target,List<Column> columns,int timeoutSeconds,Runnable lockedPreflight,Runnable leaseCheckpoint,
            JdbcTransactionBoundary transaction,String oracleHint,WriteMode mode,List<String> keyColumns) {
        Objects.requireNonNull(transaction);
        Objects.requireNonNull(mode);keyColumns=List.copyOf(keyColumns);
        oracleHint=tr.com.innova.akis.knowledge.JdbcStagingTransfer.safeHint(oracleHint);
        Objects.requireNonNull(lockedPreflight); Objects.requireNonNull(leaseCheckpoint);
        columns=List.copyOf(columns);
        boolean invalidKey=false;
        for(String key:keyColumns) { boolean found=false; for(Column column:columns) if(column.target().equals(key)) { found=true;break; } if(!found) { invalidKey=true;break; } }
        if (columns.isEmpty() || columns.size()>256 || timeoutSeconds<1 || timeoutSeconds>3600 || evidence.stageRowCount()<0
                || columns.stream().map(Column::target).distinct().count()!=columns.size()
                || invalidKey
                || mode==WriteMode.MERGE && keyColumns.isEmpty()
                || evidence.stageRowCount()!=evidence.publishedRowCount() || evidence.rejectedRowCount()!=0 || stage.equals(target))
            throw new IllegalArgumentException("Atomik stage yayın sözleşmesi geçersiz.");
        boolean committing=false,prepared=false,nonReversibleStarted=false;
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
            if(mode==WriteMode.TRUNCATE_LOAD) {
                // TRUNCATE is DDL and commits implicitly, which would end the transaction the ledger preparation
                // belongs to. Discard that preparation, truncate, then re-lock and prepare again so the INSERT and
                // the ledger record commit together.
                // Plain JDBC rollback: this only discards the preparation; the session's transaction outcome is still open.
                connection.rollback(); prepared=false;
                nonReversibleStarted=true; command(connection,"TRUNCATE TABLE "+target.sql(),timeoutSeconds);
                // The ledger requires a clean transaction boundary for preparation, so prepare before taking the locks again.
                preparation=session.preparePublish(evidence);
                if (preparation.alreadyRecorded()) throw new IllegalStateException("Yayın kaydı truncate sonrasında başka bir deneme tarafından oluşturuldu.");
                prepared=true;
                command(connection,"LOCK TABLE "+target.sql()+" IN EXCLUSIVE MODE NOWAIT",timeoutSeconds);
                command(connection,"LOCK TABLE "+stage.sql()+" IN SHARE MODE NOWAIT",timeoutSeconds);
                lockedPreflight.run();
                if (count(connection,stage,timeoutSeconds)!=evidence.stageRowCount()) throw new IllegalStateException("Mühürlü stage satır sayısı değişmiş.");
            }
            long deleted=mode==WriteMode.ATOMIC_DELETE_INSERT?update(connection,"DELETE FROM "+target.sql(),timeoutSeconds):0;
            String targetColumns=String.join(",",columns.stream().map(c->quote(c.target())).toList());
            String stageColumns=String.join(",",columns.stream().map(c->quote(c.stage())).toList());
            String hint=oracleHint.isBlank()?"":" /*+ "+oracleHint+" */";
            long before=mode==WriteMode.APPEND?count(connection,target,timeoutSeconds):0;
            long inserted=mode==WriteMode.MERGE
                    ? update(connection,mergeSql(stage,target,columns,keyColumns,hint),timeoutSeconds)
                    : update(connection,"INSERT"+hint+" INTO "+target.sql()+" ("+targetColumns+") SELECT "+stageColumns+" FROM "+stage.sql(),timeoutSeconds);
            long expectedTarget=mode==WriteMode.APPEND?before+inserted:inserted;
            if (inserted!=evidence.stageRowCount() || mode!=WriteMode.MERGE && count(connection,target,timeoutSeconds)!=expectedTarget)
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
            LOG.warn("Staged publish into {} failed (prepared={}, truncated={}, committing={}, rolledBack={}): {}",target.sql(),prepared,nonReversibleStarted,committing,rolledBack,failure.toString());
            // A failed PREPARE might represent an already-committed publish; do not classify absent evidence optimistically.
            return new Result(committing || nonReversibleStarted || !rolledBack || !prepared ? Outcome.UNKNOWN : Outcome.ROLLED_BACK,null,null);
        }
    }
    private static String mergeSql(Table stage,Table target,List<Column> columns,List<String> keys,String hint) {
        String on=String.join(" AND ",keys.stream().map(key->"T."+quote(key)+"=S."+quote(columns.stream().filter(column->column.target().equals(key)).findFirst().orElseThrow().stage())).toList());
        List<Column> mutable=columns.stream().filter(column->!keys.contains(column.target())).toList();
        String update=mutable.isEmpty()?"":" WHEN MATCHED THEN UPDATE SET "+String.join(",",mutable.stream().map(column->"T."+quote(column.target())+"=S."+quote(column.stage())).toList());
        String targetColumns=String.join(",",columns.stream().map(column->quote(column.target())).toList());
        String stageColumns=String.join(",",columns.stream().map(column->"S."+quote(column.stage())).toList());
        return "MERGE"+hint+" INTO "+target.sql()+" T USING "+stage.sql()+" S ON ("+on+")"+update+" WHEN NOT MATCHED THEN INSERT ("+targetColumns+") VALUES ("+stageColumns+")";
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

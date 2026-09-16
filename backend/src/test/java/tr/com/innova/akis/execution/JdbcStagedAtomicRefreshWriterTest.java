package tr.com.innova.akis.execution;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static tr.com.innova.akis.execution.OracleTargetLedgerPort.*;

class JdbcStagedAtomicRefreshWriterTest {
    private final Connection connection=mock(Connection.class);
    private final OracleTargetLedgerPort ledger=mock(OracleTargetLedgerPort.class);
    private final DataLedgerSession session=mock(DataLedgerSession.class);
    private final TargetLedgerContext context=new TargetLedgerContext("a".repeat(64),1,UUID.randomUUID(),UUID.randomUUID(),1,"b".repeat(64),"c".repeat(64));
    private final PublishEvidence evidence=new PublishEvidence("KM_IKM","d".repeat(64),"e".repeat(64),1201,1201,0,null,null);
    private void setup(boolean recorded) throws Exception {
        when(ledger.bindData(connection,context)).thenReturn(session);
        when(session.preparePublish(evidence)).thenReturn(new PublishPreparation(UUID.randomUUID(),evidence,"guard",recorded));
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            var statement=mock(PreparedStatement.class);
            when(statement.executeLargeUpdate()).thenReturn(1201L);
            var result=mock(ResultSet.class); when(result.next()).thenReturn(true,false); when(result.getLong(1)).thenReturn(1201L);
            when(statement.executeQuery()).thenReturn(result); return statement;
        });
    }
    private JdbcStagedAtomicRefreshWriter.Result publish() {
        return new JdbcStagedAtomicRefreshWriter(ledger).publish(connection,context,evidence,
                new Table("WORK","AKIS_C_TEST"),new Table("DATA","ITEMS"),
                List.of(new JdbcStagedAtomicRefreshWriter.Column("ID","ID")),30,()->{},()->{});
    }
    @Test void dataAndLedgerCommitOnSameConnection() throws Exception {
        setup(false);
        assertEquals(JdbcStagedAtomicRefreshWriter.Outcome.COMMITTED,publish().outcome());
        var order=inOrder(session,connection); order.verify(session).recordPublish(any()); order.verify(connection).commit();
        verify(connection,never()).rollback();
    }
    @Test void recordedPublicationDoesNotApplyDmlAgain() throws Exception {
        setup(true);
        assertEquals(JdbcStagedAtomicRefreshWriter.Outcome.ALREADY_RECORDED,publish().outcome());
        verify(connection,never()).prepareStatement(anyString()); verify(connection,never()).commit();
    }
    @Test void commitLossIsUnknownEvenIfRollbackSucceeds() throws Exception {
        setup(false); doThrow(new SQLException("ack lost")).when(connection).commit();
        assertEquals(JdbcStagedAtomicRefreshWriter.Outcome.UNKNOWN,publish().outcome());
        verify(connection,times(1)).commit();
    }
    @Test void dmlFailureRollsBackWithoutRecordingPublish() throws Exception {
        setup(false); when(connection.prepareStatement(startsWith("DELETE"))).thenThrow(new SQLException("denied"));
        assertEquals(JdbcStagedAtomicRefreshWriter.Outcome.ROLLED_BACK,publish().outcome());
        verify(session,never()).recordPublish(any()); verify(connection,never()).commit(); verify(connection).rollback();
    }
    @Test void guardedConnectionUsesOwningTransactionBoundary() throws Exception {
        setup(false);
        var transaction=mock(tr.com.innova.akis.knowledge.JdbcTransactionBoundary.class);
        var result=new JdbcStagedAtomicRefreshWriter(ledger).publish(connection,context,evidence,
                new Table("WORK","AKIS_C_TEST"),new Table("DATA","ITEMS"),
                List.of(new JdbcStagedAtomicRefreshWriter.Column("ID","ID")),30,()->{},()->{},transaction);
        assertEquals(JdbcStagedAtomicRefreshWriter.Outcome.COMMITTED,result.outcome());
        verify(transaction).commit(); verify(connection,never()).commit();
    }
    @Test void uncheckedCommitFailureRemainsUnknown() throws Exception {
        setup(false);
        var transaction=mock(tr.com.innova.akis.knowledge.JdbcTransactionBoundary.class);
        doThrow(new IllegalStateException("managed session failed")).when(transaction).commit();
        var result=new JdbcStagedAtomicRefreshWriter(ledger).publish(connection,context,evidence,
                new Table("WORK","AKIS_C_TEST"),new Table("DATA","ITEMS"),
                List.of(new JdbcStagedAtomicRefreshWriter.Column("ID","ID")),30,()->{},()->{},transaction);
        assertEquals(JdbcStagedAtomicRefreshWriter.Outcome.UNKNOWN,result.outcome());
        verify(transaction,times(1)).commit();
    }
}

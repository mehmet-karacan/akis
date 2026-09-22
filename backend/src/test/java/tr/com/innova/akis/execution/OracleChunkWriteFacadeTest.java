package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.TargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;

class OracleChunkWriteFacadeTest {

    @Test
    void alreadyRecordedReceiptNeverExecutesBusinessInsert() throws Exception {
        Fixture fixture = new Fixture(true);

        OracleChunkWriteFacade.Result result = fixture.write(new Transaction());

        assertEquals(OracleChunkWriteFacade.Outcome.ALREADY_RECORDED, result.outcome());
        verify(fixture.connection, never()).prepareStatement(anyString());
        verify(fixture.ledgerSession, never()).recordBatch(any());
    }

    @Test
    void businessInsertAndReceiptCommitAsOneLocalTransaction() throws Exception {
        Fixture fixture = new Fixture(false);
        Transaction transaction = new Transaction();

        OracleChunkWriteFacade.Result result = fixture.write(transaction);

        assertEquals(OracleChunkWriteFacade.Outcome.COMMIT_CONFIRMED, result.outcome());
        assertEquals(1, transaction.commits);
        assertEquals(0, transaction.rollbacks);
        verify(fixture.statement).executeBatch();
        verify(fixture.ledgerSession).recordBatch(any());
    }

    @Test
    void lostCommitResponseIsUnknownAndNeverRetriedInsideTheFacade() throws Exception {
        Fixture fixture = new Fixture(false);
        Transaction transaction = new Transaction();
        transaction.failCommit = true;

        OracleChunkWriteFacade.Result result = fixture.write(transaction);

        assertEquals(OracleChunkWriteFacade.Outcome.OUTCOME_UNKNOWN, result.outcome());
        assertEquals(1, transaction.commits);
        verify(fixture.statement).executeBatch();
        verify(fixture.ledgerSession).recordBatch(any());
    }

    @Test
    void unknownJdbcBatchCountIsNeverInventedAsAnExactInsertCount() throws Exception {
        Fixture fixture = new Fixture(false);
        when(fixture.statement.executeBatch()).thenReturn(
                new int[]{Statement.SUCCESS_NO_INFO});
        Transaction transaction = new Transaction();

        OracleChunkWriteFacade.Result result = fixture.write(transaction);

        assertEquals(OracleChunkWriteFacade.Outcome.ROLLBACK_CONFIRMED, result.outcome());
        assertEquals(0, transaction.commits);
        assertEquals(1, transaction.rollbacks);
        verify(fixture.ledgerSession, never()).recordBatch(any());
    }

    private static final class Fixture {
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private final TargetLedgerPort ledger = mock(TargetLedgerPort.class);
        private final DataLedgerSession ledgerSession = mock(DataLedgerSession.class);
        private final BatchEvidence evidence = new BatchEvidence(
                "LOAD", "P0", "b".repeat(64), 1,
                "c".repeat(64), 1, 16);
        private final TransferExecutionPlan plan = new TransferExecutionPlan(
                1, "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "99",
                "SRC", "T1", "STAGE", "W1", "ID",
                List.of(new TransferExecutionPlan.ColumnMapping("VALUE", "VALUE", "NUMBER")),
                10, 1024, "AKIS_RANGE_BATCH/1");
        private final StreamingRowReader.Batch batch = new StreamingRowReader.Batch(
                List.of(new StreamingRowReader.Row(new BigDecimal("1"),
                        List.of(new StreamingRowReader.Cell("NUMBER", "42")), 16)),
                new BigDecimal("1"), 16, "c".repeat(64));

        private Fixture(boolean already) throws Exception {
            when(connection.getAutoCommit()).thenReturn(false);
            when(connection.isReadOnly()).thenReturn(false);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenReturn(new int[]{1});
            when(ledger.bindData(any(), any())).thenReturn(ledgerSession);
            when(ledgerSession.prepareBatch(evidence)).thenReturn(new BatchPreparation(
                    UUID.randomUUID(), evidence, already ? null : "d".repeat(32), already));
        }

        private OracleChunkWriteFacade.Result write(JdbcTransactionBoundary transaction) {
            return new OracleChunkWriteFacade(ledger).write(connection,
                    new TargetLedgerContext("e".repeat(64), 1, UUID.randomUUID(),
                            UUID.randomUUID(), 1, "f".repeat(64), "a".repeat(64)),
                    evidence, plan, batch, () -> { }, () -> { }, transaction);
        }
    }

    private static final class Transaction implements JdbcTransactionBoundary {
        private int commits;
        private int rollbacks;
        private boolean failCommit;
        public void commit() throws SQLException {
            commits++;
            if (failCommit) throw new SQLException("lost response");
        }
        public void rollback() { rollbacks++; }
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.OracleTargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.RecordedEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.TargetLedgerContext;

class OracleChunkReceiptVerifierTest {

    private final OracleTargetLedgerPort ledger = mock(OracleTargetLedgerPort.class);
    private final Connection connection = mock(Connection.class);
    private final ReconciliationSession session = mock(ReconciliationSession.class);
    private final TargetLedgerContext context = new TargetLedgerContext("a".repeat(64), 3,
            UUID.randomUUID(), UUID.randomUUID(), 2, "b".repeat(64), "c".repeat(64));
    private final BatchEvidence evidence = new BatchEvidence("LOAD", "P0", "d".repeat(64),
            1, "e".repeat(64), 4, 128);

    @Test
    void onlyFreshTargetLocalEvidenceConfirmsAProjectedCommit() throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(false);
        when(ledger.bindReconciliation(connection, context)).thenReturn(session);
        when(session.verifyBatch(evidence)).thenReturn(Optional.of(new RecordedEvidence(
                context.runUuid(), context.attemptNumber(), context.fenceToken(),
                OffsetDateTime.now())));

        assertTrue(new OracleChunkReceiptVerifier(ledger).verify(connection, context, evidence,
                "oracle-batch:" + evidence.batchKeyHash()));
        verify(session).verifyBatch(evidence);
    }

    @Test
    void mismatchedReferenceFailsClosedWithoutReadingOracle() {
        assertFalse(new OracleChunkReceiptVerifier(ledger).verify(connection, context, evidence,
                "oracle-batch:" + "f".repeat(64)));
    }

    @Test
    void autoCommitReconciliationConnectionIsRejected() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        assertFalse(new OracleChunkReceiptVerifier(ledger).verify(connection, context, evidence,
                "oracle-batch:" + evidence.batchKeyHash()));
    }
}

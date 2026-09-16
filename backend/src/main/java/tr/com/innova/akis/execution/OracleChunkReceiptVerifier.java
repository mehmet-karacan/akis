package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.util.Objects;

import tr.com.innova.akis.execution.OracleTargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.TargetLedgerContext;

/** Reads committed batch evidence on a fresh Oracle connection. */
final class OracleChunkReceiptVerifier {

    private final OracleTargetLedgerPort ledger;

    OracleChunkReceiptVerifier(OracleTargetLedgerPort ledger) {
        this.ledger = Objects.requireNonNull(ledger);
    }

    boolean verify(Connection reconciliationConnection, TargetLedgerContext context,
            BatchEvidence evidence, String targetReceiptReference) {
        Objects.requireNonNull(reconciliationConnection);
        Objects.requireNonNull(context);
        Objects.requireNonNull(evidence);
        String expectedReference = "oracle-batch:" + evidence.batchKeyHash();
        if (!expectedReference.equals(targetReceiptReference)) return false;
        try {
            if (reconciliationConnection.getAutoCommit()
                    || reconciliationConnection.isReadOnly()) return false;
            return ledger.bindReconciliation(reconciliationConnection, context)
                    .verifyBatch(evidence).isPresent();
        }
        catch (RuntimeException | java.sql.SQLException failure) {
            return false;
        }
    }
}

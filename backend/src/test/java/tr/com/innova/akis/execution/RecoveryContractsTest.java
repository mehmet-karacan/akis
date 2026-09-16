package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RecoveryContractsTest {

    @Test
    void automaticRetryCannotIncludeReconciliation() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionRecoveryPolicy(
                1, Set.of(ExecutionRecoveryPolicy.RecoveryAction.RECONCILE),
                3, Duration.ofSeconds(1), Duration.ofMinutes(1), true));
    }

    @Test
    void transactionGroupRejectsDdl() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionGroupPlan(
                "G1", UUID.randomUUID(), 0, List.of("DDL"),
                ProcedureRuntimePlan.TransactionIsolation.READ_COMMITTED,
                TransactionGroupPlan.CommitBoundary.END_OF_GROUP,
                "operation", true));
    }

    @Test
    void transferCountsRemainLong() {
        long rows = 3_000_000_000L;
        long bytes = 9_007_199_254_740_993L;
        TransferExecutionPlan plan = new TransferExecutionPlan(
                1, "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "123456789",
                "SRC", "ORDERS", "TGT", "ORDERS", "ID",
                List.of(new TransferExecutionPlan.ColumnMapping("ID", "ID", "NUMBER")),
                rows, bytes, "AKIS_CANONICAL_ROW_V1");

        assertEquals(rows, plan.maximumRowsPerBatch());
        assertEquals(bytes, plan.maximumBytesPerBatch());
    }
}

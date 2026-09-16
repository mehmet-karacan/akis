package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;

/** Immutable first-version Oracle range-transfer contract. */
record TransferExecutionPlan(
        int contractVersion,
        String runtimePlanHash,
        UUID sourceConnectionVersionUuid,
        UUID targetConnectionVersionUuid,
        String sourceSnapshotScn,
        String sourceOwner,
        String sourceTable,
        String targetOwner,
        String targetTable,
        String numericKeyColumn,
        List<ColumnMapping> columns,
        long maximumRowsPerBatch,
        long maximumBytesPerBatch,
        String canonicalSerializationVersion) {

    static final int CURRENT_VERSION = 1;

    TransferExecutionPlan {
        if (contractVersion != CURRENT_VERSION || !hash(runtimePlanHash)
                || sourceConnectionVersionUuid == null || targetConnectionVersionUuid == null
                || blank(sourceSnapshotScn) || blank(sourceOwner) || blank(sourceTable)
                || blank(targetOwner) || blank(targetTable) || blank(numericKeyColumn)
                || columns == null || columns.isEmpty()
                || maximumRowsPerBatch < 1 || maximumBytesPerBatch < 1
                || blank(canonicalSerializationVersion)) {
            throw new IllegalArgumentException("Transfer execution plan is incomplete.");
        }
        columns = List.copyOf(columns);
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record ColumnMapping(String source, String target, String oracleType) {
        ColumnMapping {
            if (blank(source) || blank(target) || blank(oracleType)) {
                throw new IllegalArgumentException("Transfer column mapping is incomplete.");
            }
        }
    }
}

package tr.com.innova.akis.execution;

import java.util.ArrayList;
import java.util.List;

/** A bounded, canonical and immutable source batch tied to one runtime plan. */
record OraclePilotBatch(
        String runtimePlanHash,
        List<OraclePilotColumn> columns,
        List<List<OraclePilotCell>> rows,
        String payloadHash,
        long byteCount) {

    OraclePilotBatch {
        columns = List.copyOf(columns);
        List<List<OraclePilotCell>> safeRows = new ArrayList<>(rows.size());
        for (List<OraclePilotCell> row : rows) {
            safeRows.add(List.copyOf(row));
        }
        rows = List.copyOf(safeRows);
    }
}

record OraclePilotColumn(String sourceColumn, OraclePilotColumnType type) {
}

record OraclePilotCell(OraclePilotColumnType type, String canonicalValue) {

    boolean isNull() {
        return canonicalValue == null;
    }
}

enum OraclePilotColumnType {
    NUMBER,
    VARCHAR2,
    TIMESTAMP
}

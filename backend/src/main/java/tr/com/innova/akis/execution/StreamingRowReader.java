package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.util.List;

interface StreamingRowReader {
    ReadResult read(Range range, BatchConsumer consumer);

    record Range(BigDecimal lowerExclusive, BigDecimal upperInclusive) {
        public Range {
            if (upperInclusive == null
                    || (lowerExclusive != null && lowerExclusive.compareTo(upperInclusive) >= 0)) {
                throw new IllegalArgumentException("Transfer range is invalid.");
            }
        }
    }

    record Cell(String oracleType, String canonicalValue) { }
    record Row(BigDecimal key, List<Cell> cells, long canonicalBytes) {
        public Row { cells = List.copyOf(cells); }
    }
    record Batch(List<Row> rows, BigDecimal lastKey, long canonicalBytes, String payloadHash) {
        public Batch { rows = List.copyOf(rows); }
    }
    record ReadResult(long rows, long bytes, BigDecimal lastCommittedKey) { }

    @FunctionalInterface
    interface BatchConsumer {
        /** Return true only after target-local receipt and commit are confirmed. */
        boolean commit(Batch batch);
    }
}

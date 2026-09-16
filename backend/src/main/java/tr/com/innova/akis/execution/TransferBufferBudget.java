package tr.com.innova.akis.execution;

/** Two-dimensional hard buffer limit; fetchSize alone is not a memory guarantee. */
final class TransferBufferBudget {

    private final long maximumRows;
    private final long maximumBytes;
    private long rows;
    private long bytes;

    TransferBufferBudget(long maximumRows, long maximumBytes) {
        if (maximumRows < 1 || maximumBytes < 1) {
            throw new IllegalArgumentException("Transfer buffer budget must be positive.");
        }
        this.maximumRows = maximumRows;
        this.maximumBytes = maximumBytes;
    }

    boolean canAccept(long rowBytes) {
        if (rowBytes < 0) throw new IllegalArgumentException("Row byte count cannot be negative.");
        if (rowBytes > maximumBytes) {
            throw new RowExceedsTransferBudgetException(rowBytes, maximumBytes);
        }
        return rows < maximumRows && bytes <= maximumBytes - rowBytes;
    }

    void accept(long rowBytes) {
        if (!canAccept(rowBytes)) {
            throw new IllegalStateException("Transfer buffer is full.");
        }
        rows = Math.addExact(rows, 1);
        bytes = Math.addExact(bytes, rowBytes);
    }

    boolean isEmpty() { return rows == 0; }
    long rows() { return rows; }
    long bytes() { return bytes; }

    void reset() {
        rows = 0;
        bytes = 0;
    }
}

final class RowExceedsTransferBudgetException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    RowExceedsTransferBudgetException(long actual, long maximum) {
        super("One source row exceeds the configured transfer byte budget (actual="
                + actual + ", maximum=" + maximum + ").");
    }
}

package tr.com.innova.akis.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Holds at most one bounded Procedure rowset for one run generation.
 *
 * <p>The store is deliberately not a Spring component. Its owner must create one
 * instance per active run and close it when the run stops. A rowset can only be
 * consumed once, by the declared immediately-adjacent consumer task. The
 * callback boundary ensures that the store drops its payload reference on both
 * success and failure.
 */
final class ProcedureRunScopedRowsetStore implements AutoCloseable {

    static final int MAXIMUM_ROWS = 1_000;
    static final int MAXIMUM_COLUMNS = 256;
    static final long MAXIMUM_BYTES = 16_777_216L;

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TASK_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern ORACLE_IDENTIFIER =
            Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private static final int INTEGER_BYTES = Integer.BYTES;

    private final Scope scope;
    private Pending pending;
    private boolean closed;

    ProcedureRunScopedRowsetStore(Scope scope) {
        this.scope = Objects.requireNonNull(scope, "Procedure rowset scope is required.");
    }

    synchronized Handle store(
            int producerTaskIndex,
            String producerTaskId,
            String consumerTaskId,
            List<Column> columns,
            List<List<Cell>> rows) {
        ensureOpen();
        if (pending != null) {
            throw new IllegalStateException("A Procedure rowset is already pending consumption.");
        }
        String producer = canonicalTaskId(producerTaskId, "Producer task id");
        String consumer = canonicalTaskId(consumerTaskId, "Consumer task id");
        if (producerTaskIndex < 1 || producerTaskIndex == Integer.MAX_VALUE
                || producer.equals(consumer)) {
            throw new IllegalArgumentException("Procedure rowset task adjacency is invalid.");
        }

        FrozenRowset frozen = freeze(columns, rows);
        Handle handle = new Handle(
                UUID.randomUUID(),
                scope.runUuid(),
                scope.runGeneration(),
                scope.runtimePlanHash(),
                producer,
                producerTaskIndex,
                frozen.rows().size(),
                frozen.byteCount());
        pending = new Pending(handle, consumer, producerTaskIndex + 1, frozen);
        return handle;
    }

    synchronized <T> T consume(
            Handle handle,
            int consumerTaskIndex,
            String consumerTaskId,
            RowsetConsumer<T> consumer) {
        ensureOpen();
        Objects.requireNonNull(consumer, "Procedure rowset consumer is required.");
        String taskId = canonicalTaskId(consumerTaskId, "Consumer task id");
        Pending candidate = pending;
        if (candidate == null
                || handle == null
                || !candidate.handle().equals(handle)
                || !scope.matches(handle)
                || candidate.consumerTaskIndex() != consumerTaskIndex
                || !candidate.consumerTaskId().equals(taskId)) {
            throw new IllegalArgumentException(
                    "Procedure rowset handle does not match the adjacent consumer.");
        }

        pending = null;
        try {
            return consumer.accept(candidate.rowset().view());
        }
        finally {
            candidate.close();
        }
    }

    synchronized boolean hasPending() {
        return pending != null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pending != null) {
            pending.close();
            pending = null;
        }
    }

    private FrozenRowset freeze(List<Column> columns, List<List<Cell>> rows) {
        if (columns == null || columns.isEmpty() || rows == null) {
            throw new IllegalArgumentException("Procedure rowset columns and rows are required.");
        }
        if (columns.size() > MAXIMUM_COLUMNS) {
            throw new IllegalArgumentException("Procedure rowset exceeds the column limit.");
        }
        if (rows.size() > MAXIMUM_ROWS) {
            throw new IllegalArgumentException("Procedure rowset exceeds the row limit.");
        }

        long bytes = INTEGER_BYTES;
        List<Column> frozenColumns = new ArrayList<>(columns.size());
        Set<String> names = new HashSet<>();
        for (Column column : columns) {
            if (column == null) {
                throw new IllegalArgumentException("Procedure rowset column is required.");
            }
            String name = canonicalColumnName(column.name());
            String canonicalName = name.toUpperCase(Locale.ROOT);
            if (!names.add(canonicalName)) {
                throw new IllegalArgumentException("Procedure rowset column names must be unique.");
            }
            Column frozen = new Column(name, Objects.requireNonNull(
                    column.type(), "Procedure rowset column type is required."));
            frozenColumns.add(frozen);
            bytes = addFramed(bytes, frozen.name());
            bytes = addFramed(bytes, frozen.type().name());
        }
        bytes = add(bytes, INTEGER_BYTES);

        List<List<Cell>> frozenRows = new ArrayList<>(rows.size());
        for (List<Cell> row : rows) {
            if (row == null || row.size() != frozenColumns.size()) {
                throw new IllegalArgumentException(
                        "Procedure rowset row does not match its columns.");
            }
            bytes = add(bytes, INTEGER_BYTES);
            List<Cell> frozenRow = new ArrayList<>(row.size());
            for (int index = 0; index < row.size(); index++) {
                Cell cell = row.get(index);
                if (cell == null || cell.type() != frozenColumns.get(index).type()) {
                    throw new IllegalArgumentException(
                            "Procedure rowset cell does not match its column type.");
                }
                Cell frozen = new Cell(cell.type(), cell.value());
                frozenRow.add(frozen);
                bytes = add(bytes, 1);
                if (frozen.value() != null) {
                    bytes = add(bytes, INTEGER_BYTES);
                    bytes = add(bytes, utf8Length(frozen.value()));
                }
            }
            frozenRows.add(List.copyOf(frozenRow));
        }
        return new FrozenRowset(
                List.copyOf(frozenColumns), List.copyOf(frozenRows), bytes);
    }

    private long addFramed(long current, String value) {
        return add(add(current, INTEGER_BYTES), utf8Length(value));
    }

    private long add(long current, long added) {
        if (added < 0 || current > MAXIMUM_BYTES - added) {
            throw new IllegalArgumentException("Procedure rowset exceeds the byte limit.");
        }
        return current + added;
    }

    /** Counts strict UTF-8 bytes without allocating a second copy of a large value. */
    private long utf8Length(String value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                bytes += 1;
            }
            else if (current <= 0x7ff) {
                bytes += 2;
            }
            else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "Procedure rowset contains malformed Unicode.");
                }
                bytes += 4;
                index++;
            }
            else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Procedure rowset contains malformed Unicode.");
            }
            else {
                bytes += 3;
            }
            if (bytes > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Procedure rowset exceeds the byte limit.");
            }
        }
        return bytes;
    }

    private String canonicalTaskId(String value, String field) {
        if (value == null || !TASK_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be canonical.");
        }
        return value;
    }

    private String canonicalColumnName(String value) {
        if (value == null || !ORACLE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Column name must be a canonical Oracle identifier.");
        }
        return value;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Procedure rowset store is closed.");
        }
    }

    record Scope(UUID runUuid, long runGeneration, String runtimePlanHash) {

        Scope {
            if (runUuid == null || runGeneration < 1
                    || runtimePlanHash == null || !HASH.matcher(runtimePlanHash).matches()) {
                throw new IllegalArgumentException("Procedure rowset scope is invalid.");
            }
        }

        private boolean matches(Handle handle) {
            return runUuid.equals(handle.runUuid())
                    && runGeneration == handle.runGeneration()
                    && runtimePlanHash.equals(handle.runtimePlanHash());
        }
    }

    record Handle(
            UUID uuid,
            UUID runUuid,
            long runGeneration,
            String runtimePlanHash,
            String producerTaskId,
            int producerTaskIndex,
            int rowCount,
            long byteCount) {

        Handle {
            if (uuid == null || runUuid == null || runGeneration < 1
                    || runtimePlanHash == null || !HASH.matcher(runtimePlanHash).matches()
                    || producerTaskId == null || !TASK_ID.matcher(producerTaskId).matches()
                    || producerTaskIndex < 1
                    || rowCount < 0 || rowCount > MAXIMUM_ROWS
                    || byteCount < 0 || byteCount > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Procedure rowset handle is invalid.");
            }
        }
    }

    record Column(String name, ValueType type) {

        Column {
            if (name == null || !ORACLE_IDENTIFIER.matcher(name).matches() || type == null) {
                throw new IllegalArgumentException("Procedure rowset column is invalid.");
            }
        }
    }

    record Cell(ValueType type, String value) {

        Cell {
            if (type == null) {
                throw new IllegalArgumentException("Procedure rowset cell type is required.");
            }
        }
    }

    enum ValueType {
        NUMBER,
        STRING,
        TIMESTAMP
    }

    record Rowset(List<Column> columns, List<List<Cell>> rows, long byteCount) {

        Rowset {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            if (rows.size() > MAXIMUM_ROWS || byteCount < 0 || byteCount > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Procedure rowset is outside its bounds.");
            }
        }
    }

    @FunctionalInterface
    interface RowsetConsumer<T> {

        T accept(Rowset rowset);
    }

    private static final class FrozenRowset implements AutoCloseable {

        private List<Column> columns;
        private List<List<Cell>> rows;
        private final long byteCount;
        private boolean closed;

        private FrozenRowset(
                List<Column> columns, List<List<Cell>> rows, long byteCount) {
            this.columns = columns;
            this.rows = rows;
            this.byteCount = byteCount;
        }

        private List<List<Cell>> rows() {
            ensureOpen();
            return rows;
        }

        private long byteCount() {
            return byteCount;
        }

        private Rowset view() {
            ensureOpen();
            return new Rowset(columns, rows, byteCount);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            columns = List.of();
            rows = List.of();
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Procedure rowset payload is closed.");
            }
        }
    }

    private record Pending(
            Handle handle,
            String consumerTaskId,
            int consumerTaskIndex,
            FrozenRowset rowset) implements AutoCloseable {

        @Override
        public void close() {
            rowset.close();
        }
    }
}

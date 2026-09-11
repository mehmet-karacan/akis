package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Cell;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Column;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Handle;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Rowset;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Scope;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.ValueType;

class ProcedureRunScopedRowsetStoreTest {

    private static final String PLAN_HASH = "a".repeat(64);

    @Test
    void freezesValuesAndConsumesOnceByTheDeclaredAdjacentTask() {
        UUID runUuid = UUID.randomUUID();
        List<Column> columns = new ArrayList<>(List.of(
                new Column("ID", ValueType.NUMBER),
                new Column("NAME", ValueType.STRING)));
        List<Cell> mutableRow = new ArrayList<>(List.of(
                new Cell(ValueType.NUMBER, "1"),
                new Cell(ValueType.STRING, "Akis")));
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(mutableRow);

        try (ProcedureRunScopedRowsetStore store = store(runUuid, 7, PLAN_HASH)) {
            Handle handle = store.store(
                    2, "READ_SOURCE", "INSERT_TARGET", columns, rows);
            columns.clear();
            mutableRow.clear();
            rows.clear();

            assertEquals(runUuid, handle.runUuid());
            assertEquals(7, handle.runGeneration());
            assertEquals(PLAN_HASH, handle.runtimePlanHash());
            assertEquals("READ_SOURCE", handle.producerTaskId());
            assertEquals(1, handle.rowCount());
            assertTrue(store.hasPending());

            long bytes = store.consume(handle, 3, "INSERT_TARGET", rowset -> {
                assertEquals(List.of("ID", "NAME"), rowset.columns().stream()
                        .map(Column::name).toList());
                assertEquals("Akis", rowset.rows().getFirst().get(1).value());
                assertThrows(UnsupportedOperationException.class,
                        () -> rowset.rows().add(List.of()));
                assertThrows(UnsupportedOperationException.class,
                        () -> rowset.rows().getFirst().add(
                                new Cell(ValueType.STRING, "changed")));
                return rowset.byteCount();
            });

            assertEquals(handle.byteCount(), bytes);
            assertFalse(store.hasPending());
            assertThrows(IllegalArgumentException.class,
                    () -> store.consume(handle, 3, "INSERT_TARGET", Rowset::byteCount));
        }
    }

    @Test
    void rejectsCrossRunGenerationAndPlanConfusionWithoutConsumingEitherPayload() {
        UUID firstRun = UUID.randomUUID();
        UUID secondRun = UUID.randomUUID();
        try (ProcedureRunScopedRowsetStore first = store(firstRun, 3, PLAN_HASH);
                ProcedureRunScopedRowsetStore second = store(
                        secondRun, 4, "b".repeat(64))) {
            Handle firstHandle = first.store(
                    1, "READ_A", "WRITE_A", columns(), rows("first"));
            Handle secondHandle = second.store(
                    1, "READ_B", "WRITE_B", columns(), rows("second"));

            assertThrows(IllegalArgumentException.class,
                    () -> second.consume(firstHandle, 2, "WRITE_B", Rowset::byteCount));
            assertThrows(IllegalArgumentException.class,
                    () -> first.consume(secondHandle, 2, "WRITE_A", Rowset::byteCount));
            assertTrue(first.hasPending());
            assertTrue(second.hasPending());
            assertEquals("first", first.consume(
                    firstHandle, 2, "WRITE_A",
                    rowset -> rowset.rows().getFirst().getFirst().value()));
            assertEquals("second", second.consume(
                    secondHandle, 2, "WRITE_B",
                    rowset -> rowset.rows().getFirst().getFirst().value()));
        }
    }

    @Test
    void rejectsWrongOrNonAdjacentConsumerWithoutBurningTheHandle() {
        try (ProcedureRunScopedRowsetStore store = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            Handle handle = store.store(
                    4, "READ_SOURCE", "INSERT_TARGET", columns(), rows("value"));

            assertThrows(IllegalArgumentException.class,
                    () -> store.consume(handle, 6, "INSERT_TARGET", Rowset::byteCount));
            assertThrows(IllegalArgumentException.class,
                    () -> store.consume(handle, 5, "OTHER_TARGET", Rowset::byteCount));
            assertTrue(store.hasPending());
            assertEquals("value", store.consume(
                    handle, 5, "INSERT_TARGET",
                    rowset -> rowset.rows().getFirst().getFirst().value()));
        }
    }

    @Test
    void enforcesTheStrictRowLimit() {
        List<Cell> row = List.of(new Cell(ValueType.STRING, "x"));
        List<List<Cell>> maximum = Collections.nCopies(
                ProcedureRunScopedRowsetStore.MAXIMUM_ROWS, row);
        List<List<Cell>> excessive = Collections.nCopies(
                ProcedureRunScopedRowsetStore.MAXIMUM_ROWS + 1, row);

        try (ProcedureRunScopedRowsetStore accepted = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            Handle handle = accepted.store(
                    1, "READ", "WRITE", columns(), maximum);
            assertEquals(ProcedureRunScopedRowsetStore.MAXIMUM_ROWS, handle.rowCount());
        }
        try (ProcedureRunScopedRowsetStore rejected = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            assertThrows(IllegalArgumentException.class,
                    () -> rejected.store(1, "READ", "WRITE", columns(), excessive));
            assertFalse(rejected.hasPending());
        }
    }

    @Test
    void enforcesTheColumnLimitAndCanonicalIdentifierContracts() {
        List<Column> maximum = new ArrayList<>();
        for (int index = 0;
                index < ProcedureRunScopedRowsetStore.MAXIMUM_COLUMNS;
                index++) {
            maximum.add(new Column("C" + index, ValueType.STRING));
        }
        List<Column> excessive = new ArrayList<>(maximum);
        excessive.add(new Column("OVERFLOW", ValueType.STRING));

        try (ProcedureRunScopedRowsetStore store = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            Handle handle = store.store(
                    1, "READ_SOURCE", "WRITE_TARGET", maximum, List.of());
            int consumedColumns = store.consume(handle, 2, "WRITE_TARGET",
                    rowset -> rowset.columns().size());
            assertEquals(ProcedureRunScopedRowsetStore.MAXIMUM_COLUMNS,
                    consumedColumns);

            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "READ_AGAIN", "WRITE_AGAIN", excessive, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "read_again", "WRITE_AGAIN", columns(), rows("x")));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "READ AGAIN", "WRITE_AGAIN", columns(), rows("x")));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "R".repeat(101), "WRITE_AGAIN", columns(), rows("x")));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "READ_AGAIN", "WRITE_AGAIN",
                            List.of(new Column("lowercase", ValueType.STRING)), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "READ_AGAIN", "WRITE_AGAIN",
                            List.of(new Column("C".repeat(129), ValueType.STRING)), List.of()));
            assertFalse(store.hasPending());
        }
    }

    @Test
    void enforcesTheStrictUtf8ByteLimitIncludingFraming() {
        int singleStringFramingBytes = 36;
        String maximumValue = "x".repeat(Math.toIntExact(
                ProcedureRunScopedRowsetStore.MAXIMUM_BYTES - singleStringFramingBytes));

        try (ProcedureRunScopedRowsetStore store = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            Handle maximum = store.store(
                    1, "READ", "WRITE", columns(), rows(maximumValue));
            assertEquals(ProcedureRunScopedRowsetStore.MAXIMUM_BYTES, maximum.byteCount());
            store.consume(maximum, 2, "WRITE", Rowset::byteCount);

            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "READ_2", "WRITE_2", columns(), rows(maximumValue + "x")));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            3, "READ_2", "WRITE_2", columns(),
                            rows("\u20ac".repeat(6_000_000))));
            assertFalse(store.hasPending());
        }
    }

    @Test
    void alwaysCleansUpAfterConsumerFailureAndAllowsTheNextAdjacentPair() {
        try (ProcedureRunScopedRowsetStore store = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            Handle failed = store.store(
                    1, "READ_ONE", "WRITE_ONE", columns(), rows("one"));
            assertThrows(IllegalStateException.class,
                    () -> store.consume(failed, 2, "WRITE_ONE", rowset -> {
                        throw new IllegalStateException("consumer failed");
                    }));
            assertFalse(store.hasPending());
            assertThrows(IllegalArgumentException.class,
                    () -> store.consume(failed, 2, "WRITE_ONE", Rowset::byteCount));

            Handle next = store.store(
                    3, "READ_TWO", "WRITE_TWO", columns(), rows("two"));
            assertEquals("two", store.consume(
                    next, 4, "WRITE_TWO",
                    rowset -> rowset.rows().getFirst().getFirst().value()));
        }
    }

    @Test
    void closeDiscardsPendingDataAndIsIdempotent() {
        ProcedureRunScopedRowsetStore store = store(UUID.randomUUID(), 1, PLAN_HASH);
        Handle handle = store.store(
                1, "READ", "WRITE", columns(), rows("sensitive-value"));

        store.close();
        store.close();

        assertFalse(store.hasPending());
        assertThrows(IllegalStateException.class,
                () -> store.consume(handle, 2, "WRITE", Rowset::byteCount));
        assertThrows(IllegalStateException.class,
                () -> store.store(3, "READ_2", "WRITE_2", columns(), rows("x")));
    }

    @Test
    void rejectsMalformedShapesTypesAndUnicodeWithoutRetainingData() {
        try (ProcedureRunScopedRowsetStore store = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(1, "READ", "WRITE", List.of(), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            1, "READ", "WRITE", columns(),
                            List.of(List.of(new Cell(ValueType.NUMBER, "1")))));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            1, "READ", "WRITE",
                            List.of(
                                    new Column("ID", ValueType.STRING),
                                    new Column("id", ValueType.STRING)),
                            List.of(List.of(
                                    new Cell(ValueType.STRING, "1"),
                                    new Cell(ValueType.STRING, "2")))));
            assertThrows(IllegalArgumentException.class,
                    () -> store.store(
                            1, "READ", "WRITE", columns(), rows("\ud800")));
            assertFalse(store.hasPending());
        }
    }

    @Test
    void preventsASecondPendingRowsetFromReplacingTheFirst() {
        try (ProcedureRunScopedRowsetStore store = store(
                UUID.randomUUID(), 1, PLAN_HASH)) {
            Handle first = store.store(
                    1, "READ_ONE", "WRITE_ONE", columns(), rows("one"));

            assertThrows(IllegalStateException.class,
                    () -> store.store(
                            3, "READ_TWO", "WRITE_TWO", columns(), rows("two")));
            assertEquals("one", store.consume(
                    first, 2, "WRITE_ONE",
                    rowset -> rowset.rows().getFirst().getFirst().value()));
        }
    }

    private ProcedureRunScopedRowsetStore store(
            UUID runUuid, long generation, String runtimePlanHash) {
        return new ProcedureRunScopedRowsetStore(
                new Scope(runUuid, generation, runtimePlanHash));
    }

    private List<Column> columns() {
        return List.of(new Column("VALUE", ValueType.STRING));
    }

    private List<List<Cell>> rows(String value) {
        return List.of(List.of(new Cell(ValueType.STRING, value)));
    }
}

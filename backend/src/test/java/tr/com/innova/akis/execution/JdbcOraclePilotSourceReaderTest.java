package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.execution.OraclePilotDataException.Failure;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;

class JdbcOraclePilotSourceReaderTest {

    private final JdbcOraclePilotSourceReader reader = new JdbcOraclePilotSourceReader();

    @Test
    void readsAllowlistedTypesWithoutRawGetObjectAndBuildsDeterministicPayload() {
        Timestamp changedAt = Timestamp.valueOf(
                LocalDateTime.of(2026, 9, 11, 10, 15, 30, 123_456_000));
        List<List<Object>> rows = List.of(
                List.of(new BigDecimal("1.00"), "ONE", changedAt),
                Arrays.asList(new BigDecimal("2"), null, changedAt));
        FakeSource source = new FakeSource(rows);

        OraclePilotBatch batch = reader.read(source.connection(), plan("TTBP", 1_000));
        OraclePilotBatch same = reader.read(
                new FakeSource(rows.reversed()).connection(), plan("TTBP", 1_000));

        assertEquals("SELECT \"ID\", \"AD\", \"CHANGED_AT\" FROM "
                + "\"TTBP\".\"HAKEDIS_TIPI\" FETCH FIRST 1001 ROWS ONLY", source.sql);
        assertEquals(1001, source.maximumRows);
        assertEquals(250, source.fetchSize);
        assertEquals(List.of("ID", "AD", "CHANGED_AT"), batch.columns().stream()
                .map(OraclePilotColumn::sourceColumn).toList());
        assertEquals(List.of(
                OraclePilotColumnType.NUMBER,
                OraclePilotColumnType.VARCHAR2,
                OraclePilotColumnType.TIMESTAMP), batch.columns().stream()
                .map(OraclePilotColumn::type).toList());
        assertEquals("1", batch.rows().getFirst().getFirst().canonicalValue());
        assertNull(batch.rows().get(1).get(1).canonicalValue());
        assertEquals(batch.payloadHash(), same.payloadHash());
        assertEquals(batch.byteCount(), same.byteCount());
        assertTrue(batch.byteCount() > 0);
        assertEquals(0, source.rawGetObjectCalls);
        assertEquals(0, source.commits);
        assertEquals(0, source.rollbacks);
    }

    @Test
    void failsClosedWhenSourceContainsOneMoreThanTheBound() {
        FakeSource source = new FakeSource(List.of(
                row(1, "ONE"), row(2, "TWO"), row(3, "THREE")));

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> reader.read(source.connection(), plan("TTBP", 2)));

        assertEquals(Failure.SOURCE_ROW_LIMIT_EXCEEDED, error.failure());
        assertEquals(0, source.commits);
        assertEquals(0, source.rollbacks);
    }

    @Test
    void rejectsLobTypeBeforeReadingAnyCell() {
        FakeSource source = new FakeSource(List.of(row(1, "ONE")));
        source.jdbcTypes.set(1, Types.CLOB);
        source.typeNames.set(1, "CLOB");

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> reader.read(source.connection(), plan("TTBP", 100)));

        assertEquals(Failure.UNSUPPORTED_SOURCE_TYPE, error.failure());
        assertEquals(0, source.cellReads);
    }

    @Test
    void rejectsOversizedCell() {
        FakeSource source = new FakeSource(List.of(row(
                1, "x".repeat(OraclePilotPayloadCodec.MAXIMUM_CELL_BYTES + 1))));

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> reader.read(source.connection(), plan("TTBP", 100)));

        assertEquals(Failure.SOURCE_CELL_LIMIT_EXCEEDED, error.failure());
    }

    @Test
    void rejectsOversizedCanonicalBatch() {
        String maximumCell = "x".repeat(OraclePilotPayloadCodec.MAXIMUM_CELL_BYTES);
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            rows.add(row(index, maximumCell));
        }
        FakeSource source = new FakeSource(rows);

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> reader.read(source.connection(), plan("TTBP", 100)));

        assertEquals(Failure.SOURCE_BATCH_LIMIT_EXCEEDED, error.failure());
    }

    @Test
    void rejectsNonCanonicalIdentifierBeforePreparingSql() {
        FakeSource source = new FakeSource(List.of());

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> reader.read(source.connection(), plan("TTBP;DROP_TABLE", 100)));

        assertEquals(Failure.INVALID_PLAN, error.failure());
        assertNull(source.sql);
    }

    @Test
    void jdbcFailureDoesNotRetainSensitiveDriverMessageOrCause() {
        FakeSource source = new FakeSource(List.of());
        source.failure = new SQLException(
                "jdbc:oracle:thin:user/secret@internal-host", "08006", 17002);

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> reader.read(source.connection(), plan("TTBP", 100)));

        assertEquals(Failure.SOURCE_READ_FAILED, error.failure());
        assertEquals("08006", error.sqlState());
        assertEquals(17002, error.vendorCode());
        assertNull(error.getCause());
        assertEquals("The Oracle pilot source read failed.", error.getMessage());
    }

    private static List<Object> row(int id, String name) {
        return List.of(
                BigDecimal.valueOf(id), name,
                Timestamp.valueOf("2026-09-11 10:15:30.123456"));
    }

    private static PilotRuntimePlan plan(String sourceOwner, int maximumRows) {
        return new PilotRuntimePlan(
                PilotRuntimePlan.CURRENT_VERSION, "a".repeat(64), "b".repeat(64),
                "c".repeat(64), UUID.randomUUID(), UUID.randomUUID(), maximumRows,
                binding(DatasetRole.SOURCE, sourceOwner, "HAKEDIS_TIPI"),
                binding(DatasetRole.TARGET, "INNOVA_ODI", "STG_HAKEDIS_TIPI"),
                List.of(new DirectColumnMapping("ID", "ID"),
                        new DirectColumnMapping("AD", "AD"),
                        new DirectColumnMapping("CHANGED_AT", "CHANGED_AT")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                new ObjectMapper().createObjectNode());
    }

    private static DatasetBinding binding(
            DatasetRole role, String owner, String objectName) {
        return new DatasetBinding(
                role.name(), role, DatabaseType.ORACLE, DataObjectType.TABLE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "d".repeat(64), "identity", owner, objectName);
    }

    private static final class FakeSource {
        private final List<List<Object>> rows;
        private final List<Integer> jdbcTypes = new ArrayList<>(
                List.of(Types.NUMERIC, Types.VARCHAR, Types.TIMESTAMP));
        private final List<String> typeNames = new ArrayList<>(
                List.of("NUMBER", "VARCHAR2", "TIMESTAMP"));
        private String sql;
        private int maximumRows;
        private int fetchSize;
        private int commits;
        private int rollbacks;
        private int rawGetObjectCalls;
        private int cellReads;
        private SQLException failure;

        private FakeSource(List<List<Object>> rows) {
            this.rows = rows;
        }

        private Connection connection() {
            return proxy(Connection.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "isClosed" -> false;
                case "prepareStatement" -> {
                    sql = (String) arguments[0];
                    yield statement();
                }
                case "commit" -> {
                    commits++;
                    yield null;
                }
                case "rollback" -> {
                    rollbacks++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "setMaxRows" -> {
                    maximumRows = (int) arguments[0];
                    yield null;
                }
                case "setFetchSize" -> {
                    fetchSize = (int) arguments[0];
                    yield null;
                }
                case "executeQuery" -> {
                    if (failure != null) {
                        throw failure;
                    }
                    yield resultSet();
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet() {
            int[] index = {-1};
            return proxy(ResultSet.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "next" -> ++index[0] < rows.size();
                case "getMetaData" -> metadata();
                case "getBigDecimal" -> {
                    cellReads++;
                    yield rows.get(index[0]).get((int) arguments[0] - 1);
                }
                case "getString" -> {
                    cellReads++;
                    yield rows.get(index[0]).get((int) arguments[0] - 1);
                }
                case "getTimestamp" -> {
                    cellReads++;
                    yield rows.get(index[0]).get((int) arguments[0] - 1);
                }
                case "getObject" -> {
                    rawGetObjectCalls++;
                    throw new AssertionError("Raw getObject is forbidden.");
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSetMetaData metadata() {
            return proxy(ResultSetMetaData.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getColumnCount" -> jdbcTypes.size();
                        case "getColumnType" -> jdbcTypes.get((int) arguments[0] - 1);
                        case "getColumnTypeName" -> typeNames.get((int) arguments[0] - 1);
                        case "getScale" -> (int) arguments[0] == 3 ? 6 : 0;
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}

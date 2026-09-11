package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.execution.OraclePilotDataException.Failure;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.VerifiedDatabaseIdentity;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

class JdbcOracleAtomicRefreshWriterTest {

    private static final String TARGET_HASH = new OracleTargetIdentityV1().canonicalize(
            new VerifiedDatabaseIdentity("AKISDB", "AKISPDB"),
            "INNOVA_ODI", "TABLE", "STG_HAKEDIS_TIPI").canonicalTargetHash();

    private final JdbcOracleAtomicRefreshWriter writer =
            new JdbcOracleAtomicRefreshWriter();
    private final OraclePilotPayloadCodec codec = new OraclePilotPayloadCodec();

    @Test
    void verifiesIdentityLocksThenRefreshesWithoutCompletingCallerTransaction() {
        PilotRuntimePlan plan = plan();
        OraclePilotBatch batch = batch(plan.runtimePlanHash(), List.of(
                cells("1", "ONE", "2026-09-11T10:15:30.123456"),
                cells("2", null, "2026-09-11T10:15:31")));
        FakeTarget target = new FakeTarget();

        OraclePilotWriteResult result = writer.write(
                target.connection(), plan, fence(TARGET_HASH, 1), batch);

        assertEquals(List.of(
                "LOCK", "IDENTITY_DATABASE", "IDENTITY_OBJECT", "DELETE", "INSERT_BATCH",
                "VERIFY"),
                target.events);
        assertEquals("LOCK TABLE \"INNOVA_ODI\".\"STG_HAKEDIS_TIPI\" "
                + "IN EXCLUSIVE MODE NOWAIT", target.lockSql);
        assertEquals("DELETE FROM \"INNOVA_ODI\".\"STG_HAKEDIS_TIPI\"", target.deleteSql);
        assertEquals("INSERT INTO \"INNOVA_ODI\".\"STG_HAKEDIS_TIPI\" "
                + "(\"ID\", \"AD\", \"CHANGED_AT\") VALUES (?, ?, ?)",
                target.insertSql);
        assertEquals(new BigDecimal("1"), target.rows.getFirst().getFirst());
        assertNull(target.rows.get(1).get(1));
        assertEquals(Timestamp.valueOf("2026-09-11 10:15:30.123456"),
                target.rows.getFirst().get(2));
        assertEquals(4, result.deletedRows());
        assertEquals(2, result.insertedRows());
        assertEquals(2, result.verifiedRows());
        assertEquals(batch.byteCount(), result.verifiedByteCount());
        assertEquals(batch.payloadHash(), result.verifiedPayloadHash());
        assertEquals(0, target.commits);
        assertEquals(0, target.rollbacks);
    }

    @Test
    void rejectsIdentityHashOrVersionAfterLockButBeforeDml() {
        PilotRuntimePlan plan = plan();
        OraclePilotBatch batch = batch(plan.runtimePlanHash(), List.of());

        for (TargetFenceToken token : List.of(
                fence("f".repeat(64), 1), fence(TARGET_HASH, 2))) {
            FakeTarget target = new FakeTarget();
            OraclePilotDataException error = assertThrows(
                    OraclePilotDataException.class,
                    () -> writer.write(target.connection(), plan, token, batch));

            assertEquals(Failure.TARGET_IDENTITY_MISMATCH, error.failure());
            assertEquals("LOCK", target.events.getFirst());
            assertFalse(target.events.contains("DELETE"));
            assertEquals(0, target.commits);
            assertEquals(0, target.rollbacks);
        }
    }

    @Test
    void lockFailurePreventsDeleteAndLeavesRollbackToCaller() {
        PilotRuntimePlan plan = plan();
        FakeTarget target = new FakeTarget();
        target.lockFailure = new SQLException(
                "resource busy at jdbc:oracle:thin:user/secret@host", "72000", 54);

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> writer.write(target.connection(), plan, fence(TARGET_HASH, 1),
                        batch(plan.runtimePlanHash(), List.of())));

        assertEquals(Failure.TARGET_LOCK_FAILED, error.failure());
        assertEquals(List.of("LOCK"), target.events);
        assertFalse(target.events.contains("DELETE"));
        assertEquals("The Oracle pilot target lock could not be acquired.", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, target.commits);
        assertEquals(0, target.rollbacks);
    }

    @Test
    void rejectsAutoCommitAndTamperedBatchBeforeIdentityOrDml() {
        PilotRuntimePlan plan = plan();
        OraclePilotBatch valid = batch(plan.runtimePlanHash(), List.of());
        OraclePilotBatch tampered = new OraclePilotBatch(
                valid.runtimePlanHash(), valid.columns(), valid.rows(),
                "f".repeat(64), valid.byteCount());
        FakeTarget target = new FakeTarget();

        assertEquals(Failure.INVALID_BATCH, assertThrows(
                OraclePilotDataException.class,
                () -> writer.write(target.connection(), plan,
                        fence(TARGET_HASH, 1), tampered)).failure());
        assertEquals(List.of(), target.events);

        FakeTarget autoCommit = new FakeTarget();
        autoCommit.autoCommit = true;
        assertEquals(Failure.INVALID_CONNECTION, assertThrows(
                OraclePilotDataException.class,
                () -> writer.write(autoCommit.connection(), plan,
                        fence(TARGET_HASH, 1), valid)).failure());
        assertEquals(List.of(), autoCommit.events);
    }

    @Test
    void usesBoundedChunksAndAcceptsJdbcUnknownSuccessCounts() {
        PilotRuntimePlan plan = plan();
        List<List<OraclePilotCell>> rows = new ArrayList<>();
        for (int index = 0; index < 251; index++) {
            rows.add(cells(Integer.toString(index), "ROW" + index,
                    "2026-09-11T10:15:30.123456"));
        }
        FakeTarget target = new FakeTarget();
        target.unknownBatchCounts = true;

        OraclePilotWriteResult result = writer.write(
                target.connection(), plan, fence(TARGET_HASH, 1),
                batch(plan.runtimePlanHash(), rows));

        assertEquals(2, target.batchExecutions);
        assertEquals(251, result.insertedRows());
        assertEquals(0, target.commits);
        assertEquals(0, target.rollbacks);
    }

    @Test
    void writeFailureIsSanitizedAndLeavesRollbackToCaller() {
        PilotRuntimePlan plan = plan();
        FakeTarget target = new FakeTarget();
        target.writeFailure = new SQLException(
                "jdbc:oracle:thin:user/secret@internal-host", "72000", 12899);

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> writer.write(target.connection(), plan, fence(TARGET_HASH, 1),
                        batch(plan.runtimePlanHash(), List.of(cells(
                                "1", "ONE", "2026-09-11T10:15:30")))));

        assertEquals(Failure.TARGET_WRITE_FAILED, error.failure());
        assertEquals("72000", error.sqlState());
        assertEquals(12899, error.vendorCode());
        assertNull(error.getCause());
        assertEquals("The Oracle pilot target write failed.", error.getMessage());
        assertEquals(0, target.commits);
        assertEquals(0, target.rollbacks);
    }

    @Test
    void rejectsTargetContentMismatchBeforeCallerCanRecordOrCommit() {
        PilotRuntimePlan plan = plan();
        FakeTarget target = new FakeTarget();
        target.corruptVerification = true;

        OraclePilotDataException error = assertThrows(
                OraclePilotDataException.class,
                () -> writer.write(target.connection(), plan, fence(TARGET_HASH, 1),
                        batch(plan.runtimePlanHash(), List.of(cells(
                                "1", "ONE", "2026-09-11T10:15:30")))));

        assertEquals(Failure.TARGET_VERIFICATION_FAILED, error.failure());
        assertEquals("VERIFY", target.events.getLast());
        assertEquals(0, target.commits);
        assertEquals(0, target.rollbacks);
    }

    private OraclePilotBatch batch(
            String runtimePlanHash, List<List<OraclePilotCell>> rows) {
        return codec.batch(runtimePlanHash, List.of(
                new OraclePilotColumn("ID", OraclePilotColumnType.NUMBER),
                new OraclePilotColumn("AD", OraclePilotColumnType.VARCHAR2),
                new OraclePilotColumn("CHANGED_AT", OraclePilotColumnType.TIMESTAMP)), rows);
    }

    private static List<OraclePilotCell> cells(
            String id, String name, String changedAt) {
        return List.of(
                new OraclePilotCell(OraclePilotColumnType.NUMBER, id),
                new OraclePilotCell(OraclePilotColumnType.VARCHAR2, name),
                new OraclePilotCell(OraclePilotColumnType.TIMESTAMP, changedAt));
    }

    private static TargetFenceToken fence(String hash, int version) {
        return new TargetFenceToken(
                UUID.randomUUID(), "worker-1", 1, UUID.randomUUID(), 1, hash, version);
    }

    private static PilotRuntimePlan plan() {
        return new PilotRuntimePlan(
                PilotRuntimePlan.CURRENT_VERSION, "a".repeat(64), "b".repeat(64),
                "c".repeat(64), UUID.randomUUID(), UUID.randomUUID(), 1_000,
                binding(DatasetRole.SOURCE, "TTBP", "HAKEDIS_TIPI"),
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

    private static final class FakeTarget {
        private final List<String> events = new ArrayList<>();
        private final List<List<Object>> rows = new ArrayList<>();
        private boolean autoCommit;
        private boolean readOnly;
        private int commits;
        private int rollbacks;
        private int batchExecutions;
        private boolean unknownBatchCounts;
        private boolean corruptVerification;
        private SQLException lockFailure;
        private SQLException writeFailure;
        private String lockSql;
        private String deleteSql;
        private String insertSql;

        private Connection connection() {
            return proxy(Connection.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "isClosed" -> false;
                case "getAutoCommit" -> autoCommit;
                case "isReadOnly" -> readOnly;
                case "prepareStatement" -> statement((String) arguments[0]);
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

        private PreparedStatement statement(String sql) {
            if (sql.contains("SYS_CONTEXT")) {
                return identityStatement(true);
            }
            if (sql.contains("ALL_OBJECTS")) {
                return identityStatement(false);
            }
            if (sql.startsWith("LOCK TABLE")) {
                lockSql = sql;
                return lockStatement();
            }
            if (sql.startsWith("DELETE")) {
                deleteSql = sql;
                return deleteStatement();
            }
            if (sql.startsWith("SELECT")) {
                return verificationStatement();
            }
            insertSql = sql;
            return insertStatement();
        }

        private PreparedStatement verificationStatement() {
            return proxy(PreparedStatement.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "executeQuery" -> {
                    events.add("VERIFY");
                    yield dataResultSet();
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement identityStatement(boolean database) {
            return proxy(PreparedStatement.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "executeQuery" -> {
                    events.add(database ? "IDENTITY_DATABASE" : "IDENTITY_OBJECT");
                    yield database
                            ? resultSet(List.of(Map.of(
                                    "DB_UNIQUE_NAME", "AKISDB", "CON_NAME", "AKISPDB")))
                            : resultSet(List.of(Map.of("OBJECT_COUNT", 1)));
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement lockStatement() {
            return proxy(PreparedStatement.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "execute" -> {
                    events.add("LOCK");
                    if (lockFailure != null) {
                        throw lockFailure;
                    }
                    yield false;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement deleteStatement() {
            return proxy(PreparedStatement.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "executeUpdate" -> {
                    events.add("DELETE");
                    yield 4;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement insertStatement() {
            List<Object> current = new ArrayList<>();
            List<List<Object>> pending = new ArrayList<>();
            return proxy(PreparedStatement.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "setBigDecimal", "setString", "setTimestamp" -> {
                    set(current, (int) arguments[0], arguments[1]);
                    yield null;
                }
                case "setNull" -> {
                    set(current, (int) arguments[0], null);
                    yield null;
                }
                case "addBatch" -> {
                    pending.add(new ArrayList<>(current));
                    current.clear();
                    yield null;
                }
                case "executeBatch" -> {
                    events.add("INSERT_BATCH");
                    batchExecutions++;
                    if (writeFailure != null) {
                        throw writeFailure;
                    }
                    rows.addAll(pending);
                    int[] counts = new int[pending.size()];
                    Arrays.fill(counts, unknownBatchCounts ? -2 : 1);
                    yield counts;
                }
                case "clearBatch" -> {
                    pending.clear();
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private static void set(List<Object> values, int jdbcIndex, Object value) {
            int index = jdbcIndex - 1;
            while (values.size() <= index) {
                values.add(null);
            }
            values.set(index, value);
        }

        private ResultSet resultSet(List<Map<String, Object>> resultRows) {
            int[] index = {-1};
            boolean[] wasNull = {false};
            return proxy(ResultSet.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "next" -> ++index[0] < resultRows.size();
                case "getString" -> {
                    Object value = resultRows.get(index[0]).get((String) arguments[0]);
                    wasNull[0] = value == null;
                    yield value;
                }
                case "getInt" -> {
                    Object value = resultRows.get(index[0]).get((String) arguments[0]);
                    wasNull[0] = value == null;
                    yield value == null ? 0 : ((Number) value).intValue();
                }
                case "wasNull" -> wasNull[0];
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet dataResultSet() {
            int[] index = {-1};
            return proxy(ResultSet.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "next" -> ++index[0] < rows.size();
                case "getMetaData" -> dataMetadata();
                case "getBigDecimal" -> rows.get(index[0]).get((int) arguments[0] - 1);
                case "getString" -> {
                    Object value = rows.get(index[0]).get((int) arguments[0] - 1);
                    if (corruptVerification && (int) arguments[0] == 2 && value != null) {
                        yield value + "_CORRUPTED";
                    }
                    yield value;
                }
                case "getTimestamp" -> rows.get(index[0]).get((int) arguments[0] - 1);
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSetMetaData dataMetadata() {
            return proxy(ResultSetMetaData.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getColumnCount" -> 3;
                        case "getColumnType" -> switch ((int) arguments[0]) {
                            case 1 -> Types.NUMERIC;
                            case 2 -> Types.VARCHAR;
                            default -> Types.TIMESTAMP;
                        };
                        case "getColumnTypeName" -> switch ((int) arguments[0]) {
                            case 1 -> "NUMBER";
                            case 2 -> "VARCHAR2";
                            default -> "TIMESTAMP";
                        };
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

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JdbcOracleTargetIdentityReaderTest {

    private final JdbcOracleTargetIdentityReader reader =
            new JdbcOracleTargetIdentityReader();

    @Test
    void verifiesDatabaseContainerAndExactlyOneTableUsingSelectsOnly() {
        FakeOracle oracle = new FakeOracle(
                List.of(Map.of(
                        "DB_UNIQUE_NAME", "AKISDB",
                        "CON_NAME", "AKISPDB")),
                1);

        var identity = reader.read(
                oracle.connection(), "INNOVA_ODI", "TABLE", "STG_TABLE");

        assertEquals("AKISDB", identity.databaseUniqueName());
        assertEquals("AKISPDB", identity.containerName());
        assertEquals(List.of("INNOVA_ODI", "STG_TABLE"), oracle.boundValues);
        assertEquals(2, oracle.sql.size());
        assertTrue(oracle.sql.stream().allMatch(sql -> sql.stripLeading().startsWith("SELECT")));
    }

    @Test
    void failsClosedForAmbiguousDatabaseIdentity() {
        FakeOracle oracle = new FakeOracle(
                List.of(
                        Map.of("DB_UNIQUE_NAME", "ONE", "CON_NAME", "PDB"),
                        Map.of("DB_UNIQUE_NAME", "TWO", "CON_NAME", "PDB")),
                1);

        assertThrows(
                OracleTargetIdentityException.class,
                () -> reader.read(oracle.connection(), "OWNER", "TABLE", "TARGET"));
    }

    @Test
    void failsClosedForMissingOrAmbiguousTargetObject() {
        for (int count : List.of(0, 2)) {
            FakeOracle oracle = new FakeOracle(
                    List.of(Map.of("DB_UNIQUE_NAME", "AKISDB", "CON_NAME", "PDB")),
                    count);
            assertThrows(
                    OracleTargetIdentityException.class,
                    () -> reader.read(oracle.connection(), "OWNER", "TABLE", "TARGET"));
        }
    }

    @Test
    void rejectsInvalidIdentifiersBeforeExecutingSql() {
        FakeOracle oracle = new FakeOracle(
                List.of(Map.of("DB_UNIQUE_NAME", "AKISDB", "CON_NAME", "PDB")),
                1);

        assertThrows(
                OracleTargetIdentityException.class,
                () -> reader.read(oracle.connection(), "owner", "TABLE", "TARGET"));
        assertTrue(oracle.sql.isEmpty());
    }

    private static final class FakeOracle {
        private final List<Map<String, Object>> identityRows;
        private final int objectCount;
        private final List<String> sql = new ArrayList<>();
        private final List<String> boundValues = new ArrayList<>();

        private FakeOracle(List<Map<String, Object>> identityRows, int objectCount) {
            this.identityRows = identityRows;
            this.objectCount = objectCount;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement")) {
                    String statementSql = (String) arguments[0];
                    sql.add(statementSql);
                    List<Map<String, Object>> rows = statementSql.contains("ALL_OBJECTS")
                            ? List.of(Map.of("OBJECT_COUNT", objectCount))
                            : identityRows;
                    return statement(rows);
                }
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement(List<Map<String, Object>> rows) {
            return proxy(PreparedStatement.class, (proxy, method, arguments) -> {
                if (method.getName().equals("setString")) {
                    boundValues.add((String) arguments[1]);
                    return null;
                }
                if (method.getName().equals("executeQuery")) {
                    return resultSet(rows);
                }
                return defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet(List<Map<String, Object>> rows) {
            Map<String, Object> state = new HashMap<>();
            state.put("index", -1);
            state.put("wasNull", false);
            return proxy(ResultSet.class, (proxy, method, arguments) -> {
                int index = (int) state.get("index");
                if (method.getName().equals("next")) {
                    int next = index + 1;
                    state.put("index", next);
                    return next < rows.size();
                }
                if (method.getName().equals("getString")) {
                    Object value = rows.get(index).get((String) arguments[0]);
                    state.put("wasNull", value == null);
                    return value;
                }
                if (method.getName().equals("getInt")) {
                    Object value = rows.get(index).get((String) arguments[0]);
                    state.put("wasNull", value == null);
                    return value == null ? 0 : ((Number) value).intValue();
                }
                if (method.getName().equals("wasNull")) {
                    return state.get("wasNull");
                }
                return defaultValue(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
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
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}

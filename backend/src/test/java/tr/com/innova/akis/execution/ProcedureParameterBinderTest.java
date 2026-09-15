package tr.com.innova.akis.execution;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcedureParameterBinderTest {
    @Test
    void preservesOracleDateTimeAndUsesOneRefreshForRepeatedBindsAndTasks() throws Exception {
        Timestamp yesterday = Timestamp.valueOf("2026-09-14 14:32:10");
        var calls = new AtomicInteger();
        var timeout = new AtomicInteger();
        var bound = new HashMap<Integer, Object>();
        var parameter = new ProcedureRuntimePlan.ParameterValue(ProcedureRuntimePlan.ParameterType.DATE,
                null, ProcedureRuntimePlan.ParameterSource.REFRESH_QUERY, "SELECT SYSDATE - 1 FROM DUAL", UUID.randomUUID());
        ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (p, m, a) -> m.getName().equals("getColumnCount") ? 1 : null);
        PreparedStatement refresh = proxy(PreparedStatement.class, (p, m, a) -> {
            if (m.getName().equals("setQueryTimeout")) timeout.set((int) a[0]);
            if (m.getName().equals("executeQuery")) {
                calls.incrementAndGet();
                var row = new AtomicInteger();
                return proxy(ResultSet.class, (rp, rm, ra) -> switch (rm.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> row.getAndIncrement() == 0;
                    case "getObject" -> yesterday;
                    default -> null;
                });
            }
            return null;
        });
        Connection connection = proxy(Connection.class, (p, m, a) -> m.getName().equals("prepareStatement") ? refresh : null);
        PreparedStatement statement = proxy(PreparedStatement.class, (p, m, a) -> {
            if (m.getName().equals("getConnection")) return connection;
            if (m.getName().equals("getQueryTimeout")) return 30;
            if (m.getName().equals("setDate")) fail("Oracle DATE refresh must retain its time component.");
            if (m.getName().equals("setTimestamp")) bound.put((int) a[0], a[1]);
            return null;
        });
        var context = new ProcedureVariableContext();
        var connectionId = UUID.randomUUID();
        var task = task(parameter);
        ProcedureParameterBinder.bind(statement, task, context, connectionId);
        ProcedureParameterBinder.bind(statement, task, context, connectionId);
        assertEquals(1, calls.get());
        assertEquals(30, timeout.get());
        assertEquals(yesterday, bound.get(1));
        assertEquals(yesterday, bound.get(2));
        assertEquals("SELECT ?, ? FROM DUAL", ProcedureParameterBinder.positionalSql(task));
    }

    private static ProcedureRuntimePlan.Task task(ProcedureRuntimePlan.ParameterValue parameter) {
        return new ProcedureRuntimePlan.Task("T", "T", ProcedureRuntimePlan.TaskType.SQL,
                ProcedureRuntimePlan.ConnectionRole.SOURCE, ProcedureRuntimePlan.RiskClass.READ_ONLY,
                "SELECT :d, :D FROM DUAL", "a".repeat(64), false,
                ProcedureRuntimePlan.ErrorPolicy.STOP, 30, null, null, List.of("D", "D"),
                Map.of("D", parameter), null, null, null, null, null);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}

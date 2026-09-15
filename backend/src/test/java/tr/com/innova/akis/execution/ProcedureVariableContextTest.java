package tr.com.innova.akis.execution;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcedureVariableContextTest {
    @Test
    void publishedVariableUsesItsResolverOnceAcrossDifferentConsumerConnections() throws Exception {
        var calls = new AtomicInteger();
        var context = new ProcedureVariableContext(parameter -> calls.incrementAndGet());
        var parameter = new ProcedureRuntimePlan.ParameterValue(ProcedureRuntimePlan.ParameterType.DATE,
            null, ProcedureRuntimePlan.ParameterSource.REFRESH_QUERY, "SELECT SYSDATE - 1 FROM DUAL", UUID.randomUUID(), UUID.randomUUID(), "ALL");
        assertEquals(1, context.resolve(UUID.randomUUID(), parameter, () -> { throw new AssertionError("Consumer connection must not be used"); }));
        assertEquals(1, context.resolve(UUID.randomUUID(), parameter, () -> { throw new AssertionError("Consumer connection must not be used"); }));
        assertEquals(1, calls.get());
    }
    @Test
    void evaluatesOncePerRunAndPinnedConnection() throws Exception {
        var parameter = new ProcedureRuntimePlan.ParameterValue(ProcedureRuntimePlan.ParameterType.DATE,
                null, ProcedureRuntimePlan.ParameterSource.REFRESH_QUERY, "SELECT SYSDATE - 1 FROM DUAL", UUID.randomUUID());
        var context = new ProcedureVariableContext();
        var connection = UUID.randomUUID();
        var calls = new AtomicInteger();
        assertEquals(1, context.resolve(connection, parameter, calls::incrementAndGet));
        assertEquals(1, context.resolve(connection, parameter, calls::incrementAndGet));
        assertEquals(2, context.resolve(UUID.randomUUID(), parameter, calls::incrementAndGet));
        assertEquals(3, new ProcedureVariableContext().resolve(connection, parameter, calls::incrementAndGet));
        context.clear();
        assertEquals(4, context.resolve(connection, parameter, calls::incrementAndGet));
    }

    @Test
    void doesNotCacheFailedOrNullRefresh() throws Exception {
        var parameter = new ProcedureRuntimePlan.ParameterValue(ProcedureRuntimePlan.ParameterType.STRING, "x");
        var context = new ProcedureVariableContext();
        var connection = UUID.randomUUID();
        assertThrows(SQLException.class, () -> context.resolve(connection, parameter, () -> null));
        assertThrows(SQLException.class, () -> context.resolve(connection, parameter, () -> { throw new SQLException(); }));
        assertEquals("ok", context.resolve(connection, parameter, () -> "ok"));
    }
}

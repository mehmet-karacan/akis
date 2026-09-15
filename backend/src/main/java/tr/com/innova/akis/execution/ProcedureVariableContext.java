package tr.com.innova.akis.execution;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One execution session only. Values never leak into another run or connection. */
final class ProcedureVariableContext {
    private record Key(UUID connection, ProcedureRuntimePlan.ParameterValue parameter) { }
    private final Map<Key, Object> values = new HashMap<>();
    @FunctionalInterface
    interface Resolver { Object read(ProcedureRuntimePlan.ParameterValue parameter) throws SQLException; }
    private final Resolver resolver;
    ProcedureVariableContext() { this(null); }
    ProcedureVariableContext(Resolver resolver) { this.resolver = resolver; }

    @FunctionalInterface
    interface Query { Object read() throws SQLException; }

    Object resolve(UUID connection, ProcedureRuntimePlan.ParameterValue parameter, Query query) throws SQLException {
        Key key = new Key(resolver == null ? connection : null, parameter);
        if (values.containsKey(key)) return values.get(key);
        Object value = resolver == null ? query.read() : resolver.read(parameter);
        if (value == null) throw new SQLException("Variable refresh returned NULL; nullable variables are not supported.");
        values.put(key, value);
        return value;
    }

    void clear() { values.clear(); }
}

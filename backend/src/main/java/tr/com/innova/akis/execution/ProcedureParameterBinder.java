package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import tr.com.innova.akis.metadata.NamedBindParser;
import java.util.UUID;

final class ProcedureParameterBinder {
    private ProcedureParameterBinder() { }

    static String positionalSql(ProcedureRuntimePlan.Task task) {
        var compiled = NamedBindParser.compile(task.command());
        if (!compiled.names().equals(task.namedBinds())) {
            throw new IllegalArgumentException("SQL bind order does not match the pinned plan.");
        }
        return compiled.sql();
    }

    static void bind(PreparedStatement statement, ProcedureRuntimePlan.Task task) throws SQLException {
        bind(statement, task, new ProcedureVariableContext(), null);
    }

    static void bind(PreparedStatement statement, ProcedureRuntimePlan.Task task,
            ProcedureVariableContext context, UUID connectionVersion) throws SQLException {
        for (int index = 0; index < task.namedBinds().size(); index++) {
            String name = task.namedBinds().get(index);
            var parameter = task.parameters().get(name);
            if (parameter == null) throw new IllegalArgumentException("Procedure parameter is missing.");
            int position = index + 1;
            Object runtimeValue = parameter.value();
            if (parameter.source() == ProcedureRuntimePlan.ParameterSource.REFRESH_QUERY) {
                runtimeValue = context.resolve(connectionVersion, parameter, () -> evaluate(statement, parameter));
            }
            if (runtimeValue == null) throw new SQLException("A required Procedure variable has no value.");
            switch (parameter.type()) {
                case STRING -> statement.setString(position, String.valueOf(runtimeValue));
                case INTEGER -> statement.setLong(position, Long.parseLong(String.valueOf(runtimeValue)));
                case DECIMAL -> statement.setBigDecimal(position, new BigDecimal(String.valueOf(runtimeValue)));
                case BOOLEAN -> statement.setBoolean(position, Boolean.parseBoolean(String.valueOf(runtimeValue)));
                case DATE -> {
                    // Oracle DATE includes time; do not truncate a refresh result to midnight.
                    if (runtimeValue instanceof Timestamp timestamp) statement.setTimestamp(position, timestamp);
                    else if (runtimeValue instanceof java.util.Date date) statement.setDate(position, new Date(date.getTime()));
                    else statement.setDate(position, Date.valueOf(LocalDate.parse(String.valueOf(runtimeValue))));
                }
                case TIMESTAMP -> {
                    if (runtimeValue instanceof Timestamp timestamp) statement.setTimestamp(position, timestamp);
                    else statement.setTimestamp(position, Timestamp.valueOf(LocalDateTime.parse(String.valueOf(runtimeValue))));
                }
            }
        }
    }

    private static Object evaluate(PreparedStatement target, ProcedureRuntimePlan.ParameterValue parameter)
            throws SQLException {
        try (PreparedStatement refresh = target.getConnection().prepareStatement(parameter.refreshQuery())) {
            refresh.setQueryTimeout(Math.max(1, target.getQueryTimeout()));
            refresh.setMaxRows(2);
            try (ResultSet result = refresh.executeQuery()) {
                if (result.getMetaData().getColumnCount() != 1) {
                    throw new SQLException("Variable refresh must return exactly one column.");
                }
                if (!result.next()) throw new SQLException("Variable refresh query returned no value.");
                Object value = result.getObject(1);
                if (result.next()) throw new SQLException("Variable refresh query returned multiple values.");
                return value;
            }
        }
    }

}

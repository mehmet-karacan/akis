package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.metadata.NamedBindParser;

/** Explicitly invoked live Oracle adapter test. Executes only fixed SELECTs against DUAL. */
class OracleVariableReadOnlyIT {
    @Test
    void refreshesYesterdayAndUsesTheSameValueInTwoReadOnlyQueries() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", required("AKIS_ORACLE_SOURCE_USERNAME"));
        properties.setProperty("password", required("AKIS_ORACLE_SOURCE_PASSWORD"));
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "10000");
        String url = required("AKIS_ORACLE_SOURCE_URL");
        assertTrue(url.startsWith("jdbc:oracle:"), "This integration test requires Oracle.");
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            assertEquals("Oracle", connection.getMetaData().getDatabaseProductName());
            connection.setAutoCommit(false);
            try {
                var context = new ProcedureVariableContext();
                var connectionVersion = UUID.randomUUID();
                var parameter = new ProcedureRuntimePlan.ParameterValue(ProcedureRuntimePlan.ParameterType.DATE,
                    null, ProcedureRuntimePlan.ParameterSource.REFRESH_QUERY, "SELECT SYSDATE - 1 FROM DUAL", UUID.randomUUID());
                var task = task("SELECT CASE WHEN :D BETWEEN SYSDATE - 1 - 1/1440 AND SYSDATE - 1 + 1/1440 THEN 1 ELSE 0 END FROM DUAL", parameter);
                try (PreparedStatement statement = connection.prepareStatement(ProcedureParameterBinder.positionalSql(task))) {
                    statement.setQueryTimeout(10);
                    ProcedureParameterBinder.bind(statement, task, context, connectionVersion);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1), "Refresh must retain the Oracle date and time, not midnight.");
                        assertFalse(rows.next());
                    }
                }
                var echo = task("SELECT CAST(:D AS TIMESTAMP), CAST(:D AS TIMESTAMP) FROM DUAL", parameter);
                Timestamp first = null;
                for (int i = 0; i < 2; i++) {
                    try (PreparedStatement statement = connection.prepareStatement(ProcedureParameterBinder.positionalSql(echo))) {
                        statement.setQueryTimeout(10);
                        ProcedureParameterBinder.bind(statement, echo, context, connectionVersion);
                        try (ResultSet rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            Timestamp value = rows.getTimestamp(1);
                            assertNotNull(value);
                            assertEquals(value, rows.getTimestamp(2));
                            if (first == null) first = value;
                            else assertEquals(first, value, "The execution context must reuse the resolved value.");
                        }
                    }
                }
            } finally { connection.rollback(); }
        }
    }

    private static ProcedureRuntimePlan.Task task(String sql, ProcedureRuntimePlan.ParameterValue parameter) {
        return new ProcedureRuntimePlan.Task("READ_ONLY_VARIABLE_TEST", "Read only variable test",
            ProcedureRuntimePlan.TaskType.SQL, ProcedureRuntimePlan.ConnectionRole.SOURCE,
            ProcedureRuntimePlan.RiskClass.READ_ONLY, sql, "a".repeat(64), false,
            ProcedureRuntimePlan.ErrorPolicy.STOP, 10, null, null, NamedBindParser.parse(sql),
            Map.of("D", parameter), null, null, null, null, null);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured for the live test.");
        return value;
    }
}

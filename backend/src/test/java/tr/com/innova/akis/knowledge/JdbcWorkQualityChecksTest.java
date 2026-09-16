package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcWorkQualityChecksTest {
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet result = mock(ResultSet.class);
    private final Runnable checkpoint = mock(Runnable.class);
    private final JdbcStagingTransfer.Table table = new JdbcStagingTransfer.Table("WORK", "AKIS_C_TEST");
    private final JdbcWorkQualityChecks checks = new JdbcWorkQualityChecks();
    private final JdbcWorkQualityChecks.Contract contract = new JdbcWorkQualityChecks.Contract(
            List.of("ID", "NAME"), List.of(List.of("ID", "NAME")));

    private void prepare(boolean violation) throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(violation);
    }
    @Test void requiredColumnsPassWithBoundedResultAndCheckpoints() throws SQLException {
        prepare(false);
        checks.verify(connection, table, contract, JdbcWorkQualityChecks.Rule.NOT_NULL, 30, checkpoint);
        verify(connection).prepareStatement(contains("\"ID\" IS NULL OR \"NAME\" IS NULL"));
        verify(statement).setMaxRows(1);
        verify(statement).setQueryTimeout(30);
        verify(checkpoint, times(2)).run();
        verify(connection, never()).commit();
    }
    @Test void violationFailsWithoutReturningBusinessData() throws SQLException {
        prepare(true);
        var error = assertThrows(JdbcWorkQualityChecks.CheckFailure.class,
                () -> checks.verify(connection, table, contract, JdbcWorkQualityChecks.Rule.NOT_NULL, 30, checkpoint));
        assertEquals(JdbcWorkQualityChecks.Rule.NOT_NULL, error.rule());
        verify(result, never()).getString(anyInt());
        verify(connection, never()).commit();
    }
    @Test void compositeUniqueCheckExcludesOnlyAllNullKeys() throws SQLException {
        prepare(false);
        checks.verify(connection, table, contract, JdbcWorkQualityChecks.Rule.UNIQUE, 30, checkpoint);
        verify(connection).prepareStatement(contains("WHERE \"ID\" IS NOT NULL OR \"NAME\" IS NOT NULL GROUP BY \"ID\",\"NAME\" HAVING COUNT(*)>1"));
    }
    @Test void missingKeysCannotProduceFalseSuccess() {
        assertThrows(IllegalArgumentException.class, () -> checks.verify(connection, table,
                new JdbcWorkQualityChecks.Contract(List.of(), List.of()), JdbcWorkQualityChecks.Rule.UNIQUE, 30, checkpoint));
        verifyNoInteractions(connection);
    }
    @Test void leaseLossPreventsQuery() {
        doThrow(new IllegalStateException("lease lost")).when(checkpoint).run();
        assertThrows(IllegalStateException.class, () -> checks.verify(connection, table, contract,
                JdbcWorkQualityChecks.Rule.NOT_NULL, 30, checkpoint));
        verifyNoInteractions(connection);
    }
    @Test void sqlFailureIsSanitizedAndNotRetried() throws SQLException {
        prepare(false);
        when(statement.executeQuery()).thenThrow(new SQLException("secret connection data"));
        var error = assertThrows(IllegalStateException.class, () -> checks.verify(connection, table, contract,
                JdbcWorkQualityChecks.Rule.UNIQUE, 30, checkpoint));
        assertFalse(error.toString().contains("secret"));
        verify(statement, times(1)).executeQuery();
    }
    @Test void injectionAndDuplicateColumnsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcWorkQualityChecks.Contract(List.of("ID OR 1=1"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new JdbcWorkQualityChecks.Contract(List.of("ID", "ID"), List.of()));
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class JdbcOracleSourceSnapshotTest {

    @Test
    void capturesTheScnFromTheSourceDatabase() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(
                "SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER FROM DUAL"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBigDecimal(1)).thenReturn(new BigDecimal("9007199254740993"));

        SourceSnapshotPort.SourceSnapshot snapshot =
                new JdbcOracleSourceSnapshot(connection, "a".repeat(64)).capture();

        assertEquals(new BigInteger("9007199254740993"), snapshot.scn());
        assertEquals("a".repeat(64), snapshot.databaseFingerprint());
        verify(statement).executeQuery();
    }

    @Test
    void sourceFailureNeverFallsBackToALatestRead() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(
                "SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER FROM DUAL"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("snapshot unavailable"));

        assertThrows(SourceSnapshotUnavailableException.class,
                () -> new JdbcOracleSourceSnapshot(connection, "a".repeat(64)).capture());
    }
}

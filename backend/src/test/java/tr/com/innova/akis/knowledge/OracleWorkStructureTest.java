package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleWorkStructureTest {
    private final Connection connection=mock(Connection.class);
    private final ResultSet row=mock(ResultSet.class);
    private final JdbcStagingTransfer.Table table=new JdbcStagingTransfer.Table("WORK","AKIS_C_TEST");
    private void setup() throws SQLException {
        var statement=mock(PreparedStatement.class);when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(row);when(row.next()).thenReturn(true,false);
        when(row.getString("COLUMN_NAME")).thenReturn("ID");when(row.getString("DATA_TYPE")).thenReturn("NUMBER");
        when(row.getString("NULLABLE")).thenReturn("Y");when(row.getString("HIDDEN_COLUMN")).thenReturn("NO");
        when(row.getString("VIRTUAL_COLUMN")).thenReturn("NO");when(row.getString("IDENTITY_COLUMN")).thenReturn("NO");
        when(row.getInt("COLUMN_ID")).thenReturn(1);when(row.wasNull()).thenReturn(true);
    }
    @Test void actualColumnsMatchDeclaredShape() throws Exception {
        setup();
        assertEquals(OracleWorkStructure.expected(List.of(new WorkTableManagerPort.Column("ID","NUMBER"))),
                OracleWorkStructure.read(connection,table,30));
    }
    @Test void alteredColumnChangesHash() throws Exception {
        setup();when(row.getString("COLUMN_NAME")).thenReturn("OTHER_ID");
        assertNotEquals(OracleWorkStructure.expected(List.of(new WorkTableManagerPort.Column("ID","NUMBER"))),
                OracleWorkStructure.read(connection,table,30));
    }
    @Test void hiddenColumnFailsClosed() throws Exception {
        setup();when(row.getString("HIDDEN_COLUMN")).thenReturn("YES");
        assertThrows(SQLException.class,()->OracleWorkStructure.read(connection,table,30));
    }
    @Test void defaultsAreRejected() throws Exception {
        setup();when(row.getObject("DEFAULT_LENGTH")).thenReturn(6);
        assertThrows(SQLException.class,()->OracleWorkStructure.read(connection,table,30));
    }
}

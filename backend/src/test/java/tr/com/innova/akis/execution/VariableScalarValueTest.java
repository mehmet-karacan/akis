package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.*;
import org.junit.jupiter.api.Test;

class VariableScalarValueTest {
    private ResultSet row() throws SQLException {
        ResultSet rows = mock(ResultSet.class); ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(1);
        when(rows.next()).thenReturn(true, false); return rows;
    }
    @Test void preservesDateTimeAndTypedScalarResults() throws Exception {
        var rows = row(); var timestamp = Timestamp.valueOf("2026-09-16 14:30:12");
        when(rows.getTimestamp(1)).thenReturn(timestamp); assertEquals(timestamp, VariableScalarValue.read(rows,"DATE"));
        assertEquals(timestamp, VariableScalarValue.parse(timestamp.toString(),"DATE"));
        rows = row(); when(rows.getBigDecimal(1)).thenReturn(new BigDecimal("42")); assertEquals(42L, VariableScalarValue.read(rows,"INTEGER"));
        rows = row(); when(rows.getString(1)).thenReturn("1"); assertEquals(true, VariableScalarValue.read(rows,"BOOLEAN"));
        rows = row(); when(rows.getString(1)).thenReturn("hello"); assertEquals("hello", VariableScalarValue.read(rows,"STRING"));
    }
    @Test void rejectsNullNoRowsMultipleRowsOrColumns() throws Exception {
        var rows = row(); assertThrows(SQLException.class, () -> VariableScalarValue.read(rows,"STRING"));
        var empty = row(); when(empty.next()).thenReturn(false); assertThrows(SQLException.class, () -> VariableScalarValue.read(empty,"STRING"));
        var multiple = row(); when(multiple.next()).thenReturn(true); when(multiple.getString(1)).thenReturn("x");
        assertThrows(SQLException.class, () -> VariableScalarValue.read(multiple,"STRING"));
        var columns = row(); when(columns.getMetaData().getColumnCount()).thenReturn(2);
        assertThrows(SQLException.class, () -> VariableScalarValue.read(columns,"STRING"));
    }
    @Test void rejectsFractionalIntegerInvalidBooleanAndOversizedText() throws Exception {
        var integer = row(); when(integer.getBigDecimal(1)).thenReturn(new BigDecimal("1.2"));
        assertThrows(SQLException.class, () -> VariableScalarValue.read(integer,"INTEGER"));
        var bool = row(); when(bool.getString(1)).thenReturn("yes");
        assertThrows(SQLException.class, () -> VariableScalarValue.read(bool,"BOOLEAN"));
        var large = row(); when(large.getString(1)).thenReturn("x".repeat(10001));
        assertThrows(SQLException.class, () -> VariableScalarValue.read(large,"STRING"));
    }
}

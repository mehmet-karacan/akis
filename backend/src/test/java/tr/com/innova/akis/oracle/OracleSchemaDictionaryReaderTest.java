package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OracleSchemaDictionaryReaderTest {

    @Test
    void capturesAllFiftyDictionaryColumnsIncludingRawByteLengthAndTimezonePrecision() throws Exception {
        var fixture = OracleSchemaSnapshotCodecV1Test.skyDitColumns();
        Connection connection = mock(Connection.class);
        PreparedStatement objects = mock(PreparedStatement.class);
        PreparedStatement columns = mock(PreparedStatement.class);
        PreparedStatement defaults = mock(PreparedStatement.class);
        PreparedStatement constraints = mock(PreparedStatement.class);
        ResultSet objectRows = mock(ResultSet.class);
        ResultSet columnRows = mock(ResultSet.class);
        ResultSet constraintRows = mock(ResultSet.class);
        AtomicReference<String> columnSql = new AtomicReference<>();
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM all_objects")) return objects;
            if (sql.contains("SELECT data_default")) return defaults;
            if (sql.contains("FROM all_constraints")) return constraints;
            if (sql.contains("FROM all_tab_columns")) {
                columnSql.set(sql);
                return columns;
            }
            throw new AssertionError("Unexpected dictionary query: " + sql);
        });
        when(objects.executeQuery()).thenReturn(objectRows);
        when(objectRows.next()).thenReturn(true, false);
        when(objectRows.getString("OBJECT_NAME")).thenReturn("SKY_DIT");
        when(objectRows.getString("OBJECT_TYPE")).thenReturn("TABLE");
        when(constraints.executeQuery()).thenReturn(constraintRows);
        when(constraintRows.next()).thenReturn(false);
        when(defaults.executeQuery()).thenAnswer(invocation -> {
            ResultSet rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            return rows;
        });
        when(columns.executeQuery()).thenReturn(columnRows);
        AtomicInteger index = new AtomicInteger(-1);
        AtomicBoolean wasNull = new AtomicBoolean();
        when(columnRows.next()).thenAnswer(invocation -> index.incrementAndGet() < fixture.size());
        when(columnRows.wasNull()).thenAnswer(invocation -> wasNull.get());
        when(columnRows.getString(anyString())).thenAnswer(invocation -> {
            var raw = fixture.get(index.get());
            return switch ((String) invocation.getArgument(0)) {
                case "COLUMN_NAME" -> raw.name();
                // Oracle ALL_TAB_COLUMNS embeds the timestamp precision in DATA_TYPE.
                case "DATA_TYPE" -> raw.dataType().replace("TIMESTAMP", "TIMESTAMP(6)");
                case "NULLABLE" -> raw.nullable() ? "Y" : "N";
                default -> throw new AssertionError("Unexpected string field");
            };
        });
        when(columnRows.getInt(anyString())).thenAnswer(invocation -> {
            var raw = fixture.get(index.get());
            Integer value = switch ((String) invocation.getArgument(0)) {
                case "COLUMN_ID" -> raw.ordinal();
                case "DATA_PRECISION" -> raw.precision();
                case "NUMERIC_SCALE" -> raw.scale();
                case "TIME_PRECISION" -> raw.timePrecision();
                default -> throw new AssertionError("Unexpected numeric field");
            };
            wasNull.set(value == null);
            return value == null ? 0 : value;
        });
        when(columnRows.getLong("CHAR_LENGTH")).thenAnswer(invocation -> {
            Long value = fixture.get(index.get()).charLength();
            wasNull.set(value == null);
            return value == null ? 0L : value;
        });

        var snapshot = new OracleSchemaDictionaryReader(new ObjectMapper())
                .read(connection, "TTBP", "SKY_DIT");
        assertEquals(50, snapshot.columns().size());
        assertEquals(-2, snapshot.columns().get(7).scale());
        assertEquals("TIMESTAMP(6) WITH TIME ZONE", snapshot.columns().get(18).producerType());
        assertEquals(6, snapshot.columns().get(18).timePrecision());
        assertEquals("NCHAR(8)", snapshot.columns().get(26).producerType());
        assertEquals("RAW(32)", snapshot.columns().get(35).producerType());
        assertEquals(32L, snapshot.columns().get(35).length());
        String sql = columnSql.get().replaceAll("\\s+", " ");
        assertTrue(sql.contains("data_type IN ('VARCHAR2', 'CHAR', 'NCHAR', 'NVARCHAR2') THEN char_length"));
        assertTrue(sql.contains("WHEN data_type = 'RAW' THEN data_length"));
        assertTrue(sql.contains("WHEN data_type LIKE 'TIMESTAMP%' THEN data_scale"));
        verify(columns).setString(1, "TTBP");
        verify(columns).setString(2, "SKY_DIT");
    }
}

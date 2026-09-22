package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Re-reads actual work columns, including hidden/virtual columns, before ownership-sensitive operations. */
public final class OracleWorkStructure {
    private static final Pattern NUMBER = Pattern.compile("NUMBER(?:\\((\\d+)(?:,(-?\\d+))?\\))?");
    private static final Pattern TEXT = Pattern.compile("(N?VARCHAR2)\\((\\d+)(?: CHAR)?\\)");
    private static final Pattern TIME = Pattern.compile("TIMESTAMP\\((\\d)\\)");
    private record Shape(String name, String type, Integer precision, Integer scale, Long characters) { }
    private OracleWorkStructure() { }

    public static String expected(List<WorkTableManagerPort.Column> columns) {
        if (columns.isEmpty() || columns.size() > 256) throw new IllegalArgumentException("Çalışma kolonları eksik.");
        return hash(columns.stream().map(OracleWorkStructure::shape).toList());
    }
    public static String read(Connection connection, JdbcStagingTransfer.Table table, int timeout) throws SQLException {
        List<Shape> shapes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COLUMN_NAME, DATA_TYPE, DATA_PRECISION, DATA_SCALE, CHAR_LENGTH, CHAR_USED,
                       NULLABLE, DEFAULT_LENGTH, HIDDEN_COLUMN, VIRTUAL_COLUMN, IDENTITY_COLUMN, COLUMN_ID
                FROM ALL_TAB_COLS WHERE OWNER=? AND TABLE_NAME=? ORDER BY INTERNAL_COLUMN_ID
                """)) {
            statement.setString(1, table.owner()); statement.setString(2, table.name()); statement.setQueryTimeout(timeout);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (shapes.size() >= 256 || !"Y".equals(row.getString("NULLABLE"))
                            || row.getObject("DEFAULT_LENGTH") != null
                            || !"NO".equals(row.getString("HIDDEN_COLUMN"))
                            || !"NO".equals(row.getString("VIRTUAL_COLUMN"))
                            || !"NO".equals(row.getString("IDENTITY_COLUMN"))
                            || row.getInt("COLUMN_ID") != shapes.size() + 1)
                        throw new SQLException("Unexpected work column characteristics");
                    String type = row.getString("DATA_TYPE");
                    Integer precision = integer(row, "DATA_PRECISION"), scale = integer(row, "DATA_SCALE");
                    Long characters = null;
                    if ("VARCHAR2".equals(type) || "NVARCHAR2".equals(type)) {
                        if (!"C".equals(row.getString("CHAR_USED"))) throw new SQLException("Unexpected character semantics");
                        characters = row.getLong("CHAR_LENGTH");
                        if (characters < 1) throw new SQLException("Invalid character length");
                    } else if (type != null && TIME.matcher(type).matches()) {
                        type = "TIMESTAMP";
                    } else if (!"NUMBER".equals(type) && !"DATE".equals(type)) {
                        throw new SQLException("Unsupported work column type");
                    }
                    shapes.add(new Shape(StagedMappingDefinition.identifier(row.getString("COLUMN_NAME")), type, precision, scale, characters));
                }
            }
        }
        if (shapes.isEmpty()) throw new SQLException("Work columns missing");
        return hash(shapes);
    }
    private static Shape shape(WorkTableManagerPort.Column column) {
        Matcher number = NUMBER.matcher(column.oracleType()), text = TEXT.matcher(column.oracleType()), time = TIME.matcher(column.oracleType());
        if (number.matches()) return new Shape(column.name(), "NUMBER", number.group(1) == null ? null : Integer.valueOf(number.group(1)),
                number.group(1) == null ? null : number.group(2) == null ? 0 : Integer.valueOf(number.group(2)), null);
        if (text.matches()) return new Shape(column.name(), text.group(1), null, null, Long.valueOf(text.group(2)));
        if (time.matches()) return new Shape(column.name(), "TIMESTAMP", null, Integer.valueOf(time.group(1)), null);
        if ("DATE".equals(column.oracleType())) return new Shape(column.name(), "DATE", null, null, null);
        throw new IllegalArgumentException("Çalışma kolonu tipi desteklenmiyor.");
    }
    private static Integer integer(ResultSet row, String name) throws SQLException {
        int value = row.getInt(name); return row.wasNull() ? null : value;
    }
    private static String hash(List<Shape> shapes) { return KmCanonical.hash("AKIS_WORK_SHAPE/1|" + shapes); }
}

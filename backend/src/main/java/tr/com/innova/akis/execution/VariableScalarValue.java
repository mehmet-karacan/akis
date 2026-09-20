package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

final class VariableScalarValue {
    private VariableScalarValue() { }
    static Object read(ResultSet rows, String type) throws SQLException {
        if (rows.getMetaData().getColumnCount() != 1 || !rows.next()) throw new SQLException("Expected one column and one row");
        Object value;
        try {
            value = switch (type) {
                case "DATE", "TIMESTAMP" -> rows.getTimestamp(1);
                case "INTEGER" -> { BigDecimal number = rows.getBigDecimal(1); yield number == null ? null : number.longValueExact(); }
                case "DECIMAL" -> rows.getBigDecimal(1);
                case "BOOLEAN" -> { String text = rows.getString(1); yield text == null ? null : booleanValue(text); }
                case "STRING" -> rows.getString(1);
                default -> throw new IllegalArgumentException("Unsupported variable type");
            };
            if (value == null || value.toString().length() > 10000 || rows.next()) throw new SQLException("Expected one non-null bounded value");
            return value;
        } catch (IllegalArgumentException | ArithmeticException error) { throw new SQLException("Variable type mismatch"); }
    }
    static Object parse(String value, String type) {
        return switch (type) {
            case "DATE", "TIMESTAMP" -> Timestamp.valueOf(value);
            case "INTEGER" -> Long.valueOf(value);
            case "DECIMAL" -> new BigDecimal(value);
            case "BOOLEAN" -> booleanValue(value);
            case "STRING" -> value;
            default -> throw new IllegalArgumentException("Unsupported variable type");
        };
    }
    private static Boolean booleanValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0")) return false;
        throw new IllegalArgumentException("Expected boolean");
    }
}

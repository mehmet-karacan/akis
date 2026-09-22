package tr.com.innova.akis.knowledge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure hash of a PostgreSQL work table: what the plan declares ({@link #expected}) versus what pg_catalog holds
 * ({@link #read}). Work columns are plain nullable columns without defaults, generated expressions or identity; anything
 * else means the table was touched outside AKIS and must not be adopted.
 */
public final class PostgresWorkStructure {
    private static final Pattern NUMERIC = Pattern.compile("NUMERIC(?:\\((\\d+),(\\d+)\\))?");
    private static final Pattern TEXT = Pattern.compile("VARCHAR\\((\\d+)\\)");
    private static final Pattern TIME = Pattern.compile("TIMESTAMP\\((\\d)\\)");
    private record Shape(String name, String type, Integer precision, Integer scale, Long characters) { }

    private PostgresWorkStructure() { }

    /** Accepted work DDL types: NUMERIC[(p,s)], SMALLINT, INTEGER, BIGINT, VARCHAR(n), TEXT, TIMESTAMP(p), DATE. */
    public static boolean supported(String ddlType) {
        try { shape(new WorkTableManagerPort.Column("c", ddlType)); return true; }
        catch (IllegalArgumentException unsupported) { return false; }
    }

    public static String expected(List<WorkTableManagerPort.Column> columns) {
        if (columns.isEmpty() || columns.size() > 256) throw new IllegalArgumentException("Çalışma kolonları eksik.");
        return hash(columns.stream().map(PostgresWorkStructure::shape).toList());
    }

    public static String read(Connection connection, JdbcStagingTransfer.Table table, int timeout) throws SQLException {
        List<Shape> shapes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS format_type, a.attnotnull, a.atthasdef,
                       a.attgenerated::text AS generated, a.attidentity::text AS identity, a.attnum
                  FROM pg_catalog.pg_attribute a
                  JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY a.attnum
                """)) {
            statement.setString(1, table.owner()); statement.setString(2, table.name()); statement.setQueryTimeout(timeout);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (shapes.size() >= 256 || row.getBoolean("attnotnull") || row.getBoolean("atthasdef")
                            || !row.getString("generated").isBlank() || !row.getString("identity").isBlank()
                            || row.getInt("attnum") != shapes.size() + 1)
                        throw new SQLException("Unexpected work column characteristics");
                    shapes.add(shape(new WorkTableManagerPort.Column(row.getString("attname"), ddlOf(row.getString("format_type")))));
                }
            }
        }
        catch (IllegalArgumentException unsupported) {
            throw new SQLException("Unsupported work column type");
        }
        if (shapes.isEmpty()) throw new SQLException("Work columns missing");
        return hash(shapes);
    }

    /** format_type text back to the declared DDL spelling. */
    static String ddlOf(String formatType) {
        String type = formatType == null ? "" : formatType.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Matcher m;
        if ((m = Pattern.compile("numeric\\((\\d+),(\\d+)\\)").matcher(type)).matches()) return "NUMERIC(" + m.group(1) + "," + m.group(2) + ")";
        if ((m = Pattern.compile("character varying\\((\\d+)\\)").matcher(type)).matches()) return "VARCHAR(" + m.group(1) + ")";
        if ((m = Pattern.compile("timestamp(?:\\((\\d)\\))? without time zone").matcher(type)).matches()) return "TIMESTAMP(" + (m.group(1) == null ? "6" : m.group(1)) + ")";
        return switch (type) {
            case "numeric" -> "NUMERIC";
            case "smallint" -> "SMALLINT";
            case "integer" -> "INTEGER";
            case "bigint" -> "BIGINT";
            case "text" -> "TEXT";
            case "date" -> "DATE";
            default -> type.toUpperCase(Locale.ROOT);
        };
    }

    private static String hash(List<Shape> shapes) { return KmCanonical.hash("AKIS_PG_WORK_SHAPE/1|" + shapes); }

    private static Shape shape(WorkTableManagerPort.Column column) {
        String ddl = column.ddlType() == null ? "" : column.ddlType().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher numeric = NUMERIC.matcher(ddl), text = TEXT.matcher(ddl), time = TIME.matcher(ddl);
        if (numeric.matches()) return new Shape(column.name(), "NUMERIC", numeric.group(1) == null ? null : Integer.valueOf(numeric.group(1)),
                numeric.group(1) == null ? null : Integer.valueOf(numeric.group(2)), null);
        if (text.matches()) return new Shape(column.name(), "VARCHAR", null, null, Long.valueOf(text.group(1)));
        if (time.matches()) return new Shape(column.name(), "TIMESTAMP", null, Integer.valueOf(time.group(1)), null);
        return switch (ddl) {
            case "SMALLINT", "INTEGER", "BIGINT", "TEXT", "DATE" -> new Shape(column.name(), ddl, null, null, null);
            default -> throw new IllegalArgumentException("Çalışma kolonu tipi desteklenmiyor.");
        };
    }
}

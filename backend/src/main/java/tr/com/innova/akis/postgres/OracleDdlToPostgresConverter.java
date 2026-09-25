package tr.com.innova.akis.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the portable table/constraint subset of Oracle DBMS_METADATA DDL to PostgreSQL DDL. */
public final class OracleDdlToPostgresConverter {
    private static final Pattern TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+))?\\s*\\((.*?)\\)\\s*(?:SEGMENT|PCTFREE|TABLESPACE|;)");
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*\\\"([A-Za-z0-9_$#]+)\\\"\\s+([A-Z0-9]+)(?:\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIMARY_KEY = Pattern.compile(
            "(?is)PRIMARY\\s+KEY\\s*\\(([^)]*)\\)");

    private OracleDdlToPostgresConverter() { }

    public record Result(String schema, String table, String ddl, List<String> ignoredClauses) { }

    public static Result convert(String sourceDdl, String targetSchema, String targetTable) {
        String schema = identifier(targetSchema, "Hedef şema");
        String table = identifier(targetTable, "Hedef tablo");
        if (sourceDdl == null || sourceDdl.isBlank()) throw invalid("Oracle DDL boş olamaz.");
        Matcher tableMatcher = TABLE.matcher(sourceDdl);
        if (!tableMatcher.find()) throw invalid("Oracle CREATE TABLE bölümü çözümlenemedi.");

        List<String> columns = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (String part : splitTopLevel(tableMatcher.group(1))) {
            String item = part.strip();
            if (item.isBlank()) continue;
            if (item.toUpperCase(Locale.ROOT).startsWith("SUPPLEMENTAL LOG GROUP")) {
                ignored.add("SUPPLEMENTAL LOG GROUP");
                continue;
            }
            Matcher column = COLUMN.matcher(item);
            if (!column.matches()) throw invalid("Desteklenmeyen Oracle tablo bölümü: " + item);
            String suffix = column.group(5).toUpperCase(Locale.ROOT);
            String nullability = suffix.contains("NOT NULL") ? " NOT NULL" : "";
            columns.add("    " + sqlIdentifier(column.group(1)) + " " + mapType(column.group(2), column.group(3), column.group(4)) + nullability);
        }
        Matcher pk = PRIMARY_KEY.matcher(sourceDdl);
        if (pk.find()) columns.add("    PRIMARY KEY (" + quoteColumns(pk.group(1)) + ")");
        if (columns.isEmpty()) throw invalid("Dönüştürülebilir kolon bulunamadı.");

        String ddl = "CREATE SCHEMA IF NOT EXISTS " + sqlIdentifier(schema) + ";\n\n"
                + "CREATE TABLE " + sqlIdentifier(schema) + "." + sqlIdentifier(table) + " (\n"
                + String.join(",\n", columns) + "\n);\n";
        if (sourceDdl.toUpperCase(Locale.ROOT).contains("CREATE UNIQUE INDEX")) ignored.add("Oracle fiziksel index ayarları");
        if (sourceDdl.toUpperCase(Locale.ROOT).contains("TABLESPACE")) ignored.add("Oracle TABLESPACE/STORAGE ayarları");
        return new Result(schema, table, ddl, List.copyOf(ignored));
    }

    private static String mapType(String type, String precision, String scale) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "VARCHAR2", "VARCHAR" -> "varchar(" + precision + ")";
            case "NUMBER" -> precision == null ? "numeric" : "numeric(" + precision + "," + (scale == null ? "0" : scale) + ")";
            case "DATE" -> "timestamp(0)";
            case "TIMESTAMP" -> "timestamp(" + (precision == null ? "6" : precision) + ")";
            case "CLOB" -> "text";
            case "BLOB" -> "bytea";
            default -> throw invalid("Desteklenmeyen Oracle veri tipi: " + type);
        };
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0, depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { parts.add(text.substring(start, i)); start = i + 1; }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static String quoteColumns(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .map(column -> column.replaceAll("^\\\"|\\\"$", ""))
                .map(OracleDdlToPostgresConverter::sqlIdentifier)
                .reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    private static String identifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_$]{0,62}")) throw invalid(field + " geçersiz.");
        return value;
    }

    private static String sqlIdentifier(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

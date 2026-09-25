package tr.com.innova.akis.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.metadata.ApiException;

/**
 * Generates (and, under {@code DDL_AUTO_CREATE}, runs) the {@code CREATE SCHEMA}/{@code CREATE TABLE} text that
 * provisions a PostgreSQL target table from a pinned Oracle snapshot. Faz A scope: one ordinary table, its enabled
 * primary key if every key column is provisionable, no foreign keys or check constraints — a freshly created table
 * has no inbound references anyway, and {@code JdbcPostgresSchemaPreflight} forbids them on a publish target.
 */
public final class PostgresSchemaProvisioner {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");

    private PostgresSchemaProvisioner() { }

    public record Plan(String schema, String table, String ddl, List<String> skippedColumns) { }

    public static Plan plan(SnapshotRow source, String targetSchema, String targetTable) {
        String schema = identifier(targetSchema, "Hedef şema adı");
        String table = identifier(targetTable, "Hedef tablo adı");
        if (source.columns().isEmpty()) throw validation("Kaynak şema görüntüsünde kolon yok.");
        List<ColumnRow> provisionable = source.columns().stream()
                .sorted((a, b) -> Integer.compare(a.ordinal(), b.ordinal())).toList();
        List<String> skipped = provisionable.stream().filter(c -> !OracleToPostgresTypeMapper.supported(c)).map(ColumnRow::reference).toList();
        List<ColumnRow> columns = provisionable.stream().filter(OracleToPostgresTypeMapper::supported).toList();
        if (columns.isEmpty()) throw validation("Sağlanabilir hiçbir kolon yok; tüm kolonlar desteklenmeyen tipte.");
        Set<String> provisionedNames = columns.stream().map(ColumnRow::reference).collect(java.util.stream.Collectors.toSet());

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE SCHEMA IF NOT EXISTS ").append(quote(schema)).append(";\n");
        ddl.append("CREATE TABLE ").append(quote(schema)).append('.').append(quote(table)).append(" (\n");
        List<String> lines = columns.stream()
                .map(c -> "    " + quote(c.reference()) + " " + OracleToPostgresTypeMapper.ddlType(c) + (c.nullable() ? "" : " NOT NULL"))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        source.constraints().stream()
                .filter(ConstraintRow::enabled)
                .filter(c -> "PK".equals(c.type()))
                .filter(c -> provisionedNames.containsAll(c.columnReferences()))
                .findFirst()
                .ifPresent(pk -> lines.add("    PRIMARY KEY (" + String.join(", ", pk.columnReferences().stream().map(PostgresSchemaProvisioner::quote).toList()) + ")"));
        ddl.append(String.join(",\n", lines));
        ddl.append("\n);\n");
        return new Plan(schema, table, ddl.toString(), skipped);
    }

    /** Runs the plan's DDL on an already-open, autocommit PostgreSQL connection; the caller owns the connection's lifecycle. */
    public static void execute(Connection connection, Plan plan) {
        try (Statement statement = connection.createStatement()) {
            for (String block : plan.ddl().split(";\\s*\\n")) {
                String sql = block.strip();
                if (!sql.isEmpty()) statement.execute(sql);
            }
        }
        catch (SQLException failure) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TARGET_PROVISIONING_FAILED",
                    "Hedef tablo oluşturulamadı: " + safeMessage(failure));
        }
    }

    /** SQLSTATE only; the driver message can echo back the SQL text or connection details. */
    private static String safeMessage(SQLException failure) {
        return failure.getSQLState() != null ? "SQLSTATE " + failure.getSQLState() : failure.getClass().getSimpleName();
    }

    private static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) throw validation(field + " geçersiz.");
        return value;
    }

    /** PostgreSQL convention: unquoted identifiers are folded to lower case; do not freeze Oracle uppercase names. */
    private static String quote(String name) { return name.toLowerCase(java.util.Locale.ROOT); }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TARGET_PROVISIONING_REJECTED", message);
    }
}

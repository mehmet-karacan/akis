package tr.com.innova.akis.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecException;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.SnapshotDefinition;
import tr.com.innova.akis.postgres.PostgresSchemaSnapshotCodecV1.RawColumn;
import tr.com.innova.akis.postgres.PostgresSchemaSnapshotCodecV1.RawConstraint;
import tr.com.innova.akis.postgres.PostgresSchemaSnapshotCodecV1.RawConstraintColumn;

/**
 * Reads one ordinary table from pg_catalog (never information_schema: it hides type modifiers and partial details) and
 * hands the rows to {@link PostgresSchemaSnapshotCodecV1}. Only visible, non-dropped attributes are read; partitioned
 * tables (relkind p) are accepted as tables, foreign tables and views are not.
 */
final class PostgresSchemaDictionaryReader {
    private static final String TABLE_SQL = """
            SELECT c.relkind
              FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')
            """;
    private static final String COLUMN_SQL = """
            SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS format_type, a.attnum, a.attnotnull,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS default_expression
              FROM pg_catalog.pg_attribute a
              JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
              LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
             ORDER BY a.attnum
            """;
    private static final String CONSTRAINT_SQL = """
            SELECT con.conname, con.contype, con.convalidated, con.condeferrable, con.confdeltype,
                   rn.nspname AS referenced_schema, rc.relname AS referenced_table,
                   CASE WHEN con.contype = 'c' THEN pg_catalog.pg_get_constraintdef(con.oid) END AS check_expression,
                   k.position, a.attname AS column_name, ra.attname AS referenced_column
              FROM pg_catalog.pg_constraint con
              JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
              LEFT JOIN pg_catalog.pg_class rc ON rc.oid = con.confrelid
              LEFT JOIN pg_catalog.pg_namespace rn ON rn.oid = rc.relnamespace
              LEFT JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, position) ON TRUE
              LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
              LEFT JOIN LATERAL (SELECT con.confkey[k.position] AS attnum) rk ON con.contype = 'f'
              LEFT JOIN pg_catalog.pg_attribute ra ON ra.attrelid = con.confrelid AND ra.attnum = rk.attnum
             WHERE n.nspname = ? AND c.relname = ? AND con.contype IN ('p', 'u', 'f', 'c')
             ORDER BY con.conname, k.position
            """;

    private final PostgresSchemaSnapshotCodecV1 codec;

    PostgresSchemaDictionaryReader(ObjectMapper objectMapper) {
        this.codec = new PostgresSchemaSnapshotCodecV1(objectMapper);
    }

    SnapshotDefinition read(Connection connection, String schema, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new OracleSchemaSnapshotCodecException("PostgreSQL table was not found or is not an ordinary table.");
                if (rows.next()) throw new OracleSchemaSnapshotCodecException("PostgreSQL table name is ambiguous.");
            }
        }
        List<RawColumn> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                int ordinal = 0;
                while (rows.next()) {
                    columns.add(new RawColumn(rows.getString("attname"), rows.getString("format_type"), ++ordinal,
                            !rows.getBoolean("attnotnull"), rows.getString("default_expression")));
                }
            }
        }
        Map<String, ConstraintBuilder> constraints = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(CONSTRAINT_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ConstraintBuilder builder = constraints.computeIfAbsent(rows.getString("conname"), name -> {
                        try {
                            return new ConstraintBuilder(name, rows.getString("contype"), rows.getBoolean("convalidated"),
                                    rows.getBoolean("condeferrable"), rows.getString("referenced_schema"), rows.getString("referenced_table"),
                                    deleteRule(rows.getString("confdeltype")), rows.getString("check_expression"));
                        } catch (SQLException failure) { throw new IllegalStateException("PostgreSQL dictionary row is unreadable."); }
                    });
                    String column = rows.getString("column_name");
                    if (column != null) builder.columns.add(new RawConstraintColumn(column, rows.getInt("position"), rows.getString("referenced_column")));
                }
            }
        }
        return codec.decode(schema, tableName, columns, constraints.values().stream().map(ConstraintBuilder::build).toList());
    }

    private static String deleteRule(String code) {
        if (code == null) return null;
        return switch (code) {
            case "a" -> "NO_ACTION";
            case "r" -> "RESTRICT";
            case "c" -> "CASCADE";
            case "n" -> "SET_NULL";
            case "d" -> "SET_DEFAULT";
            default -> null;
        };
    }

    private static final class ConstraintBuilder {
        private final String name, type, referencedSchema, referencedTable, deleteRule, checkExpression;
        private final boolean validated, deferrable;
        private final List<RawConstraintColumn> columns = new ArrayList<>();

        private ConstraintBuilder(String name, String type, boolean validated, boolean deferrable, String referencedSchema,
                String referencedTable, String deleteRule, String checkExpression) {
            this.name = name; this.type = type; this.validated = validated; this.deferrable = deferrable;
            this.referencedSchema = referencedSchema; this.referencedTable = referencedTable; this.deleteRule = deleteRule;
            this.checkExpression = checkExpression;
        }

        private RawConstraint build() {
            return new RawConstraint(name, type, validated, deferrable, referencedSchema, referencedTable, deleteRule, checkExpression, columns);
        }
    }
}

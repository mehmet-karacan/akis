package tr.com.innova.akis.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.RawColumn;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.RawConstraint;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.RawConstraintColumn;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.SnapshotDefinition;

/** Reads the exact Oracle 19c dictionary fields that participate in snapshot fingerprints. */
final class OracleSchemaDictionaryReader {

    private static final String TABLE_SQL = """
            SELECT table_name
              FROM all_tables
             WHERE owner = ?
               AND table_name = ?
            """;

    private static final String COLUMN_SQL = """
            SELECT column_name,
                   data_type,
                   data_precision,
                   CASE WHEN data_type = 'NUMBER' THEN data_scale END AS numeric_scale,
                   CASE WHEN data_type = 'VARCHAR2' THEN char_length END AS char_length,
                   CASE
                       WHEN data_type LIKE 'TIMESTAMP%'
                        AND data_type NOT LIKE '%TIME ZONE%'
                       THEN data_scale
                   END AS time_precision,
                   nullable,
                   column_id
             FROM all_tab_columns
             WHERE owner = ?
               AND table_name = ?
             ORDER BY column_id
            """;

    private static final String COLUMN_DEFAULT_SQL = """
            SELECT data_default
              FROM all_tab_columns
             WHERE owner = ?
               AND table_name = ?
               AND column_name = ?
            """;

    private static final String CONSTRAINT_SQL = """
            SELECT c.constraint_name,
                   c.constraint_type,
                   c.status,
                   c.r_owner,
                   rc.table_name AS referenced_table,
                   c.delete_rule,
                   c.deferrable,
                   c.search_condition_vc,
                   cc.column_name,
                   cc.position,
                   rcc.column_name AS referenced_column
              FROM all_constraints c
              LEFT JOIN all_cons_columns cc
                ON cc.owner = c.owner
               AND cc.constraint_name = c.constraint_name
               AND cc.table_name = c.table_name
              LEFT JOIN all_constraints rc
                ON rc.owner = c.r_owner
               AND rc.constraint_name = c.r_constraint_name
              LEFT JOIN all_cons_columns rcc
                ON rcc.owner = rc.owner
               AND rcc.constraint_name = rc.constraint_name
               AND rcc.table_name = rc.table_name
               AND rcc.position = cc.position
             WHERE c.owner = ?
               AND c.table_name = ?
               AND (c.constraint_type IN ('P', 'U', 'R')
                    OR (c.constraint_type = 'C' AND c.generated = 'USER NAME'))
             ORDER BY c.constraint_name, cc.position
            """;

    private final OracleSchemaSnapshotCodecV1 codec;

    OracleSchemaDictionaryReader(ObjectMapper objectMapper) {
        this.codec = new OracleSchemaSnapshotCodecV1(objectMapper);
    }

    SnapshotDefinition read(Connection connection, String owner, String tableName)
            throws SQLException {
        try {
            requireSingleTable(connection, owner, tableName);
        }
        catch (SQLException exception) {
            throw tagged("AKIS_TABLE", exception);
        }
        List<RawColumn> columns;
        try {
            columns = readColumns(connection, owner, tableName);
        }
        catch (SQLException exception) {
            throw tagged("AKIS_COLUMNS", exception);
        }
        List<RawConstraint> constraints;
        try {
            constraints = readConstraints(connection, owner, tableName);
        }
        catch (SQLException exception) {
            throw tagged("AKIS_CONSTRAINTS", exception);
        }
        return codec.decode(
                owner,
                tableName,
                columns,
                constraints);
    }

    private SQLException tagged(String stage, SQLException exception) {
        return new SQLException(
                "Oracle dictionary stage failed.", stage,
                exception.getErrorCode(), exception);
    }

    private void requireSingleTable(Connection connection, String owner, String tableName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_SQL)) {
            statement.setString(1, owner);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString("TABLE_NAME") == null || rows.next()) {
                    throw new OracleSchemaSnapshotCodecException(
                            "Oracle table metadata must resolve to exactly one table.");
                }
            }
        }
    }

    private List<RawColumn> readColumns(
            Connection connection, String owner, String tableName) throws SQLException {
        List<ColumnWithoutDefault> discovered = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_SQL)) {
            statement.setString(1, owner);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                int expectedOrdinal = 1;
                while (rows.next()) {
                    int ordinal = rows.getInt("COLUMN_ID");
                    if (ordinal != expectedOrdinal++) {
                        throw new OracleSchemaSnapshotCodecException(
                                "Oracle visible column ordinals must be contiguous.");
                    }
                    discovered.add(new ColumnWithoutDefault(
                            rows.getString("COLUMN_NAME"),
                            codecType(rows.getString("DATA_TYPE")),
                            nullableInteger(rows, "DATA_PRECISION"),
                            nullableInteger(rows, "NUMERIC_SCALE"),
                            nullableLong(rows, "CHAR_LENGTH"),
                            nullableInteger(rows, "TIME_PRECISION"),
                            ordinal,
                            "Y".equals(rows.getString("NULLABLE"))));
                }
            }
        }
        List<RawColumn> columns = new ArrayList<>();
        for (ColumnWithoutDefault column : discovered) {
            columns.add(new RawColumn(
                    column.name(), column.dataType(), column.precision(), column.scale(),
                    column.charLength(), column.timePrecision(), column.ordinal(),
                    column.nullable(),
                    readDefault(connection, owner, tableName, column.name())));
        }
        return List.copyOf(columns);
    }

    private String readDefault(
            Connection connection, String owner, String tableName, String columnName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_DEFAULT_SQL)) {
            statement.setString(1, owner);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Oracle column default row is missing.");
                }
                String value = trimToNull(rows.getString("DATA_DEFAULT"));
                if (rows.next()) {
                    throw new SQLException("Oracle column default row is ambiguous.");
                }
                return value;
            }
        }
    }

    private List<RawConstraint> readConstraints(
            Connection connection, String owner, String tableName) throws SQLException {
        Map<String, ConstraintBuilder> constraints = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(CONSTRAINT_SQL)) {
            statement.setString(1, owner);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("CONSTRAINT_NAME");
                    String oracleType = rows.getString("CONSTRAINT_TYPE");
                    ConstraintBuilder builder = constraints.computeIfAbsent(
                            name,
                            ignored -> new ConstraintBuilder(
                                    name,
                                    oracleType,
                                    rowsUnchecked(rows, "STATUS"),
                                    rowsUnchecked(rows, "R_OWNER"),
                                    rowsUnchecked(rows, "REFERENCED_TABLE"),
                                    rowsUnchecked(rows, "DELETE_RULE"),
                                    rowsUnchecked(rows, "DEFERRABLE"),
                                    rowsUnchecked(rows, "SEARCH_CONDITION_VC")));
                    String column = rows.getString("COLUMN_NAME");
                    Integer position = nullableInteger(rows, "POSITION");
                    if (column != null && position != null) {
                        builder.columns.add(new RawConstraintColumn(
                                column,
                                position,
                                rows.getString("REFERENCED_COLUMN")));
                    }
                    else if (!"C".equals(oracleType) && (column != null || position != null)) {
                        throw new OracleSchemaSnapshotCodecException(
                                "Oracle key constraint column position is incomplete.");
                    }
                }
            }
        }
        return constraints.values().stream().map(ConstraintBuilder::build).toList();
    }

    private String rowsUnchecked(ResultSet rows, String name) {
        try {
            return rows.getString(name);
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Oracle dictionary row is unreadable.");
        }
    }

    private String codecType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("TIMESTAMP(?:\\([0-9]+\\))?")) {
            return "TIMESTAMP";
        }
        return normalized;
    }

    private Integer nullableInteger(ResultSet rows, String name) throws SQLException {
        int value = rows.getInt(name);
        return rows.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet rows, String name) throws SQLException {
        long value = rows.getLong(name);
        return rows.wasNull() ? null : value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static final class ConstraintBuilder {
        private final String name;
        private final String type;
        private final String status;
        private final String referencedOwner;
        private final String referencedTable;
        private final String deleteRule;
        private final String deferrability;
        private final String checkExpression;
        private final List<RawConstraintColumn> columns = new ArrayList<>();

        private ConstraintBuilder(
                String name,
                String type,
                String status,
                String referencedOwner,
                String referencedTable,
                String deleteRule,
                String deferrability,
                String checkExpression) {
            this.name = name;
            this.type = type;
            this.status = status;
            this.referencedOwner = referencedOwner;
            this.referencedTable = referencedTable;
            this.deleteRule = deleteRule;
            this.deferrability = deferrability;
            this.checkExpression = checkExpression;
        }

        private RawConstraint build() {
            return new RawConstraint(
                    name, type, status, referencedOwner, referencedTable, deleteRule,
                    deferrability, checkExpression, columns);
        }
    }

    private record ColumnWithoutDefault(
            String name,
            String dataType,
            Integer precision,
            Integer scale,
            Long charLength,
            Integer timePrecision,
            int ordinal,
            boolean nullable) {
    }
}

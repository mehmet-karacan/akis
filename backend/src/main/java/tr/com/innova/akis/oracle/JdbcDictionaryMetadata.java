package tr.com.innova.akis.oracle;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ColumnMetadata;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConstraintMetadata;

/** Column and key listing through {@link DatabaseMetaData}; the browse-level discovery every JDBC technology shares. */
public final class JdbcDictionaryMetadata {
    private JdbcDictionaryMetadata() { }

    public static List<ColumnMetadata> readColumns(
            DatabaseMetaData metadata,
            String owner,
            String table) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet resultSet = metadata.getColumns(null, owner, table, "%")) {
            while (resultSet.next()) {
                // Oracle exposes COLUMN_DEF as a LONG-backed stream. Reading it through
                // DatabaseMetaData can close the stream (ORA-17027) before iteration ends.
                // Default expressions require a separate, explicit dictionary query.
                String defaultValue = null;
                columns.add(new ColumnMetadata(
                        resultSet.getString("COLUMN_NAME"),
                        resultSet.getInt("DATA_TYPE"),
                        resultSet.getString("TYPE_NAME"),
                        resultSet.getInt("ORDINAL_POSITION"),
                        nullableInteger(resultSet, "COLUMN_SIZE"),
                        nullableInteger(resultSet, "DECIMAL_DIGITS"),
                        resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        defaultValue));
            }
        }
        return List.copyOf(columns);
    }

    public static List<ConstraintMetadata> readConstraints(
            DatabaseMetaData metadata,
            String owner,
            String table) throws SQLException {
        List<ConstraintMetadata> constraints = new ArrayList<>();
        Set<String> primaryKeyNames = new LinkedHashSet<>();
        Map<String, List<String>> primaryKeys = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(null, owner, table)) {
            while (resultSet.next()) {
                String name = fallbackName(resultSet.getString("PK_NAME"), "PK_" + table);
                primaryKeyNames.add(name);
                primaryKeys.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(resultSet.getString("COLUMN_NAME"));
            }
        }
        primaryKeys.forEach((name, columns) -> constraints.add(new ConstraintMetadata(
                name, "PK", List.copyOf(columns), null, null)));

        Map<String, List<String>> uniqueKeys = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(null, owner, table, true, true)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                String column = resultSet.getString("COLUMN_NAME");
                if (name != null && column != null && !primaryKeyNames.contains(name)
                        && resultSet.getShort("TYPE") != DatabaseMetaData.tableIndexStatistic) {
                    uniqueKeys.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                }
            }
        }
        uniqueKeys.forEach((name, columns) -> constraints.add(new ConstraintMetadata(
                name, "UK", List.copyOf(columns), null, null)));

        Map<String, ForeignKeyBuilder> foreignKeys = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getImportedKeys(null, owner, table)) {
            while (resultSet.next()) {
                String name = fallbackName(resultSet.getString("FK_NAME"), "FK_" + table);
                String referencedOwner = resultSet.getString("PKTABLE_SCHEM");
                String referencedTable = resultSet.getString("PKTABLE_NAME");
                ForeignKeyBuilder key = foreignKeys.computeIfAbsent(
                        name,
                        ignored -> new ForeignKeyBuilder(referencedOwner, referencedTable));
                key.columns.add(resultSet.getString("FKCOLUMN_NAME"));
            }
        }
        foreignKeys.forEach((name, key) -> constraints.add(new ConstraintMetadata(
                name, "FK", List.copyOf(key.columns), key.owner, key.table)));
        return List.copyOf(constraints);
    }

    static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String fallbackName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class ForeignKeyBuilder {
        private final String owner;
        private final String table;
        private final List<String> columns = new ArrayList<>();

        private ForeignKeyBuilder(String owner, String table) {
            this.owner = owner;
            this.table = table;
        }
    }
}

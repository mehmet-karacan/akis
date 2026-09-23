package tr.com.innova.akis.oracle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure Oracle 19c dictionary metadata to schema-snapshot conversion contract. */
public final class OracleSchemaSnapshotCodecV1 {

    public static final String ENGINE_VERSION = "ORACLE_19C";
    public static final int PROPERTY_VERSION = 1;
    public static final int DETAIL_VERSION = 1;

    private static final String CODEC = "ORACLE_SCHEMA_V1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private static final Set<String> DELETE_RULES = Set.of("NO_ACTION", "CASCADE", "SET_NULL");
    private static final Set<String> DEFERRABILITY = Set.of("DEFERRABLE", "NOT_DEFERRABLE");

    private final ObjectMapper objectMapper;

    public OracleSchemaSnapshotCodecV1(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required.");
        }
        this.objectMapper = objectMapper;
    }

    public SnapshotDefinition decode(
            String owner,
            String tableName,
            List<RawColumn> rawColumns,
            List<RawConstraint> rawConstraints) {
        String normalizedOwner = identifier(owner, "owner");
        String normalizedTable = identifier(tableName, "table name");
        List<Column> columns = columns(rawColumns);
        List<Constraint> constraints = constraints(rawConstraints, columns);
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("codec", CODEC);
        properties.put("objectType", "TABLE");
        properties.put("owner", normalizedOwner);
        properties.put("table", normalizedTable);
        return new SnapshotDefinition(
                ENGINE_VERSION, PROPERTY_VERSION, properties, columns, constraints);
    }

    private List<Column> columns(List<RawColumn> rawColumns) {
        if (rawColumns == null || rawColumns.isEmpty()) {
            throw failure("At least one Oracle column is required.");
        }
        Set<String> names = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        List<Column> result = new ArrayList<>();
        for (RawColumn raw : rawColumns) {
            if (raw == null) {
                throw failure("Oracle column metadata cannot be null.");
            }
            String name = identifier(raw.name(), "column name");
            if (!names.add(name)) {
                throw failure("Oracle column names must be unique.");
            }
            if (raw.ordinal() < 1 || !ordinals.add(raw.ordinal())) {
                throw failure("Oracle column ordinals must be positive and unique.");
            }
            String dataType = upper(raw.dataType(), "data type");
            result.add(switch (dataType) {
                case "NUMBER" -> number(raw, name);
                case "VARCHAR2" -> varchar2(raw, name);
                case "TIMESTAMP" -> timestamp(raw, name);
                case "CLOB" -> clob(raw, name);
                default -> throw failure("Unsupported Oracle data type: " + dataType + ".");
            });
        }
        result.sort(Comparator.comparingInt(Column::ordinal).thenComparing(Column::reference));
        return List.copyOf(result);
    }

    private Column number(RawColumn raw, String name) {
        requireNull(raw.charLength(), "NUMBER char length");
        requireNull(raw.timePrecision(), "NUMBER time precision");
        Integer precision = raw.precision();
        Integer scale = raw.scale();
        if ((precision == null) != (scale == null)) {
            throw failure("Oracle NUMBER precision and scale must both be present or both be null.");
        }
        if (precision == null) {
            return column(raw, name, "NUMBER", "DECIMAL", null, null, null, null);
        }
        if (precision < 1 || precision > 38 || scale < 0 || scale > 127) {
            throw failure("Oracle NUMBER precision or scale is outside the supported range.");
        }
        String canonicalType = scale == 0 && precision <= 19 ? "INTEGER" : "DECIMAL";
        String producerType = "NUMBER(" + precision + "," + scale + ")";
        return column(
                raw, name, producerType, canonicalType, precision, scale, null, null);
    }

    private Column varchar2(RawColumn raw, String name) {
        requireNull(raw.precision(), "VARCHAR2 precision");
        requireNull(raw.scale(), "VARCHAR2 scale");
        requireNull(raw.timePrecision(), "VARCHAR2 time precision");
        Long length = raw.charLength();
        if (length == null || length < 1 || length > 32_767) {
            throw failure("Oracle VARCHAR2 character length is outside the supported range.");
        }
        return column(
                raw, name, "VARCHAR2(" + length + ")", "STRING",
                null, null, length, null);
    }

    private Column clob(RawColumn raw, String name) {
        requireNull(raw.precision(), "CLOB precision");
        requireNull(raw.scale(), "CLOB scale");
        requireNull(raw.timePrecision(), "CLOB time precision");
        // CHAR_LENGTH is not meaningful for a LOB (Oracle reports an internal locator size, not a character count),
        // so it is deliberately not validated or carried through, unlike VARCHAR2's declared length.
        return column(raw, name, "CLOB", "STRING", null, null, null, null);
    }

    private Column timestamp(RawColumn raw, String name) {
        requireNull(raw.precision(), "TIMESTAMP numeric precision");
        requireNull(raw.scale(), "TIMESTAMP numeric scale");
        requireNull(raw.charLength(), "TIMESTAMP char length");
        int timePrecision = raw.timePrecision() == null ? 6 : raw.timePrecision();
        if (timePrecision < 0 || timePrecision > 6) {
            throw failure("Oracle TIMESTAMP precision is outside the supported range.");
        }
        return column(
                raw, name, "TIMESTAMP(" + timePrecision + ")", "TIMESTAMP",
                null, null, null, timePrecision);
    }

    private Column column(
            RawColumn raw,
            String name,
            String producerType,
            String canonicalType,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision) {
        return new Column(
                name, producerType, canonicalType, raw.ordinal(), precision, scale,
                length, timePrecision, raw.nullable(), optional(raw.defaultExpression()), name);
    }

    private List<Constraint> constraints(
            List<RawConstraint> rawConstraints,
            List<Column> columns) {
        if (rawConstraints == null) {
            throw failure("Oracle constraints collection is required; it may be empty.");
        }
        Set<String> availableColumns = new HashSet<>();
        columns.forEach(column -> availableColumns.add(column.reference()));
        Set<String> names = new HashSet<>();
        List<Constraint> result = new ArrayList<>();
        for (RawConstraint raw : rawConstraints) {
            if (raw == null) {
                throw failure("Oracle constraint metadata cannot be null.");
            }
            String name = identifier(raw.name(), "constraint name");
            if (!names.add(name)) {
                throw failure("Oracle constraint names must be unique.");
            }
            String type = constraintType(upper(raw.oracleType(), "constraint type"));
            boolean enabled = status(raw.status());
            List<RawConstraintColumn> positioned = positioned(raw.columns(), availableColumns, type);
            result.add(switch (type) {
                case "PK", "UK" -> key(raw, name, type, enabled, positioned);
                case "FK" -> foreignKey(raw, name, enabled, positioned);
                case "CHECK" -> check(raw, name, enabled, positioned);
                default -> throw new IllegalStateException("Unexpected constraint type.");
            });
        }
        result.sort(Comparator.comparing(Constraint::externalReference));
        return List.copyOf(result);
    }

    private Constraint key(
            RawConstraint raw,
            String name,
            String type,
            boolean enabled,
            List<RawConstraintColumn> columns) {
        if (columns.isEmpty()) {
            throw failure(type + " constraint must contain at least one column.");
        }
        requireAbsentReference(raw, type);
        requireNotDeferrable(raw.deferrability(), type);
        return constraint(name, type, enabled, objectMapper.createObjectNode(), columns);
    }

    private Constraint foreignKey(
            RawConstraint raw,
            String name,
            boolean enabled,
            List<RawConstraintColumn> columns) {
        if (columns.isEmpty()) {
            throw failure("FK constraint must contain at least one column.");
        }
        String referencedOwner = identifier(raw.referencedOwner(), "referenced owner");
        String referencedTable = identifier(raw.referencedTable(), "referenced table");
        String deleteRule = rule(raw.deleteRule(), DELETE_RULES, "delete rule");
        String deferrability = rule(raw.deferrability(), DEFERRABILITY, "deferrability");
        if (optional(raw.checkExpression()) != null) {
            throw failure("FK constraint cannot carry a check expression.");
        }
        ObjectNode details = objectMapper.createObjectNode();
        details.put("deferrability", deferrability);
        details.put("deleteRule", deleteRule);
        details.put("referencedOwner", referencedOwner);
        details.put("referencedTable", referencedTable);
        var references = details.putArray("referencedColumns");
        for (RawConstraintColumn column : columns) {
            references.add(identifier(column.referencedColumnName(), "referenced column"));
        }
        return constraint(name, "FK", enabled, details, columns);
    }

    private Constraint check(
            RawConstraint raw,
            String name,
            boolean enabled,
            List<RawConstraintColumn> columns) {
        if (raw.referencedOwner() != null || raw.referencedTable() != null
                || raw.deleteRule() != null) {
            throw failure("CHECK constraint cannot carry foreign-key metadata.");
        }
        requireNotDeferrable(raw.deferrability(), "CHECK");
        String expression = required(raw.checkExpression(), "check expression");
        ObjectNode details = objectMapper.createObjectNode();
        details.put("expression", expression);
        return constraint(name, "CHECK", enabled, details, columns);
    }

    private Constraint constraint(
            String name,
            String type,
            boolean enabled,
            JsonNode details,
            List<RawConstraintColumn> columns) {
        return new Constraint(
                name, type, enabled, DETAIL_VERSION, details, name,
                columns.stream().map(RawConstraintColumn::columnName).toList());
    }

    private List<RawConstraintColumn> positioned(
            List<RawConstraintColumn> rawColumns,
            Set<String> availableColumns,
            String type) {
        if (rawColumns == null) {
            throw failure("Oracle constraint columns collection is required; it may be empty.");
        }
        Set<Integer> positions = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<RawConstraintColumn> result = new ArrayList<>();
        for (RawConstraintColumn raw : rawColumns) {
            if (raw == null || raw.position() < 1 || !positions.add(raw.position())) {
                throw failure("Oracle constraint positions must be positive and unique.");
            }
            String name = identifier(raw.columnName(), "constraint column");
            if (!availableColumns.contains(name) || !names.add(name)) {
                throw failure("Oracle constraint columns must uniquely reference snapshot columns.");
            }
            String referenced = optional(raw.referencedColumnName());
            if ("FK".equals(type) != (referenced != null)) {
                throw failure("Only FK constraint columns may carry referenced columns.");
            }
            result.add(new RawConstraintColumn(name, raw.position(), referenced));
        }
        result.sort(Comparator.comparingInt(RawConstraintColumn::position));
        return List.copyOf(result);
    }

    private void requireAbsentReference(RawConstraint raw, String type) {
        if (raw.referencedOwner() != null || raw.referencedTable() != null
                || raw.deleteRule() != null || raw.checkExpression() != null) {
            throw failure(type + " constraint cannot carry FK or CHECK metadata.");
        }
    }

    private void requireNotDeferrable(String value, String type) {
        if (value != null && !"NOT_DEFERRABLE".equals(normalizedRule(value))) {
            throw failure(type + " constraint deferrability is unsupported.");
        }
    }

    private String constraintType(String oracleType) {
        return switch (oracleType) {
            case "P" -> "PK";
            case "U" -> "UK";
            case "R" -> "FK";
            case "C" -> "CHECK";
            default -> throw failure("Unsupported Oracle constraint type: " + oracleType + ".");
        };
    }

    private boolean status(String value) {
        return switch (upper(value, "constraint status")) {
            case "ENABLED" -> true;
            case "DISABLED" -> false;
            default -> throw failure("Unsupported Oracle constraint status.");
        };
    }

    private String rule(String value, Set<String> allowed, String field) {
        String normalized = normalizedRule(required(value, field));
        if (!allowed.contains(normalized)) {
            throw failure("Unsupported Oracle " + field + ".");
        }
        return normalized;
    }

    private String normalizedRule(String value) {
        return value.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private String identifier(String value, String field) {
        String normalized = upper(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw failure("Invalid Oracle " + field + ".");
        }
        return normalized;
    }

    private String upper(String value, String field) {
        return required(value, field).toUpperCase(Locale.ROOT);
    }

    private String required(String value, String field) {
        String normalized = optional(value);
        if (normalized == null || normalized.indexOf('\0') >= 0) {
            throw failure("Oracle " + field + " is required and must be valid.");
        }
        return normalized;
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void requireNull(Object value, String field) {
        if (value != null) {
            throw failure(field + " must be absent.");
        }
    }

    private OracleSchemaSnapshotCodecException failure(String message) {
        return new OracleSchemaSnapshotCodecException(message);
    }

    public record RawColumn(
            String name,
            String dataType,
            Integer precision,
            Integer scale,
            Long charLength,
            Integer timePrecision,
            int ordinal,
            boolean nullable,
            String defaultExpression) {
    }

    public record RawConstraintColumn(
            String columnName,
            int position,
            String referencedColumnName) {
    }

    public record RawConstraint(
            String name,
            String oracleType,
            String status,
            String referencedOwner,
            String referencedTable,
            String deleteRule,
            String deferrability,
            String checkExpression,
            List<RawConstraintColumn> columns) {

        public RawConstraint {
            columns = columns == null ? null : List.copyOf(columns);
        }
    }

    public record Column(
            String reference,
            String producerType,
            String canonicalType,
            int ordinal,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision,
            boolean nullable,
            String defaultExpression,
            String name) {
    }

    public record Constraint(
            String externalReference,
            String type,
            boolean enabled,
            int detailVersion,
            JsonNode details,
            String name,
            List<String> columnReferences) {

        public Constraint {
            details = details.deepCopy();
            columnReferences = List.copyOf(columnReferences);
        }

        @Override
        public JsonNode details() {
            return details.deepCopy();
        }
    }

    public record SnapshotDefinition(
            String engineVersion,
            int propertyVersion,
            JsonNode properties,
            List<Column> columns,
            List<Constraint> constraints) {

        public SnapshotDefinition {
            properties = properties.deepCopy();
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
        }

        @Override
        public JsonNode properties() {
            return properties.deepCopy();
        }
    }
}

package tr.com.innova.akis.postgres;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecException;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.Column;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.Constraint;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.SnapshotDefinition;

/**
 * Canonical PostgreSQL table snapshot (codec POSTGRESQL_SCHEMA_V1). Produces the same {@link SnapshotDefinition} shape the
 * Oracle codec does so fingerprinting, pinning and the catalog screens stay technology-neutral:
 * <ul>
 *   <li>identifiers are kept as stored in pg_catalog (case-sensitive; the runtime always quotes them),</li>
 *   <li>the producer type is the normalized {@code format_type()} text, the canonical type the AKIS vocabulary
 *       (INTEGER, DECIMAL, STRING, TIMESTAMP, OFFSET_TIMESTAMP, DATE, BOOLEAN, BINARY, FLOAT32, FLOAT64, UNKNOWN),</li>
 *   <li>unknown types are recorded as UNKNOWN rather than rejected, so an existing schema can always be catalogued;
 *       transfer support is decided per column later.</li>
 * </ul>
 * The engine version is the major-agnostic constant so a server patch upgrade never changes a structural fingerprint.
 */
public final class PostgresSchemaSnapshotCodecV1 {
    public static final String ENGINE_VERSION = "POSTGRESQL";
    public static final int PROPERTY_VERSION = 1;
    public static final int DETAIL_VERSION = 1;
    static final String CODEC = "POSTGRESQL_SCHEMA_V1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");
    private static final Pattern NUMERIC = Pattern.compile("numeric\\((\\d+),(\\d+)\\)");
    private static final Pattern VARCHAR = Pattern.compile("character varying\\((\\d+)\\)");
    private static final Pattern CHAR = Pattern.compile("character\\((\\d+)\\)");
    private static final Pattern TIMESTAMP = Pattern.compile("timestamp(?:\\((\\d)\\))? without time zone");
    private static final Pattern TIMESTAMPTZ = Pattern.compile("timestamp(?:\\((\\d)\\))? with time zone");
    private static final Set<String> DELETE_RULES = Set.of("NO_ACTION", "RESTRICT", "CASCADE", "SET_NULL", "SET_DEFAULT");

    private final ObjectMapper objectMapper;

    public PostgresSchemaSnapshotCodecV1(ObjectMapper objectMapper) {
        if (objectMapper == null) throw new IllegalArgumentException("ObjectMapper is required.");
        this.objectMapper = objectMapper;
    }

    public record RawColumn(String name, String formatType, int ordinal, boolean nullable, String defaultExpression) { }

    public record RawConstraintColumn(String columnName, int position, String referencedColumnName) { }

    /** {@code type}: p, u, f or c as pg_constraint.contype; {@code deleteRule} already decoded to its keyword. */
    public record RawConstraint(
            String name,
            String type,
            boolean validated,
            boolean deferrable,
            String referencedSchema,
            String referencedTable,
            String deleteRule,
            String checkExpression,
            List<RawConstraintColumn> columns) {
        public RawConstraint { columns = columns == null ? null : List.copyOf(columns); }
    }

    public SnapshotDefinition decode(String schema, String tableName, List<RawColumn> rawColumns, List<RawConstraint> rawConstraints) {
        String normalizedSchema = identifier(schema, "schema");
        String normalizedTable = identifier(tableName, "table name");
        List<Column> columns = columns(rawColumns);
        List<Constraint> constraints = constraints(rawConstraints, columns);
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("codec", CODEC);
        properties.put("objectType", "TABLE");
        properties.put("owner", normalizedSchema);
        properties.put("table", normalizedTable);
        return new SnapshotDefinition(ENGINE_VERSION, PROPERTY_VERSION, properties, columns, constraints);
    }

    private List<Column> columns(List<RawColumn> rawColumns) {
        if (rawColumns == null || rawColumns.isEmpty()) throw failure("At least one PostgreSQL column is required.");
        Set<String> names = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        List<Column> result = new ArrayList<>();
        for (RawColumn raw : rawColumns) {
            if (raw == null) throw failure("PostgreSQL column metadata cannot be null.");
            String name = identifier(raw.name(), "column name");
            if (!names.add(name)) throw failure("PostgreSQL column names must be unique.");
            if (raw.ordinal() < 1 || !ordinals.add(raw.ordinal())) throw failure("PostgreSQL column ordinals must be positive and unique.");
            result.add(column(raw, name));
        }
        result.sort(Comparator.comparingInt(Column::ordinal).thenComparing(Column::reference));
        return List.copyOf(result);
    }

    private Column column(RawColumn raw, String name) {
        String type = required(raw.formatType(), "data type").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Matcher m;
        if ((m = NUMERIC.matcher(type)).matches()) {
            int precision = Integer.parseInt(m.group(1)), scale = Integer.parseInt(m.group(2));
            String canonical = scale == 0 && precision <= 19 ? "INTEGER" : "DECIMAL";
            return build(raw, name, "NUMERIC(" + precision + "," + scale + ")", canonical, precision, scale, null, null);
        }
        if ((m = VARCHAR.matcher(type)).matches()) {
            long length = Long.parseLong(m.group(1));
            return build(raw, name, "VARCHAR(" + length + ")", "STRING", null, null, length, null);
        }
        if ((m = CHAR.matcher(type)).matches()) {
            long length = Long.parseLong(m.group(1));
            return build(raw, name, "CHAR(" + length + ")", "STRING", null, null, length, null);
        }
        if ((m = TIMESTAMP.matcher(type)).matches()) {
            int precision = m.group(1) == null ? 6 : Integer.parseInt(m.group(1));
            return build(raw, name, "TIMESTAMP(" + precision + ")", "TIMESTAMP", null, null, null, precision);
        }
        if ((m = TIMESTAMPTZ.matcher(type)).matches()) {
            int precision = m.group(1) == null ? 6 : Integer.parseInt(m.group(1));
            return build(raw, name, "TIMESTAMPTZ(" + precision + ")", "OFFSET_TIMESTAMP", null, null, null, precision);
        }
        return switch (type) {
            case "numeric" -> build(raw, name, "NUMERIC", "DECIMAL", null, null, null, null);
            case "smallint" -> build(raw, name, "SMALLINT", "INTEGER", 5, 0, null, null);
            case "integer" -> build(raw, name, "INTEGER", "INTEGER", 10, 0, null, null);
            case "bigint" -> build(raw, name, "BIGINT", "INTEGER", 19, 0, null, null);
            case "character varying" -> build(raw, name, "VARCHAR", "STRING", null, null, null, null);
            case "text" -> build(raw, name, "TEXT", "STRING", null, null, null, null);
            case "uuid" -> build(raw, name, "UUID", "STRING", null, null, 36L, null);
            case "date" -> build(raw, name, "DATE", "DATE", null, null, null, null);
            case "boolean" -> build(raw, name, "BOOLEAN", "BOOLEAN", null, null, null, null);
            case "bytea" -> build(raw, name, "BYTEA", "BINARY", null, null, null, null);
            case "real" -> build(raw, name, "REAL", "FLOAT32", null, null, null, null);
            case "double precision" -> build(raw, name, "DOUBLE PRECISION", "FLOAT64", null, null, null, null);
            default -> build(raw, name, type.toUpperCase(Locale.ROOT), "UNKNOWN", null, null, null, null);
        };
    }

    private Column build(RawColumn raw, String name, String producerType, String canonicalType,
            Integer precision, Integer scale, Long length, Integer timePrecision) {
        return new Column(name, producerType, canonicalType, raw.ordinal(), precision, scale, length, timePrecision,
                raw.nullable(), optional(raw.defaultExpression()), name);
    }

    private List<Constraint> constraints(List<RawConstraint> rawConstraints, List<Column> columns) {
        if (rawConstraints == null) throw failure("PostgreSQL constraints collection is required; it may be empty.");
        Set<String> availableColumns = new HashSet<>();
        columns.forEach(column -> availableColumns.add(column.reference()));
        Set<String> names = new HashSet<>();
        List<Constraint> result = new ArrayList<>();
        for (RawConstraint raw : rawConstraints) {
            if (raw == null) throw failure("PostgreSQL constraint metadata cannot be null.");
            String name = identifier(raw.name(), "constraint name");
            if (!names.add(name)) throw failure("PostgreSQL constraint names must be unique.");
            String type = switch (required(raw.type(), "constraint type")) {
                case "p" -> "PK";
                case "u" -> "UK";
                case "f" -> "FK";
                case "c" -> "CHECK";
                default -> throw failure("Unsupported PostgreSQL constraint type.");
            };
            List<RawConstraintColumn> positioned = positioned(raw.columns(), availableColumns);
            ObjectNode details = objectMapper.createObjectNode();
            switch (type) {
                case "PK", "UK" -> {
                    if (positioned.isEmpty()) throw failure(type + " constraint must contain at least one column.");
                    details.put("deferrability", raw.deferrable() ? "DEFERRABLE" : "NOT_DEFERRABLE");
                }
                case "FK" -> {
                    if (positioned.isEmpty()) throw failure("FK constraint must contain at least one column.");
                    String rule = required(raw.deleteRule(), "delete rule").toUpperCase(Locale.ROOT).replace(' ', '_');
                    if (!DELETE_RULES.contains(rule)) throw failure("Unsupported PostgreSQL delete rule.");
                    details.put("deferrability", raw.deferrable() ? "DEFERRABLE" : "NOT_DEFERRABLE");
                    details.put("deleteRule", rule);
                    details.put("referencedOwner", identifier(raw.referencedSchema(), "referenced schema"));
                    details.put("referencedTable", identifier(raw.referencedTable(), "referenced table"));
                    var references = details.putArray("referencedColumns");
                    for (RawConstraintColumn column : positioned) references.add(identifier(column.referencedColumnName(), "referenced column"));
                }
                default -> details.put("expression", required(raw.checkExpression(), "check expression"));
            }
            result.add(new Constraint(name, type, raw.validated(), DETAIL_VERSION, details, name,
                    positioned.stream().map(RawConstraintColumn::columnName).toList()));
        }
        result.sort(Comparator.comparing(Constraint::externalReference));
        return List.copyOf(result);
    }

    private List<RawConstraintColumn> positioned(List<RawConstraintColumn> rawColumns, Set<String> availableColumns) {
        if (rawColumns == null) throw failure("PostgreSQL constraint columns collection is required; it may be empty.");
        Set<Integer> positions = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<RawConstraintColumn> result = new ArrayList<>();
        for (RawConstraintColumn raw : rawColumns) {
            if (raw == null || raw.position() < 1 || !positions.add(raw.position())) throw failure("PostgreSQL constraint positions must be positive and unique.");
            String name = identifier(raw.columnName(), "constraint column");
            if (!availableColumns.contains(name) || !names.add(name)) throw failure("PostgreSQL constraint columns must uniquely reference snapshot columns.");
            result.add(raw);
        }
        result.sort(Comparator.comparingInt(RawConstraintColumn::position));
        return List.copyOf(result);
    }

    private String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) throw failure("Invalid PostgreSQL " + field + ".");
        return normalized;
    }

    private String required(String value, String field) {
        String normalized = optional(value);
        if (normalized == null || normalized.indexOf('\0') >= 0) throw failure("PostgreSQL " + field + " is required and must be valid.");
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private OracleSchemaSnapshotCodecException failure(String message) {
        return new OracleSchemaSnapshotCodecException(message);
    }

    /** Exposed for the discovery view: canonical type and whether the Faz A runtime can write this column. */
    public static String canonicalTypeOf(String formatType) {
        return new PostgresSchemaSnapshotCodecV1(new ObjectMapper()).column(new RawColumn("c", formatType, 1, true, null), "c").canonicalType();
    }

    static JsonNode emptyDetails(ObjectMapper mapper) { return mapper.createObjectNode(); }
}

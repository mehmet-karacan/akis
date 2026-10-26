package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;
import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.LiveTarget;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;

/**
 * Preflight for a staged mapping whose target is PostgreSQL: the Oracle sources are verified by the Oracle preflight,
 * this class verifies the pinned PostgreSQL target against pg_catalog, checks that the target is writable the way Faz A
 * writes it (no generated or identity columns, no enabled triggers, no inbound foreign keys that TRUNCATE would reject)
 * and applies the Oracle→PostgreSQL type compatibility rule.
 */
final class JdbcPostgresSchemaPreflight {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcPostgresSchemaPreflight.class);
    private static final Pattern SAFE_LITERAL_DEFAULT = Pattern.compile("(?s)(?:[-+]?\\d+(?:\\.\\d+)?|'(?:[^']|'')*'(?:::[A-Za-z ]+)?)");
    private static final String COLUMN_SQL = """
            SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS format_type, a.attnotnull, a.attnum,
                   a.attgenerated::text AS generated, a.attidentity::text AS identity,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS default_expression
              FROM pg_catalog.pg_attribute a
              JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
              LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
             ORDER BY a.attnum
            """;
    private static final String TRIGGER_SQL = """
            SELECT 1 FROM pg_catalog.pg_trigger t
              JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ? AND c.relname = ? AND NOT t.tgisinternal AND t.tgenabled <> 'D'
            """;
    private static final String INBOUND_FK_SQL = """
            SELECT 1 FROM pg_catalog.pg_constraint con
              JOIN pg_catalog.pg_class c ON c.oid = con.confrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE con.contype = 'f' AND n.nspname = ? AND c.relname = ? AND con.conrelid <> con.confrelid
            """;

    private final JdbcOracleSchemaPreflight oracle;

    JdbcPostgresSchemaPreflight(ObjectMapper objectMapper) {
        this.oracle = new JdbcOracleSchemaPreflight(objectMapper);
    }

    /** Full preflight before the work table is created: live Oracle sources, live PostgreSQL target, mapping rules. */
    void verifyStaged(StagedRuntimePlan plan, Connection sourceConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots, Connection targetConnection) {
        Map<String, List<Column>> sources = oracle.verifySources(plan, sourceConnection, snapshots);
        verifyTarget(plan, targetConnection, snapshots, sources);
    }

    /** Publish-time preflight on the locked target connection; sources are checked from their pinned snapshots only. */
    void verifyLockedStagedTarget(StagedRuntimePlan plan, Connection targetConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots) {
        verifyTarget(plan, targetConnection, snapshots, oracle.pinnedSources(plan, snapshots));
    }

    private void verifyTarget(StagedRuntimePlan plan, Connection targetConnection, PinnedSchemaSnapshotPort.PinnedSnapshots snapshots,
            Map<String, List<Column>> sources) {
        Objects.requireNonNull(targetConnection, "PostgreSQL target connection is required.");
        var targetSnapshot = snapshots.target();
        List<Column> expected = oracle.pinnedTarget(plan.target(), new ExpectedSnapshot(targetSnapshot.schemaSnapshotUuid(), targetSnapshot.body()), DatabaseType.POSTGRESQL);
        List<LiveColumn> live = readColumns(targetConnection, plan.target().owner(), plan.target().objectName());
        compareColumns(expected, live);
        verifyWriteSafety(targetConnection, plan.target(), live,
                plan.definition().stringOption("integration", "WRITE_MODE"));
        oracle.validateStagedMappings(plan, sources, expected,
                live.stream().map(column -> new LiveTarget(column.name(), column.nullable(), column.defaultExpression())).toList(),
                JdbcPostgresSchemaPreflight::requireCompatible);
    }

    /**
     * Oracle source column to PostgreSQL target column. Canonical types must match and the target must not be narrower;
     * the producer types are the ones the two codecs emit, so the mapping is explicit rather than name-based.
     */
    static void requireCompatible(Column source, Column target) {
        String canonical = source.canonicalType().toUpperCase(Locale.ROOT);
        if (!canonical.equals(target.canonicalType().toUpperCase(Locale.ROOT))
                || !Set.of("INTEGER", "DECIMAL", "STRING", "TIMESTAMP", "BINARY", "FLOAT64").contains(canonical)
                || source.nullable() && !target.nullable()) {
            LOGGER.warn("PostgreSQL mapping rejected for source column {} and target column {}: canonical type/nullability mismatch.",
                    source.reference(), target.reference());
            throw failure();
        }
        String sourceType = baseType(source.producerType());
        String targetType = baseType(target.producerType());
        boolean compatible = switch (canonical) {
            case "INTEGER" -> sourceType.equals("NUMBER") && Objects.equals(source.scale(), 0) && source.precision() != null
                    && switch (targetType) {
                        case "SMALLINT" -> source.precision() <= 4;
                        case "INTEGER" -> source.precision() <= 9;
                        case "BIGINT" -> source.precision() <= 18;
                        case "NUMERIC" -> Objects.equals(target.scale(), 0) && atLeast(target.precision(), source.precision());
                        default -> false;
                    };
            case "DECIMAL" -> sourceType.equals("NUMBER") && targetType.equals("NUMERIC")
                    && (source.scale() == null ? target.scale() == null : Objects.equals(source.scale(), target.scale()))
                    && (source.precision() == null ? target.precision() == null : atLeast(target.precision(), source.precision()));
            case "STRING" -> (sourceType.equals("VARCHAR2") && source.length() != null
                    && (targetType.equals("TEXT") || targetType.equals("VARCHAR") && atLeast(target.length(), source.length())))
                    || (sourceType.equals("CLOB") && targetType.equals("TEXT"));
            case "TIMESTAMP" -> Set.of("DATE", "TIMESTAMP").contains(sourceType) && targetType.equals("TIMESTAMP")
                    && (sourceType.equals("DATE") || source.timePrecision() != null && source.timePrecision() <= 6
                    && atLeast(target.timePrecision(), source.timePrecision()));
            case "BINARY" -> sourceType.equals("BLOB") && targetType.equals("BYTEA");
            case "FLOAT64" -> Set.of("FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE").contains(sourceType)
                    && targetType.equals("DOUBLE PRECISION");
            default -> false;
        };
        if (!compatible) {
            LOGGER.warn("PostgreSQL mapping rejected for source column {} ({}) and target column {} ({}): incompatible type width or precision.",
                    source.reference(), source.producerType(), target.reference(), target.producerType());
            throw failure();
        }
    }

    private void compareColumns(List<Column> expected, List<LiveColumn> actual) {
        if (expected.size() != actual.size()) {
            LOGGER.warn("PostgreSQL target drift: expected {} columns but found {}.", expected.size(), actual.size());
            throw drift();
        }
        for (int index = 0; index < expected.size(); index++) {
            Column left = expected.get(index);
            LiveColumn right = actual.get(index);
            if (!left.reference().equals(right.name()) || left.ordinal() != right.ordinal()
                    || !left.producerType().equalsIgnoreCase(right.producerType())
                    || left.nullable() != right.nullable()
                    || !Objects.equals(trimToNull(left.defaultExpression()), right.defaultExpression())) {
                LOGGER.warn("PostgreSQL target drift at column {}: pinned metadata no longer matches the live table.", left.reference());
                throw drift();
            }
        }
    }

    private void verifyWriteSafety(Connection connection, PilotRuntimePlan.DatasetBinding binding, List<LiveColumn> live,
            String writeMode) {
        if (binding.role() != DatasetRole.TARGET || binding.databaseType() != DatabaseType.POSTGRESQL) throw failure();
        if (live.stream().anyMatch(column -> column.generated() || column.identity())) throw failure();
        requireAbsent(connection, TRIGGER_SQL, binding, "enabled trigger");
        // PostgreSQL TRUNCATE_LOAD is explicitly CASCADE and therefore supports dependency-ordered package refreshes.
        // APPEND/MERGE do not remove rows. ATOMIC_DELETE_INSERT remains fail-closed for inbound foreign keys.
        if (blocksInboundForeignKeys(writeMode)) requireAbsent(connection, INBOUND_FK_SQL, binding, "inbound foreign key");
    }

    static boolean blocksInboundForeignKeys(String writeMode) {
        return !Set.of("APPEND", "MERGE", "TRUNCATE_LOAD").contains(writeMode == null ? "" : writeMode.strip().toUpperCase(Locale.ROOT));
    }

    private void requireAbsent(Connection connection, String sql, PilotRuntimePlan.DatasetBinding binding, String what) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, binding.owner());
            statement.setString(2, binding.objectName());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    LOGGER.warn("PostgreSQL target {}.{} rejected: {} present.", binding.owner(), binding.objectName(), what);
                    throw failure();
                }
            }
        }
        catch (OracleSchemaPreflightException exception) { throw exception; }
        catch (SQLException | RuntimeException exception) { throw metadataUnavailable(); }
    }

    private List<LiveColumn> readColumns(Connection connection, String schema, String table) {
        List<LiveColumn> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(new LiveColumn(rows.getString("attname"),
                            tr.com.innova.akis.knowledge.PostgresWorkStructure.ddlOf(rows.getString("format_type")),
                            !rows.getBoolean("attnotnull"), trimToNull(rows.getString("default_expression")),
                            !rows.getString("generated").isBlank(), !rows.getString("identity").isBlank(), rows.getInt("attnum")));
                }
            }
        }
        catch (SQLException exception) {
            LOGGER.warn("PostgreSQL preflight metadata read failed (sqlState={}).", exception.getSQLState());
            throw metadataUnavailable();
        }
        if (columns.isEmpty()) throw drift();
        // A default the publish cannot satisfy is only a problem for unmapped NOT NULL columns; the shared rule decides.
        Map<String, LiveColumn> byName = new LinkedHashMap<>();
        for (LiveColumn column : columns) if (byName.put(column.name(), column) != null) throw drift();
        for (LiveColumn column : columns) {
            if (!column.nullable() && column.defaultExpression() != null && !SAFE_LITERAL_DEFAULT.matcher(column.defaultExpression()).matches()
                    && !column.defaultExpression().startsWith("nextval(")) {
                LOGGER.warn("PostgreSQL target column {} has an unsupported default.", column.name());
            }
        }
        return List.copyOf(columns);
    }

    private static String baseType(String producerType) {
        String type = producerType == null ? "" : producerType.strip().toUpperCase(Locale.ROOT);
        int paren = type.indexOf('(');
        return paren < 0 ? type : type.substring(0, paren);
    }

    private static boolean atLeast(Integer target, Integer source) { return source == null ? target == null : target != null && target >= source; }

    private static boolean atLeast(Long target, Long source) { return source == null ? target == null : target != null && target >= source; }

    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static OracleSchemaPreflightException failure() { return new OracleSchemaPreflightException(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA); }

    private static OracleSchemaPreflightException drift() { return new OracleSchemaPreflightException(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT); }

    private static OracleSchemaPreflightException metadataUnavailable() { return new OracleSchemaPreflightException(OracleSchemaPreflightFailure.METADATA_UNAVAILABLE); }

    private record LiveColumn(String name, String producerType, boolean nullable, String defaultExpression, boolean generated, boolean identity, int ordinal) { }
}

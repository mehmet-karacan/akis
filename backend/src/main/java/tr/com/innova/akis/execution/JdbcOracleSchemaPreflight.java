package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Constraint;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;

/**
 * Read-only, fail-closed schema drift gate for the Oracle table-copy pilot.
 *
 * <p>The frozen snapshot body is loaded by the caller from the control plane.
 * This component first proves that body hashes to the fingerprint pinned in the
 * runtime plan, then compares its physically observable fields with fresh
 * Oracle catalog reads. It never changes connection state or transaction state.
 */
final class JdbcOracleSchemaPreflight {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JdbcOracleSchemaPreflight.class);

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private static final String COLUMN_SQL = """
            SELECT column_name,
                   data_type,
                   data_precision,
                   CASE WHEN data_type = 'NUMBER' THEN data_scale END AS numeric_scale,
                   CASE
                       WHEN char_used IS NOT NULL THEN char_length
                       WHEN data_type = 'RAW' THEN data_length
                   END AS declared_length,
                   CASE
                       WHEN data_type LIKE 'TIMESTAMP%'
                         OR data_type LIKE 'INTERVAL%'
                       THEN data_scale
                   END AS time_precision,
                   nullable,
                   data_default,
                   virtual_column,
                   identity_column,
                   default_on_null,
                   column_id
              FROM all_tab_cols
             WHERE owner = ?
               AND table_name = ?
               AND hidden_column = 'NO'
             ORDER BY column_id
            """;

    private static final String ENABLED_TRIGGER_SQL = """
            SELECT trigger_name
              FROM all_triggers
             WHERE table_owner = ?
               AND table_name = ?
               AND status = 'ENABLED'
            """;

    private static final Set<String> SUPPORTED_CANONICAL_TYPES = Set.of(
            "INTEGER", "DECIMAL", "STRING", "TIMESTAMP");
    private static final Pattern SAFE_LITERAL_DEFAULT = Pattern.compile(
            "(?s)(?:[-+]?\\d+(?:\\.\\d+)?|[nN]?'(?:[^']|'')*')");

    private static final String CONSTRAINT_SQL = """
            SELECT c.constraint_name,
                   c.constraint_type,
                   c.status,
                   cc.column_name,
                   cc.position
              FROM all_constraints c
              LEFT JOIN all_cons_columns cc
                ON cc.owner = c.owner
               AND cc.constraint_name = c.constraint_name
               AND cc.table_name = c.table_name
             WHERE c.owner = ?
               AND c.table_name = ?
               AND (c.constraint_type IN ('P', 'U', 'R')
                    OR (c.constraint_type = 'C' AND c.generated = 'USER NAME'))
             ORDER BY c.constraint_name, cc.position
            """;

    private final SchemaFingerprint fingerprint;

    JdbcOracleSchemaPreflight(ObjectMapper objectMapper) {
        this.fingerprint = new SchemaFingerprint(objectMapper);
    }

    PreflightResult verify(
            MappingExecutionContract plan,
            Connection sourceConnection,
            ExpectedSnapshot sourceSnapshot,
            Connection targetConnection,
            ExpectedSnapshot targetSnapshot) {
        if (plan == null || sourceConnection == null || sourceSnapshot == null
                || targetConnection == null || targetSnapshot == null) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }
        VerifiedBinding source = verifyBinding(
                plan.source(), DatasetRole.SOURCE, sourceConnection, sourceSnapshot);
        VerifiedBinding target = verifyBinding(
                plan.target(), DatasetRole.TARGET, targetConnection, targetSnapshot);
        validateMappings(plan, source.expectedColumns(), target);
        verifyTargetWriteSafety(targetConnection, plan.target(), target);
        return new PreflightResult(source.result(), target.result());
    }

    /** Re-attests the source immediately before a bounded read on the same session. */
    BindingResult verifySource(
            MappingExecutionContract plan,
            Connection sourceConnection,
            ExpectedSnapshot sourceSnapshot) {
        if (plan == null || sourceConnection == null || sourceSnapshot == null) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }
        return verifyBinding(
                plan.source(), DatasetRole.SOURCE, sourceConnection, sourceSnapshot).result();
    }

    /** Re-attests the target during the fresh read-only identity session. */
    BindingResult verifyTarget(
            MappingExecutionContract plan,
            Connection targetConnection,
            ExpectedSnapshot targetSnapshot) {
        if (plan == null || targetConnection == null || targetSnapshot == null) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }
        VerifiedBinding target = verifyBinding(
                plan.target(), DatasetRole.TARGET, targetConnection, targetSnapshot);
        verifyTargetWriteSafety(targetConnection, plan.target(), target);
        return target.result();
    }

    /**
     * Re-attests the target on the caller's already exclusively locked physical
     * connection. Fresh catalog reads here close the gap between the earlier
     * two-sided preflight and the business write. Acquiring the table lock and
     * owning its transaction remain the enclosing facade's responsibility.
     */
    BindingResult verifyLockedTarget(
            MappingExecutionContract plan,
            Connection lockedTargetConnection,
            ExpectedSnapshot sourceSnapshot,
            ExpectedSnapshot targetSnapshot) {
        if (plan == null || lockedTargetConnection == null
                || sourceSnapshot == null || targetSnapshot == null) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }
        PinnedBinding source = verifyPinnedSnapshot(
                plan.source(), DatasetRole.SOURCE, sourceSnapshot);
        verifyLockedConnectionState(lockedTargetConnection);
        VerifiedBinding target = verifyBinding(
                plan.target(), DatasetRole.TARGET, lockedTargetConnection, targetSnapshot);
        validateMappings(plan, source.expectedColumns(), target);
        verifyTargetWriteSafety(lockedTargetConnection, plan.target(), target);
        return target.result();
    }

    private void verifyLockedConnectionState(Connection connection) {
        try {
            if (connection.isClosed() || connection.getAutoCommit() || connection.isReadOnly()) {
                throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
            }
        }
        catch (OracleSchemaPreflightException exception) {
            throw exception;
        }
        catch (SQLException | RuntimeException exception) {
            throw failure(OracleSchemaPreflightFailure.METADATA_UNAVAILABLE);
        }
    }

    private VerifiedBinding verifyBinding(
            DatasetBinding binding,
            DatasetRole requiredRole,
            Connection connection,
            ExpectedSnapshot snapshot) {
        PinnedBinding pinned = verifyPinnedSnapshot(binding, requiredRole, snapshot);
        List<LiveColumn> liveColumns;
        try {
            verifyOracle19c(connection.getMetaData());
            liveColumns = readColumns(
                    connection, binding.owner(), binding.objectName());
            compareColumns(snapshot.input().columns(), liveColumns);
            if (requiredRole == DatasetRole.TARGET) {
                List<LiveConstraint> liveConstraints = readConstraints(
                        connection, binding.owner(), binding.objectName());
                compareConstraints(snapshot.input().constraints(), liveConstraints);
            }
        }
        catch (OracleSchemaPreflightException exception) {
            throw exception;
        }
        catch (SQLException exception) {
            LOGGER.warn(
                    "Oracle schema preflight metadata read failed (vendorCode={}, sqlState={}).",
                    exception.getErrorCode(), exception.getSQLState());
            throw failure(OracleSchemaPreflightFailure.METADATA_UNAVAILABLE);
        }
        catch (RuntimeException exception) {
            // JDBC exception text may contain endpoints. Do not retain it as a cause.
            throw failure(OracleSchemaPreflightFailure.METADATA_UNAVAILABLE);
        }
        return new VerifiedBinding(
                new BindingResult(
                        requiredRole,
                        binding.schemaSnapshotUuid(),
                        pinned.verifiedFingerprint(),
                        snapshot.input().columns().size(),
                        snapshot.input().constraints().size()),
                snapshot.input().columns(),
                liveColumns);
    }

    private PinnedBinding verifyPinnedSnapshot(
            DatasetBinding binding,
            DatasetRole requiredRole,
            ExpectedSnapshot snapshot) {
        validateContract(binding, requiredRole, snapshot);
        String calculated;
        try {
            calculated = fingerprint.calculate(snapshot.input());
        }
        catch (RuntimeException exception) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }
        if (!constantTimeEquals(binding.schemaSnapshotFingerprint(), calculated)) {
            throw failure(OracleSchemaPreflightFailure.SNAPSHOT_FINGERPRINT_MISMATCH);
        }
        validateVerifiableSnapshot(snapshot.input(), requiredRole);
        return new PinnedBinding(calculated, snapshot.input().columns());
    }

    private void validateContract(
            DatasetBinding binding,
            DatasetRole requiredRole,
            ExpectedSnapshot snapshot) {
        if (binding == null || binding.role() != requiredRole
                || binding.databaseType() != PilotRuntimePlan.DatabaseType.ORACLE
                || binding.dataObjectType() != PilotRuntimePlan.DataObjectType.TABLE
                || binding.schemaSnapshotUuid() == null
                || !binding.schemaSnapshotUuid().equals(snapshot.schemaSnapshotUuid())
                || binding.schemaSnapshotFingerprint() == null
                || !HASH.matcher(binding.schemaSnapshotFingerprint()).matches()
                || !identifier(binding.owner()) || !identifier(binding.objectName())
                || snapshot.input() == null) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }
    }

    private void validateVerifiableSnapshot(
            SchemaFingerprintInput input, DatasetRole role) {
        if (input.engineVersion() == null || input.engineVersion().isBlank()
                || input.propertyVersion() < 1 || input.properties() == null
                || !input.properties().isObject() || input.columns().isEmpty()) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }
        Set<String> columnReferences = new java.util.HashSet<>();
        Set<Integer> ordinals = new java.util.HashSet<>();
        for (Column column : input.columns()) {
            if (column == null || !identifier(column.reference())
                    || !column.reference().equals(column.name())
                    || column.ordinal() < 1 || column.producerType() == null
                    || column.producerType().isBlank() || column.canonicalType() == null
                    || column.canonicalType().isBlank()
                    || !columnReferences.add(column.reference())
                    || !ordinals.add(column.ordinal())) {
                throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
            }
        }
        Set<String> constraintReferences = new java.util.HashSet<>();
        for (Constraint constraint : input.constraints()) {
            if (constraint == null || !identifier(constraint.externalReference())
                    || !constraint.externalReference().equals(constraint.name())
                    || constraint.detailVersion() < 1 || constraint.details() == null
                    || !constraint.details().isObject()
                    || !constraintReferences.add(constraint.externalReference())) {
                throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
            }
            boolean knownType = Set.of("PK", "UK", "FK", "CHECK")
                    .contains(constraint.type());
            if (!knownType || role == DatasetRole.TARGET
                    && (!constraint.details().isEmpty()
                        || !(constraint.type().equals("PK")
                            || constraint.type().equals("UK")))) {
                throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
            }
        }
    }

    private void verifyOracle19c(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName();
        if (product == null || !product.toUpperCase(Locale.ROOT).contains("ORACLE")
                || metadata.getDatabaseMajorVersion() != 19) {
            throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
        }
    }

    private List<LiveColumn> readColumns(
            Connection connection, String owner, String objectName) throws SQLException {
        List<LiveColumn> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_SQL)) {
            statement.setString(1, owner);
            statement.setString(2, objectName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(new LiveColumn(
                            rows.getString("COLUMN_NAME"),
                            rows.getString("DATA_TYPE"),
                            nullableInteger(rows, "DATA_PRECISION"),
                            nullableInteger(rows, "NUMERIC_SCALE"),
                            nullableLong(rows, "DECLARED_LENGTH"),
                            nullableInteger(rows, "TIME_PRECISION"),
                            "Y".equals(rows.getString("NULLABLE")),
                            trimToNull(rows.getString("DATA_DEFAULT")),
                            "YES".equals(rows.getString("VIRTUAL_COLUMN")),
                            "YES".equals(rows.getString("IDENTITY_COLUMN")),
                            "YES".equals(rows.getString("DEFAULT_ON_NULL")),
                            rows.getInt("COLUMN_ID")));
                }
            }
        }
        if (columns.isEmpty()) {
            throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
        }
        return List.copyOf(columns);
    }

    private List<LiveConstraint> readConstraints(
            Connection connection, String owner, String objectName) throws SQLException {
        Map<String, ConstraintBuilder> constraints = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(CONSTRAINT_SQL)) {
            statement.setString(1, owner);
            statement.setString(2, objectName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("CONSTRAINT_NAME");
                    String type = constraintType(rows.getString("CONSTRAINT_TYPE"));
                    boolean enabled = "ENABLED".equals(rows.getString("STATUS"));
                    ConstraintBuilder builder = constraints.computeIfAbsent(
                            name, ignored -> new ConstraintBuilder(name, type, enabled));
                    String column = rows.getString("COLUMN_NAME");
                    if (column != null) {
                        builder.columns.add(column);
                    }
                }
            }
        }
        return constraints.values().stream().map(ConstraintBuilder::build).toList();
    }

    private void compareColumns(List<Column> expected, List<LiveColumn> actual) {
        if (expected.size() != actual.size()) {
            throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
        }
        for (int index = 0; index < expected.size(); index++) {
            Column left = expected.get(index);
            LiveColumn right = actual.get(index);
            if (!left.reference().equals(right.name())
                    || left.ordinal() != right.ordinal()
                    || !oracleBaseType(left.producerType()).equals(
                            oracleBaseType(right.producerType()))
                    || !Objects.equals(left.precision(), right.precision())
                    || !Objects.equals(left.scale(), right.scale())
                    || !Objects.equals(left.length(), right.length())
                    || !Objects.equals(left.timePrecision(), right.timePrecision())
                    || left.nullable() != right.nullable()
                    || !Objects.equals(trimToNull(left.defaultExpression()),
                            right.defaultExpression())) {
                throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
            }
        }
    }

    private void compareConstraints(
            List<Constraint> expected, List<LiveConstraint> actual) {
        if (expected.size() != actual.size()) {
            throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
        }
        Map<String, LiveConstraint> actualByName = new LinkedHashMap<>();
        actual.forEach(constraint -> actualByName.put(constraint.name(), constraint));
        for (Constraint constraint : expected) {
            LiveConstraint live = actualByName.get(constraint.externalReference());
            if (live == null || !constraint.type().equals(live.type())
                    || constraint.enabled() != live.enabled()
                    || !constraint.columnReferences().equals(live.columns())) {
                throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
            }
        }
    }

    private void validateMappings(
            MappingExecutionContract plan,
            List<Column> expectedSourceColumns,
            VerifiedBinding target) {
        Map<String, Column> sourceColumns = byReference(expectedSourceColumns);
        Map<String, Column> targetColumns = byReference(target.expectedColumns());
        Set<String> mappedTargets = new java.util.HashSet<>();
        Set<String> mappedSources = new java.util.HashSet<>();
        for (PilotRuntimePlan.DirectColumnMapping mapping : plan.columnMappings()) {
            if (mapping == null || !mappedSources.add(mapping.sourceColumn())
                    || !mappedTargets.add(mapping.targetColumn())) {
                throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
            }
            Column sourceColumn = sourceColumns.get(mapping.sourceColumn());
            Column targetColumn = targetColumns.get(mapping.targetColumn());
            if (sourceColumn == null || targetColumn == null) {
                throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
            }
            requireCompatible(sourceColumn, targetColumn);
        }
        if (mappedTargets.isEmpty()) {
            throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
        }

        Map<String, LiveColumn> liveTargets = liveByName(target.liveColumns());
        for (Column targetColumn : target.expectedColumns()) {
            if (mappedTargets.contains(targetColumn.reference())) {
                continue;
            }
            LiveColumn live = liveTargets.get(targetColumn.reference());
            if (live == null) {
                throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
            }
            if (!live.nullable()) {
                if (live.defaultExpression() == null) {
                    throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
                }
                if (!SAFE_LITERAL_DEFAULT.matcher(live.defaultExpression()).matches()) {
                    throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
                }
            }
        }
    }

    private void requireCompatible(Column source, Column target) {
        String canonical = source.canonicalType().toUpperCase(Locale.ROOT);
        if (!canonical.equals(target.canonicalType().toUpperCase(Locale.ROOT))
                || !SUPPORTED_CANONICAL_TYPES.contains(canonical)
                || source.nullable() && !target.nullable()) {
            throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
        }
        String sourceType = oracleBaseType(source.producerType());
        String targetType = oracleBaseType(target.producerType());
        boolean compatible = switch (canonical) {
            case "INTEGER" -> sourceType.equals("NUMBER")
                    && targetType.equals("NUMBER")
                    && Objects.equals(source.scale(), 0)
                    && Objects.equals(target.scale(), 0)
                    && source.precision() != null
                    && capacityAtLeast(target.precision(), source.precision());
            case "DECIMAL" -> sourceType.equals("NUMBER")
                    && targetType.equals("NUMBER")
                    && (source.scale() == null
                        ? target.scale() == null
                        : source.scale() >= 0
                            && Objects.equals(source.scale(), target.scale()))
                    && (source.precision() == null
                        ? target.precision() == null
                        : capacityAtLeast(target.precision(), source.precision()));
            case "STRING" -> sourceType.equals("VARCHAR2")
                    && targetType.equals("VARCHAR2")
                    && source.length() != null
                    && capacityAtLeast(target.length(), source.length());
            case "TIMESTAMP" -> sourceType.equals("TIMESTAMP")
                    && sourceType.equals(targetType)
                    && source.timePrecision() != null
                    && source.timePrecision() <= 6
                    && capacityAtLeast(target.timePrecision(), source.timePrecision());
            default -> false;
        };
        if (!compatible) {
            throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
        }
    }

    private void verifyTargetWriteSafety(
            Connection connection, DatasetBinding binding, VerifiedBinding target) {
        if (target.liveColumns().stream().anyMatch(column ->
                column.virtual() || column.identity() || column.defaultOnNull())) {
            throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
        }
        try (PreparedStatement statement = connection.prepareStatement(ENABLED_TRIGGER_SQL)) {
            statement.setString(1, binding.owner());
            statement.setString(2, binding.objectName());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
                }
            }
        }
        catch (OracleSchemaPreflightException exception) {
            throw exception;
        }
        catch (SQLException | RuntimeException exception) {
            throw failure(OracleSchemaPreflightFailure.METADATA_UNAVAILABLE);
        }
    }

    private Map<String, Column> byReference(List<Column> columns) {
        Map<String, Column> result = new LinkedHashMap<>();
        for (Column column : columns) {
            if (result.put(column.reference(), column) != null) {
                throw failure(OracleSchemaPreflightFailure.INVALID_CONTRACT);
            }
        }
        return result;
    }

    private Map<String, LiveColumn> liveByName(List<LiveColumn> columns) {
        Map<String, LiveColumn> result = new LinkedHashMap<>();
        for (LiveColumn column : columns) {
            if (result.put(column.name(), column) != null) {
                throw failure(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
            }
        }
        return result;
    }

    private boolean capacityAtLeast(Integer target, Integer source) {
        return source == null ? target == null : target != null && target >= source;
    }

    private boolean capacityAtLeast(Long target, Long source) {
        return source == null ? target == null : target != null && target >= source;
    }

    private String constraintType(String oracleType) {
        return switch (oracleType) {
            case "P" -> "PK";
            case "U" -> "UK";
            case "R" -> "FK";
            case "C" -> "CHECK";
            default -> throw failure(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA);
        };
    }

    private String oracleBaseType(String producerType) {
        if (producerType == null) {
            return "";
        }
        return producerType.strip().toUpperCase(Locale.ROOT)
                .replaceAll("\\s*\\([^)]*\\)", "")
                .replaceAll("\\s+", " ");
    }

    private Integer nullableInteger(ResultSet rows, String field) throws SQLException {
        int value = rows.getInt(field);
        return rows.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet rows, String field) throws SQLException {
        long value = rows.getLong(field);
        return rows.wasNull() ? null : value;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < expected.length(); index++) {
            difference |= expected.charAt(index) ^ actual.charAt(index);
        }
        return difference == 0;
    }

    private boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OracleSchemaPreflightException failure(OracleSchemaPreflightFailure failure) {
        return new OracleSchemaPreflightException(failure);
    }

    record ExpectedSnapshot(UUID schemaSnapshotUuid, SchemaFingerprintInput input) {
    }

    record PreflightResult(BindingResult source, BindingResult target) {
    }

    record BindingResult(
            DatasetRole role,
            UUID schemaSnapshotUuid,
            String verifiedFingerprint,
            int columnCount,
            int constraintCount) {
    }

    private record LiveColumn(
            String name,
            String producerType,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision,
            boolean nullable,
            String defaultExpression,
            boolean virtual,
            boolean identity,
            boolean defaultOnNull,
            int ordinal) {
    }

    private record VerifiedBinding(
            BindingResult result, List<Column> expectedColumns, List<LiveColumn> liveColumns) {
    }

    private record PinnedBinding(
            String verifiedFingerprint, List<Column> expectedColumns) {
    }

    private record LiveConstraint(
            String name, String type, boolean enabled, List<String> columns) {
    }

    private static final class ConstraintBuilder {
        private final String name;
        private final String type;
        private final boolean enabled;
        private final List<String> columns = new ArrayList<>();

        private ConstraintBuilder(String name, String type, boolean enabled) {
            this.name = name;
            this.type = type;
            this.enabled = enabled;
        }

        private LiveConstraint build() {
            return new LiveConstraint(name, type, enabled, List.copyOf(columns));
        }
    }
}

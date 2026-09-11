package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Constraint;
import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;

class JdbcOracleSchemaPreflightTest {

    private static final String OWNER = "INNOVA_ODI";
    private static final String SOURCE_TABLE = "HAKEDIS_TIPI";
    private static final String TARGET_TABLE = "STG_HAKEDIS_TIPI";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaFingerprint fingerprint = new SchemaFingerprint(objectMapper);
    private final JdbcOracleSchemaPreflight preflight =
            new JdbcOracleSchemaPreflight(objectMapper);

    @Test
    void verifiesBothPinnedSnapshotsAgainstLiveOracleUsingSelectsOnly() {
        SchemaFingerprintInput sourceInput = snapshotInput(
                List.of(numberColumn("ID", 1), stringColumn("NAME", 2)),
                List.of(primaryKey("PK_HAKEDIS_TIPI", "ID")));
        SchemaFingerprintInput targetInput = snapshotInput(
                List.of(numberColumn("ID", 1), stringColumn("NAME", 2)),
                List.of(primaryKey("PK_STG_HAKEDIS_TIPI", "ID")));
        Inputs inputs = inputs(sourceInput, targetInput);
        FakeOracle source = new FakeOracle(
                columnRows(sourceInput.columns()), constraintRows(sourceInput.constraints()));
        FakeOracle target = new FakeOracle(
                columnRows(targetInput.columns()), constraintRows(targetInput.constraints()));

        var result = preflight.verify(
                inputs.plan,
                source.connection(), inputs.sourceSnapshot,
                target.connection(), inputs.targetSnapshot);

        assertEquals(DatasetRole.SOURCE, result.source().role());
        assertEquals(inputs.plan.source().schemaSnapshotFingerprint(),
                result.source().verifiedFingerprint());
        assertEquals(2, result.source().columnCount());
        assertEquals(1, result.target().constraintCount());
        assertEquals(List.of(OWNER, SOURCE_TABLE), source.boundValues);
        assertEquals(List.of(
                OWNER, TARGET_TABLE, OWNER, TARGET_TABLE, OWNER, TARGET_TABLE),
                target.boundValues);
        assertTrue(source.sql.stream().allMatch(sql -> sql.stripLeading().startsWith("SELECT")));
        assertTrue(target.sql.stream().allMatch(sql -> sql.stripLeading().startsWith("SELECT")));
        assertTrue(source.connectionMethods.stream().noneMatch(this::isStateChangingMethod));
        assertTrue(target.connectionMethods.stream().noneMatch(this::isStateChangingMethod));
    }

    @Test
    void lockedTargetApiReattestsOnTheSameConnectionAndCatchesPostPreflightDrift() {
        SchemaFingerprintInput input = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(input, input);
        FakeOracle source = new FakeOracle(columnRows(input.columns()), List.of());
        List<Map<String, Object>> mutableTargetColumns = new ArrayList<>(
                columnRows(input.columns()));
        FakeOracle target = new FakeOracle(mutableTargetColumns, List.of());
        Connection lockedTargetConnection = target.connection();

        preflight.verify(
                inputs.plan,
                source.connection(), inputs.sourceSnapshot,
                lockedTargetConnection, inputs.targetSnapshot);
        int readsBeforeLockedAttestation = target.sql.size();

        var attestation = preflight.verifyLockedTarget(
                inputs.plan,
                lockedTargetConnection,
                inputs.sourceSnapshot,
                inputs.targetSnapshot);

        assertEquals(DatasetRole.TARGET, attestation.role());
        assertEquals(readsBeforeLockedAttestation + 3, target.sql.size());
        assertTrue(target.sql.stream().allMatch(sql -> sql.stripLeading().startsWith("SELECT")));
        assertTrue(target.connectionMethods.stream().noneMatch(this::isStateChangingMethod));

        mutableTargetColumns.set(0, columnRow(stringColumn("ID", 1)));
        OracleSchemaPreflightException drift = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verifyLockedTarget(
                        inputs.plan,
                        lockedTargetConnection,
                        inputs.sourceSnapshot,
                        inputs.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT, drift.failure());
    }

    @Test
    void lockedTargetApiCatchesTriggerCreatedAfterInitialPreflight() {
        SchemaFingerprintInput input = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(input, input);
        List<Map<String, Object>> mutableTriggers = new ArrayList<>();
        FakeOracle target = new FakeOracle(
                columnRows(input.columns()), List.of(), mutableTriggers,
                "Oracle Database", 19, null);
        Connection lockedTargetConnection = target.connection();

        preflight.verify(
                inputs.plan,
                new FakeOracle(columnRows(input.columns()), List.of()).connection(),
                inputs.sourceSnapshot,
                lockedTargetConnection,
                inputs.targetSnapshot);
        mutableTriggers.add(Map.of("TRIGGER_NAME", "BI_STG_TABLE"));

        OracleSchemaPreflightException error = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verifyLockedTarget(
                        inputs.plan,
                        lockedTargetConnection,
                        inputs.sourceSnapshot,
                        inputs.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA, error.failure());
    }

    @Test
    void lockedTargetApiProvesSourceSnapshotHashBeforeAnyTargetCatalogRead() {
        SchemaFingerprintInput input = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(input, input);
        SchemaFingerprintInput tamperedSource = snapshotInput(
                List.of(stringColumn("ID", 1)), List.of());
        FakeOracle untouchedTarget = new FakeOracle(List.of(), List.of());

        OracleSchemaPreflightException error = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verifyLockedTarget(
                        inputs.plan,
                        untouchedTarget.connection(),
                        new ExpectedSnapshot(
                                inputs.sourceSnapshot.schemaSnapshotUuid(), tamperedSource),
                        inputs.targetSnapshot));

        assertEquals(OracleSchemaPreflightFailure.SNAPSHOT_FINGERPRINT_MISMATCH,
                error.failure());
        assertTrue(untouchedTarget.connectionMethods.isEmpty());
        assertTrue(untouchedTarget.sql.isEmpty());
    }

    @Test
    void lockedTargetApiRejectsAutoCommitConnectionBeforeCatalogReads() {
        SchemaFingerprintInput input = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(input, input);
        List<String> invoked = new ArrayList<>();
        Connection autoCommitConnection = proxy(Connection.class, (proxy, method, arguments) -> {
            invoked.add(method.getName());
            if (method.getName().equals("getAutoCommit")) {
                return true;
            }
            return defaultValue(method.getReturnType());
        });

        OracleSchemaPreflightException error = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verifyLockedTarget(
                        inputs.plan,
                        autoCommitConnection,
                        inputs.sourceSnapshot,
                        inputs.targetSnapshot));

        assertEquals(OracleSchemaPreflightFailure.INVALID_CONTRACT, error.failure());
        assertEquals(List.of("isClosed", "getAutoCommit"), invoked);
    }

    @Test
    void acceptsIdenticalUnconstrainedOracleNumberColumns() {
        Column unconstrainedNumber = new Column(
                "ID", "NUMBER", "DECIMAL", 1, null, null, null, null,
                false, null, "ID");
        SchemaFingerprintInput input = snapshotInput(
                List.of(unconstrainedNumber), List.of());
        Inputs inputs = inputs(input, input);

        var result = preflight.verify(
                inputs.plan,
                new FakeOracle(columnRows(input.columns()), List.of()).connection(),
                inputs.sourceSnapshot,
                new FakeOracle(columnRows(input.columns()), List.of()).connection(),
                inputs.targetSnapshot);

        assertEquals(1, result.source().columnCount());
        assertEquals(1, result.target().columnCount());
    }

    @Test
    void rejectsSnapshotBodyThatDoesNotHashToPinnedFingerprintBeforeJdbcAccess() {
        SchemaFingerprintInput sourceInput = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(sourceInput, sourceInput);
        SchemaFingerprintInput tampered = snapshotInput(
                List.of(stringColumn("ID", 1)), List.of());
        FakeOracle source = new FakeOracle(List.of(), List.of());

        OracleSchemaPreflightException error = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        inputs.plan,
                        source.connection(), new ExpectedSnapshot(
                                inputs.sourceSnapshot.schemaSnapshotUuid(), tampered),
                        new FakeOracle(List.of(), List.of()).connection(),
                        inputs.targetSnapshot));

        assertEquals(OracleSchemaPreflightFailure.SNAPSHOT_FINGERPRINT_MISMATCH,
                error.failure());
        assertTrue(source.connectionMethods.isEmpty());
        assertTrue(source.sql.isEmpty());
    }

    @Test
    void rejectsLiveColumnTypeNullabilityAndCardinalityDrift() {
        SchemaFingerprintInput expected = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(expected, expected);

        for (List<Map<String, Object>> drifted : List.of(
                List.of(columnRow(stringColumn("ID", 1))),
                List.of(columnRow(new Column(
                        "ID", "NUMBER(19)", "INTEGER", 1, 19, 0, null, null,
                        true, null, "ID"))),
                List.of(columnRow(numberColumn("ID", 1)),
                        columnRow(stringColumn("EXTRA", 2))))) {
            OracleSchemaPreflightException error = assertThrows(
                    OracleSchemaPreflightException.class,
                    () -> preflight.verify(
                            inputs.plan,
                            new FakeOracle(drifted, List.of()).connection(),
                            inputs.sourceSnapshot,
                            new FakeOracle(columnRows(expected.columns()), List.of()).connection(),
                            inputs.targetSnapshot));
            assertEquals(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT, error.failure());
        }
    }

    @Test
    void rejectsLiveConstraintDriftAndUnsupportedConstraintContracts() {
        Constraint primaryKey = primaryKey("PK_TABLE", "ID");
        SchemaFingerprintInput expected = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of(primaryKey));
        Inputs inputs = inputs(expected, expected);
        List<Map<String, Object>> wrongConstraint = constraintRows(List.of(
                new Constraint(
                        "UK_TABLE", "UK", true, 1, objectMapper.createObjectNode(),
                        "UK_TABLE", List.of("ID"))));

        OracleSchemaPreflightException drift = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        inputs.plan,
                        new FakeOracle(columnRows(expected.columns()),
                                constraintRows(expected.constraints())).connection(),
                        inputs.sourceSnapshot,
                        new FakeOracle(columnRows(expected.columns()), wrongConstraint).connection(),
                        inputs.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT, drift.failure());

        Constraint foreignKey = new Constraint(
                "FK_TABLE", "FK", true, 1, objectMapper.createObjectNode(),
                "FK_TABLE", List.of("ID"));
        SchemaFingerprintInput unsupported = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of(foreignKey));
        Inputs unsupportedInputs = inputs(expected, unsupported);
        OracleSchemaPreflightException unsupportedError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        unsupportedInputs.plan,
                        new FakeOracle(columnRows(expected.columns()), List.of()).connection(),
                        unsupportedInputs.sourceSnapshot,
                        new FakeOracle(columnRows(unsupported.columns()),
                                constraintRows(unsupported.constraints())).connection(),
                        unsupportedInputs.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA,
                unsupportedError.failure());
    }

    @Test
    void rejectsIncompatibleMappingAndUnsafeUnmappedRequiredTargetColumn() {
        SchemaFingerprintInput source = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Column narrowStringTarget = new Column(
                "ID", "VARCHAR2(10)", "STRING", 1, null, null, 10L, null,
                false, null, "ID");
        SchemaFingerprintInput incompatibleTarget = snapshotInput(
                List.of(narrowStringTarget), List.of());
        Inputs incompatible = inputs(source, incompatibleTarget);

        OracleSchemaPreflightException typeError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        incompatible.plan,
                        new FakeOracle(columnRows(source.columns()), List.of()).connection(),
                        incompatible.sourceSnapshot,
                        new FakeOracle(columnRows(incompatibleTarget.columns()), List.of())
                                .connection(),
                        incompatible.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA, typeError.failure());

        Column nullableSourceColumn = new Column(
                "ID", "NUMBER(19)", "INTEGER", 1, 19, 0, null, null,
                true, null, "ID");
        Inputs nullableToRequired = inputs(
                snapshotInput(List.of(nullableSourceColumn), List.of()), source);
        OracleSchemaPreflightException nullabilityError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        nullableToRequired.plan,
                        new FakeOracle(columnRows(
                                nullableToRequired.sourceSnapshot.input().columns()), List.of())
                                .connection(),
                        nullableToRequired.sourceSnapshot,
                        new FakeOracle(columnRows(source.columns()), List.of()).connection(),
                        nullableToRequired.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA,
                nullabilityError.failure());

        Column requiredUnmapped = new Column(
                "REQUIRED_VALUE", "VARCHAR2(20)", "STRING", 2,
                null, null, 20L, null, false, null, "REQUIRED_VALUE");
        SchemaFingerprintInput unsafeTarget = snapshotInput(
                List.of(numberColumn("ID", 1), requiredUnmapped), List.of());
        Inputs unsafe = inputs(source, unsafeTarget);
        OracleSchemaPreflightException requiredError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        unsafe.plan,
                        new FakeOracle(columnRows(source.columns()), List.of()).connection(),
                        unsafe.sourceSnapshot,
                        new FakeOracle(columnRows(unsafeTarget.columns()), List.of()).connection(),
                        unsafe.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA, requiredError.failure());

        Column sequenceDefault = new Column(
                "REQUIRED_VALUE", "VARCHAR2(20)", "STRING", 2,
                null, null, 20L, null, false, "APP_SEQ.NEXTVAL", "REQUIRED_VALUE");
        SchemaFingerprintInput unsafeDefaultTarget = snapshotInput(
                List.of(numberColumn("ID", 1), sequenceDefault), List.of());
        Inputs unsafeDefault = inputs(source, unsafeDefaultTarget);
        OracleSchemaPreflightException defaultError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        unsafeDefault.plan,
                        new FakeOracle(columnRows(source.columns()), List.of()).connection(),
                        unsafeDefault.sourceSnapshot,
                        new FakeOracle(columnRows(unsafeDefaultTarget.columns()), List.of())
                                .connection(),
                        unsafeDefault.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA, defaultError.failure());

        Column literalDefault = new Column(
                "REQUIRED_VALUE", "VARCHAR2(20)", "STRING", 2,
                null, null, 20L, null, false, "'READY'", "REQUIRED_VALUE");
        SchemaFingerprintInput safeDefaultTarget = snapshotInput(
                List.of(numberColumn("ID", 1), literalDefault), List.of());
        Inputs safeDefault = inputs(source, safeDefaultTarget);
        var safeResult = preflight.verify(
                safeDefault.plan,
                new FakeOracle(columnRows(source.columns()), List.of()).connection(),
                safeDefault.sourceSnapshot,
                new FakeOracle(columnRows(safeDefaultTarget.columns()), List.of()).connection(),
                safeDefault.targetSnapshot);
        assertEquals(2, safeResult.target().columnCount());
    }

    @Test
    void rejectsGeneratedTargetColumnsAndEnabledTriggers() {
        SchemaFingerprintInput expected = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(expected, expected);
        for (String generatedField : List.of(
                "VIRTUAL_COLUMN", "IDENTITY_COLUMN", "DEFAULT_ON_NULL")) {
            List<Map<String, Object>> generatedColumns = new ArrayList<>();
            for (Map<String, Object> original : columnRows(expected.columns())) {
                Map<String, Object> changed = new LinkedHashMap<>(original);
                changed.put(generatedField, "YES");
                generatedColumns.add(changed);
            }
            OracleSchemaPreflightException generated = assertThrows(
                    OracleSchemaPreflightException.class,
                    () -> preflight.verify(
                            inputs.plan,
                            new FakeOracle(columnRows(expected.columns()), List.of()).connection(),
                            inputs.sourceSnapshot,
                            new FakeOracle(generatedColumns, List.of()).connection(),
                            inputs.targetSnapshot));
            assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA, generated.failure());
        }

        Map<String, Object> trigger = Map.of("TRIGGER_NAME", "BI_STG_TABLE");
        OracleSchemaPreflightException triggerError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        inputs.plan,
                        new FakeOracle(columnRows(expected.columns()), List.of()).connection(),
                        inputs.sourceSnapshot,
                        new FakeOracle(
                                columnRows(expected.columns()), List.of(), List.of(trigger),
                                "Oracle Database", 19, null).connection(),
                        inputs.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA, triggerError.failure());
    }

    @Test
    void rejectsWrongSnapshotIdentityRoleAndNonOracle19BeforeCatalogReads() {
        SchemaFingerprintInput expected = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(expected, expected);
        ExpectedSnapshot wrongSnapshot = new ExpectedSnapshot(UUID.randomUUID(), expected);
        FakeOracle untouched = new FakeOracle(List.of(), List.of());

        OracleSchemaPreflightException identityError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        inputs.plan, untouched.connection(), wrongSnapshot,
                        new FakeOracle(List.of(), List.of()).connection(), inputs.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.INVALID_CONTRACT, identityError.failure());
        assertTrue(untouched.connectionMethods.isEmpty());

        FakeOracle nonOracle = new FakeOracle(
                columnRows(expected.columns()), List.of(), "PostgreSQL", 19, null);
        OracleSchemaPreflightException versionError = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        inputs.plan, nonOracle.connection(), inputs.sourceSnapshot,
                        new FakeOracle(columnRows(expected.columns()), List.of()).connection(),
                        inputs.targetSnapshot));
        assertEquals(OracleSchemaPreflightFailure.UNSUPPORTED_SCHEMA, versionError.failure());
        assertTrue(nonOracle.sql.isEmpty());
    }

    @Test
    void masksJdbcFailureDetailsAndDoesNotRetainSensitiveCause() {
        SchemaFingerprintInput expected = snapshotInput(
                List.of(numberColumn("ID", 1)), List.of());
        Inputs inputs = inputs(expected, expected);
        String sensitive = "jdbc:oracle:thin:@private-host password=do-not-leak";
        FakeOracle failing = new FakeOracle(
                List.of(), List.of(), "Oracle Database", 19, sensitive);

        OracleSchemaPreflightException error = assertThrows(
                OracleSchemaPreflightException.class,
                () -> preflight.verify(
                        inputs.plan, failing.connection(), inputs.sourceSnapshot,
                        new FakeOracle(columnRows(expected.columns()), List.of()).connection(),
                        inputs.targetSnapshot));

        assertEquals(OracleSchemaPreflightFailure.METADATA_UNAVAILABLE, error.failure());
        assertFalse(error.getMessage().contains(sensitive));
        assertNull(error.getCause());
    }

    private Inputs inputs(
            SchemaFingerprintInput sourceInput, SchemaFingerprintInput targetInput) {
        UUID sourceSnapshotUuid = UUID.randomUUID();
        UUID targetSnapshotUuid = UUID.randomUUID();
        DatasetBinding source = binding(
                DatasetRole.SOURCE, SOURCE_TABLE, sourceSnapshotUuid,
                fingerprint.calculate(sourceInput));
        DatasetBinding target = binding(
                DatasetRole.TARGET, TARGET_TABLE, targetSnapshotUuid,
                fingerprint.calculate(targetInput));
        PilotRuntimePlan plan = new PilotRuntimePlan(
                PilotRuntimePlan.CURRENT_VERSION,
                "a".repeat(64), "b".repeat(64), "c".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(),
                PilotRuntimePlan.MAXIMUM_SOURCE_ROWS,
                source, target,
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
        return new Inputs(
                plan,
                new ExpectedSnapshot(sourceSnapshotUuid, sourceInput),
                new ExpectedSnapshot(targetSnapshotUuid, targetInput));
    }

    private DatasetBinding binding(
            DatasetRole role, String objectName, UUID snapshotUuid, String hash) {
        return new DatasetBinding(
                role.name().toLowerCase(), role, DatabaseType.ORACLE, DataObjectType.TABLE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), snapshotUuid, 1, hash, "d".repeat(64), OWNER, objectName);
    }

    private SchemaFingerprintInput snapshotInput(
            List<Column> columns, List<Constraint> constraints) {
        return new SchemaFingerprintInput(
                "Oracle Database 19c", 1, objectMapper.createObjectNode(),
                columns, constraints);
    }

    private Column numberColumn(String name, int ordinal) {
        return new Column(
                name, "NUMBER(19)", "INTEGER", ordinal, 19, 0, null, null,
                false, null, name);
    }

    private Column stringColumn(String name, int ordinal) {
        return new Column(
                name, "VARCHAR2(100)", "STRING", ordinal, null, null, 100L, null,
                true, null, name);
    }

    private Constraint primaryKey(String name, String column) {
        return new Constraint(
                name, "PK", true, 1, objectMapper.createObjectNode(), name, List.of(column));
    }

    private List<Map<String, Object>> columnRows(List<Column> columns) {
        return columns.stream().map(this::columnRow).toList();
    }

    private Map<String, Object> columnRow(Column column) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("COLUMN_NAME", column.reference());
        row.put("DATA_TYPE", column.producerType().replaceAll("\\(.*", ""));
        row.put("DATA_PRECISION", column.precision());
        row.put("NUMERIC_SCALE", column.scale());
        row.put("DECLARED_LENGTH", column.length());
        row.put("TIME_PRECISION", column.timePrecision());
        row.put("NULLABLE", column.nullable() ? "Y" : "N");
        row.put("DATA_DEFAULT", column.defaultExpression());
        row.put("VIRTUAL_COLUMN", "NO");
        row.put("IDENTITY_COLUMN", "NO");
        row.put("DEFAULT_ON_NULL", "NO");
        row.put("COLUMN_ID", column.ordinal());
        return row;
    }

    private List<Map<String, Object>> constraintRows(List<Constraint> constraints) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Constraint constraint : constraints) {
            for (int index = 0; index < constraint.columnReferences().size(); index++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("CONSTRAINT_NAME", constraint.externalReference());
                row.put("CONSTRAINT_TYPE", switch (constraint.type()) {
                    case "PK" -> "P";
                    case "UK" -> "U";
                    case "FK" -> "R";
                    default -> "C";
                });
                row.put("STATUS", constraint.enabled() ? "ENABLED" : "DISABLED");
                row.put("COLUMN_NAME", constraint.columnReferences().get(index));
                row.put("POSITION", index + 1);
                rows.add(row);
            }
        }
        return rows;
    }

    private boolean isStateChangingMethod(String name) {
        return name.equals("commit") || name.equals("rollback")
                || name.equals("setAutoCommit") || name.equals("setReadOnly");
    }

    private record Inputs(
            PilotRuntimePlan plan,
            ExpectedSnapshot sourceSnapshot,
            ExpectedSnapshot targetSnapshot) {
    }

    private static final class FakeOracle {
        private final List<Map<String, Object>> columns;
        private final List<Map<String, Object>> constraints;
        private final List<Map<String, Object>> triggers;
        private final String product;
        private final int majorVersion;
        private final String failureDetail;
        private final List<String> sql = new ArrayList<>();
        private final List<String> boundValues = new ArrayList<>();
        private final List<String> connectionMethods = new ArrayList<>();

        private FakeOracle(
                List<Map<String, Object>> columns,
                List<Map<String, Object>> constraints) {
            this(columns, constraints, List.of(), "Oracle Database", 19, null);
        }

        private FakeOracle(
                List<Map<String, Object>> columns,
                List<Map<String, Object>> constraints,
                String product,
                int majorVersion,
                String failureDetail) {
            this(columns, constraints, List.of(), product, majorVersion, failureDetail);
        }

        private FakeOracle(
                List<Map<String, Object>> columns,
                List<Map<String, Object>> constraints,
                List<Map<String, Object>> triggers,
                String product,
                int majorVersion,
                String failureDetail) {
            this.columns = columns;
            this.constraints = constraints;
            this.triggers = triggers;
            this.product = product;
            this.majorVersion = majorVersion;
            this.failureDetail = failureDetail;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, arguments) -> {
                connectionMethods.add(method.getName());
                if (method.getName().equals("getMetaData")) {
                    return metadata();
                }
                if (method.getName().equals("prepareStatement")) {
                    if (failureDetail != null) {
                        throw new SQLException(failureDetail);
                    }
                    String statementSql = (String) arguments[0];
                    sql.add(statementSql);
                    List<Map<String, Object>> rows = statementSql.contains("all_tab_columns")
                            ? columns
                            : statementSql.contains("all_triggers") ? triggers : constraints;
                    return statement(rows);
                }
                return defaultValue(method.getReturnType());
            });
        }

        private DatabaseMetaData metadata() {
            return proxy(DatabaseMetaData.class, (proxy, method, arguments) -> {
                if (method.getName().equals("getDatabaseProductName")) {
                    return product;
                }
                if (method.getName().equals("getDatabaseMajorVersion")) {
                    return majorVersion;
                }
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement(List<Map<String, Object>> rows) {
            return proxy(PreparedStatement.class, (proxy, method, arguments) -> {
                if (method.getName().equals("setString")) {
                    boundValues.add((String) arguments[1]);
                    return null;
                }
                if (method.getName().equals("executeQuery")) {
                    return resultSet(rows);
                }
                return defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet(List<Map<String, Object>> rows) {
            Map<String, Object> state = new HashMap<>();
            state.put("index", -1);
            state.put("wasNull", false);
            return proxy(ResultSet.class, (proxy, method, arguments) -> {
                int index = (int) state.get("index");
                if (method.getName().equals("next")) {
                    int next = index + 1;
                    state.put("index", next);
                    return next < rows.size();
                }
                if (method.getName().equals("getString")) {
                    Object value = rows.get(index).get((String) arguments[0]);
                    state.put("wasNull", value == null);
                    return value == null ? null : value.toString();
                }
                if (method.getName().equals("getInt")) {
                    Object value = rows.get(index).get((String) arguments[0]);
                    state.put("wasNull", value == null);
                    return value == null ? 0 : ((Number) value).intValue();
                }
                if (method.getName().equals("getLong")) {
                    Object value = rows.get(index).get((String) arguments[0]);
                    state.put("wasNull", value == null);
                    return value == null ? 0L : ((Number) value).longValue();
                }
                if (method.getName().equals("wasNull")) {
                    return state.get("wasNull");
                }
                return defaultValue(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}

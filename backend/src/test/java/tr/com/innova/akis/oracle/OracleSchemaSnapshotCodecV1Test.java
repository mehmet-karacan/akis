package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.Column;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.Constraint;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.RawColumn;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.RawConstraint;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.RawConstraintColumn;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.SnapshotDefinition;

class OracleSchemaSnapshotCodecV1Test {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OracleSchemaSnapshotCodecV1 codec =
            new OracleSchemaSnapshotCodecV1(objectMapper);

    @Test
    void convertsSampleTipiFixtureToTheGoldenSnapshotContract() throws Exception {
        SnapshotDefinition result = codec.decode(
                " upstream_schema ", " sample_table ", shuffledColumns(), shuffledConstraints());

        assertEquals("ORACLE_19C", result.engineVersion());
        assertEquals(1, result.propertyVersion());
        assertEquals(
                objectMapper.readTree("""
                        {"codec":"ORACLE_SCHEMA_V1","objectType":"TABLE",
                         "owner":"UPSTREAM_SCHEMA","table":"SAMPLE_TABLE"}
                        """),
                result.properties());
        assertEquals(List.of(
                column("ID", "NUMBER(19,0)", "INTEGER", 1, 19, 0, null, null,
                        false, null),
                column("KOD", "VARCHAR2(50)", "STRING", 2, null, null, 50L, null,
                        false, null),
                column("TIP_ID", "NUMBER(19,0)", "INTEGER", 3, 19, 0, null, null,
                        false, null),
                column("SIRA_NO", "NUMBER(10,0)", "INTEGER", 4, 10, 0, null, null,
                        false, null),
                column("TUTAR", "NUMBER(18,2)", "DECIMAL", 5, 18, 2, null, null,
                        true, "0"),
                column("GUNCELLEME_ZAMANI", "TIMESTAMP(6)", "TIMESTAMP", 6,
                        null, null, null, 6, true, "SYSTIMESTAMP")), result.columns());

        assertEquals(List.of(
                constraint("CK_SAMPLE_TUTAR", "CHECK", true,
                        "{\"expression\":\"TUTAR >= 0\"}", List.of("TUTAR")),
                constraint("FK_SAMPLE_TABLE", "FK", true,
                        """
                                {"deferrability":"NOT_DEFERRABLE","deleteRule":"NO_ACTION",
                                 "referencedOwner":"UPSTREAM_SCHEMA","referencedTable":"SAMPLE_TIP",
                                 "referencedColumns":["ID","SIRA_NO"]}
                                """,
                        List.of("TIP_ID", "SIRA_NO")),
                constraint("PK_SAMPLE_TABLE", "PK", true, "{}", List.of("ID")),
                constraint("UK_SAMPLE_KOD", "UK", true, "{}", List.of("KOD"))),
                result.constraints());
    }

    @Test
    void preservesProviderNeutralObjectTypeInSnapshotProperties() {
        SnapshotDefinition view = codec.decode("APP", "V_SALES", OracleDiscoveryModels.VIEW,
                List.of(new RawColumn("ID", "NUMBER", 19, 0, null, null, 1, false, null)), List.of());
        SnapshotDefinition materialized = codec.decode("APP", "MV_SALES", OracleDiscoveryModels.MATERIALIZED_VIEW,
                List.of(new RawColumn("ID", "NUMBER", 19, 0, null, null, 1, false, null)), List.of());
        SnapshotDefinition synonym = codec.decode("APP", "S_SALES", OracleDiscoveryModels.SYNONYM,
                List.of(new RawColumn("ID", "NUMBER", 19, 0, null, null, 1, false, null)), List.of());

        assertEquals("VIEW", view.properties().path("objectType").asText());
        assertEquals("MATERIALIZED_VIEW", materialized.properties().path("objectType").asText());
        assertEquals("SYNONYM", synonym.properties().path("objectType").asText());
        assertThrows(OracleSchemaSnapshotCodecException.class, () -> codec.decode("APP", "X", "SEQUENCE",
                List.of(new RawColumn("ID", "NUMBER", 19, 0, null, null, 1, false, null)), List.of()));
    }

    @Test
    void outputIsIndependentOfDictionaryRowArrivalOrder() {
        SnapshotDefinition shuffled = codec.decode(
                "UPSTREAM_SCHEMA", "SAMPLE_TABLE", shuffledColumns(), shuffledConstraints());
        SnapshotDefinition ordered = codec.decode(
                "UPSTREAM_SCHEMA", "SAMPLE_TABLE",
                List.of(
                        number("ID", 19, 0, 1),
                        varchar("KOD", 50, 2),
                        number("TIP_ID", 19, 0, 3),
                        number("SIRA_NO", 10, 0, 4),
                        number("TUTAR", 18, 2, 5, true, "0"),
                        timestamp("GUNCELLEME_ZAMANI", null, 6, true, "SYSTIMESTAMP")),
                List.of(
                        checkConstraint(), foreignKeyConstraint(), primaryKeyConstraint(),
                        uniqueConstraint()));

        assertEquals(ordered, shuffled);
    }

    @Test
    void preservesBareNumberAndUsesDecimalWithoutInventingPrecision() {
        Column result = codec.decode(
                "UPSTREAM_SCHEMA", "VALUES_TABLE",
                List.of(number("VALUE", null, null, 1)), List.of()).columns().getFirst();

        assertEquals("NUMBER", result.producerType());
        assertEquals("DECIMAL", result.canonicalType());
        assertNull(result.precision());
        assertNull(result.scale());
    }

    @Test
    void decodesClobAsAnUnboundedTransferableStringColumn() {
        Column result = codec.decode(
                "UPSTREAM_SCHEMA", "VALUES_TABLE",
                List.of(new RawColumn("VALUE", "CLOB", null, null, null, null, 1, true, null)),
                List.of()).columns().getFirst();

        assertEquals("CLOB", result.producerType());
        assertEquals("STRING", result.canonicalType());
        assertNull(result.precision());
        assertNull(result.scale());
        assertNull(result.length());
        assertNull(result.timePrecision());
    }

    @Test
    void catalogsBlobDateAndFloatWithoutLosingTheirProducerTypes() {
        List<Column> result = codec.decode(
                "UPSTREAM_SCHEMA", "VALUES_TABLE",
                List.of(
                        new RawColumn("PAYLOAD", "BLOB", null, null, null, null, 1, true, null),
                        new RawColumn("BUSINESS_DATE", "DATE", null, null, null, null, 2, true, null),
                        new RawColumn("LIMIT_VALUE", "FLOAT", 126, null, null, null, 3, true, null)),
                List.of()).columns();

        assertEquals("BINARY", result.get(0).canonicalType());
        assertEquals("TIMESTAMP", result.get(1).canonicalType());
        assertEquals(0, result.get(1).timePrecision());
        assertEquals("FLOAT(126)", result.get(2).producerType());
        assertEquals("FLOAT64", result.get(2).canonicalType());
    }

    @Test
    void numberIntegerBoundaryIsNineteenDigits() {
        SnapshotDefinition result = codec.decode(
                "UPSTREAM_SCHEMA", "VALUES_TABLE",
                List.of(number("SMALL_VALUE", 19, 0, 1), number("BIG_VALUE", 20, 0, 2)),
                List.of());

        assertEquals("INTEGER", result.columns().get(0).canonicalType());
        assertEquals("DECIMAL", result.columns().get(1).canonicalType());
    }

    @Test
    void preservesSignedNumberScaleAcrossOracleBounds() {
        for (int scale : List.of(-84, -2, 127)) {
            Column result = codec.decode("TTBP", "SKY_DIT", List.of(number("N", 12, scale, 1)),
                    List.of()).columns().getFirst();
            assertEquals("NUMBER(12," + scale + ")", result.producerType());
            assertEquals("DECIMAL", result.canonicalType());
            assertEquals(scale, result.scale());
            assertEquals(12, result.precision());
        }
        for (int scale : List.of(-85, 128)) {
            assertCodecFailure(number("N", 12, scale, 1));
        }
        assertCodecFailure(number("N", 0, 0, 1));
        assertCodecFailure(number("N", 39, 0, 1));
    }

    @Test
    void catalogsAllFiftyDitColumnsWithProducerTypesAndOrdinalsIntact() {
        var result = codec.decode("TTBP", "SKY_DIT", skyDitColumns(), List.of());
        assertEquals(50, result.columns().size());
        assertEquals("NUMBER(12,-2)", result.columns().get(7).producerType());
        assertEquals(-2, result.columns().get(7).scale());
        assertEquals("FLOAT32", result.columns().get(10).canonicalType());
        assertEquals("BINARY_FLOAT", result.columns().get(10).producerType());
        assertEquals("FLOAT64", result.columns().get(11).canonicalType());
        assertEquals("BINARY_DOUBLE", result.columns().get(11).producerType());
        assertEquals("TIMESTAMP", result.columns().get(14).canonicalType());
        assertEquals(0, result.columns().get(14).timePrecision());
        assertEquals("TIMESTAMP(6) WITH TIME ZONE", result.columns().get(18).producerType());
        assertEquals("OFFSET_TIMESTAMP", result.columns().get(18).canonicalType());
        assertEquals(6, result.columns().get(18).timePrecision());
        assertEquals("CHAR(8)", result.columns().get(24).producerType());
        assertEquals("NCHAR(8)", result.columns().get(26).producerType());
        assertEquals("NVARCHAR2(120)", result.columns().get(27).producerType());
        assertEquals("NCLOB", result.columns().get(33).producerType());
        assertEquals("STRING", result.columns().get(33).canonicalType());
        assertEquals("RAW(32)", result.columns().get(35).producerType());
        assertEquals("BINARY", result.columns().get(35).canonicalType());
        assertEquals(32L, result.columns().get(35).length());
        for (int i = 0; i < 50; i++) {
            assertEquals(i + 1, result.columns().get(i).ordinal());
            assertEquals(skyDitColumns().get(i).name(), result.columns().get(i).name());
        }
    }

    @Test
    void catalogsTimestampNineWithoutPromisingRuntimePrecisionSupport() {
        Column result = codec.decode("TTBP", "SKY_DIT", List.of(new RawColumn(
                "T", "TIMESTAMP WITH TIME ZONE", null, null, null, 9, 1, true, null)),
                List.of()).columns().getFirst();
        assertEquals("TIMESTAMP(9) WITH TIME ZONE", result.producerType());
        assertEquals(9, result.timePrecision());
    }

    @Test
    void rejectsUnsupportedOrLossyColumnMetadata() {
        assertCodecFailure(new RawColumn(
                "VALUE", "NUMBER", 18, -85, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "NUMBER", null, 0, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "VARCHAR2", null, null, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "TIMESTAMP", null, null, null, 10, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "TIMESTAMP WITH TIME ZONE", null, null, null, -1,
                1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "CLOB", 1, null, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "CLOB", null, 1, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "CLOB", null, null, null, 6, 1, true, null));
        assertCodecFailure(new RawColumn("VALUE", "RAW", null, null, 0L, null, 1, true, null));
        assertCodecFailure(new RawColumn("VALUE", "RAW", null, null, 32768L, null, 1, true, null));
        assertCodecFailure(new RawColumn("VALUE", "CHAR", null, null, 2001L, null, 1, true, null));
        assertCodecFailure(new RawColumn("VALUE", "BINARY_FLOAT", 24, null, null, null, 1, true, null));
        assertCodecFailure(new RawColumn("VALUE", "XMLTYPE", null, null, null, null, 1, true, null));
    }

    static List<RawColumn> skyDitColumns() {
        // Exact ordinal/type/length/precision contract from dit_schema.COLUMNS.
        String[] names = {"ID", "NUM_SMALL", "NUM_INT", "NUM_BIG", "NUM_DECIMAL", "NUM_NEGATIVE",
                "NUM_PRECISE", "NUM_SCALED", "NUM_NULLABLE", "NUM_FLAG", "FLOAT32", "FLOAT64",
                "FLOAT32_NEG", "FLOAT64_SMALL", "DATE_VALUE", "DATE_NULLABLE", "TS_VALUE", "TS_NULLABLE",
                "TS_TZ", "TS_TZ_NULLABLE", "VARCHAR_ASCII", "VARCHAR_UNICODE", "VARCHAR_NULLABLE",
                "VARCHAR_EMPTY", "CHAR_CODE", "CHAR_PADDED", "NCHAR_CODE", "NVARCHAR_TEXT",
                "NVARCHAR_NULLABLE", "TEXT_SHORT", "TEXT_LONG", "TEXT_NULLABLE", "TEXT_EMPTY", "NTEXT",
                "NTEXT_NULLABLE", "RAW_BYTES", "RAW_NULLABLE", "BLOB_BYTES", "BLOB_NULLABLE", "BLOB_EMPTY",
                "PAYLOAD_JSON", "NUM_RATIO", "NUM_ZERO", "NUM_MAX38", "NUM_MIN38", "VARCHAR_SPACES",
                "VARCHAR_QUOTES", "DATE_BOUNDARY", "TS_FRACTION", "RAW_EDGE"};
        String[] types = {"NUMBER", "NUMBER", "NUMBER", "NUMBER", "NUMBER", "NUMBER", "NUMBER", "NUMBER",
                "NUMBER", "NUMBER", "BINARY_FLOAT", "BINARY_DOUBLE", "BINARY_FLOAT", "BINARY_DOUBLE",
                "DATE", "DATE", "TIMESTAMP", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE",
                "VARCHAR2", "VARCHAR2", "VARCHAR2", "VARCHAR2", "CHAR", "CHAR", "NCHAR", "NVARCHAR2", "NVARCHAR2",
                "CLOB", "CLOB", "CLOB", "CLOB", "NCLOB", "NCLOB", "RAW", "RAW", "BLOB", "BLOB", "BLOB", "CLOB",
                "NUMBER", "NUMBER", "NUMBER", "NUMBER", "VARCHAR2", "VARCHAR2", "DATE", "TIMESTAMP", "RAW"};
        int[] precision = {12, 5, 12, 38, 18, 18, 38, 12, 16, 1};
        int[] scale = {0, 0, 0, 0, 6, 4, 10, -2, 3, 0};
        long[] lengths = {80, 120, 80, 20, 8, 8, 8, 120, 120};
        var columns = new java.util.ArrayList<RawColumn>();
        for (int i = 0; i < 50; i++) {
            Integer p = i < 10 ? precision[i] : null;
            Integer s = i < 10 ? scale[i] : null;
            if (i >= 41 && i <= 44) {
                p = i == 41 ? 20 : i == 42 ? 10 : 38;
                s = i == 41 ? 9 : i == 42 ? 2 : 0;
            }
            Long length = null;
            if (i >= 20 && i <= 28) length = lengths[i - 20];
            if (i == 35 || i == 36) length = 32L;
            if (i == 45) length = 40L;
            if (i == 46) length = 80L;
            if (i == 49) length = 16L;
            columns.add(new RawColumn(names[i], types[i], p, s, length,
                    types[i].startsWith("TIMESTAMP") ? 6 : null, i + 1, i == 23 || names[i].endsWith("NULLABLE"), null));
        }
        return List.copyOf(columns);
    }

    @Test
    void rejectsMalformedConstraintPositionsAndForeignKeyDetails() {
        List<RawColumn> columns = List.of(
                number("TIP_ID", 19, 0, 1), number("SIRA_NO", 10, 0, 2));
        RawConstraint duplicatePosition = new RawConstraint(
                "FK_TEST", "R", "ENABLED", "UPSTREAM_SCHEMA", "TIP", "NO ACTION",
                "NOT DEFERRABLE", null,
                List.of(
                        new RawConstraintColumn("TIP_ID", 1, "ID"),
                        new RawConstraintColumn("SIRA_NO", 1, "SIRA_NO")));
        RawConstraint missingReferencedColumn = new RawConstraint(
                "FK_TEST", "R", "ENABLED", "UPSTREAM_SCHEMA", "TIP", "NO ACTION",
                "NOT DEFERRABLE", null,
                List.of(new RawConstraintColumn("TIP_ID", 1, null)));

        assertThrows(
                OracleSchemaSnapshotCodecException.class,
                () -> codec.decode("UPSTREAM_SCHEMA", "TEST", columns, List.of(duplicatePosition)));
        assertThrows(
                OracleSchemaSnapshotCodecException.class,
                () -> codec.decode("UPSTREAM_SCHEMA", "TEST", columns, List.of(missingReferencedColumn)));
    }

    private List<RawColumn> shuffledColumns() {
        return List.of(
                number("TUTAR", 18, 2, 5, true, " 0 "),
                number("ID", 19, 0, 1),
                timestamp("GUNCELLEME_ZAMANI", null, 6, true, " SYSTIMESTAMP "),
                number("SIRA_NO", 10, 0, 4),
                varchar("KOD", 50, 2),
                number("TIP_ID", 19, 0, 3));
    }

    private List<RawConstraint> shuffledConstraints() {
        return List.of(
                uniqueConstraint(),
                foreignKeyConstraint(),
                primaryKeyConstraint(),
                checkConstraint());
    }

    private RawConstraint primaryKeyConstraint() {
        return new RawConstraint(
                "PK_SAMPLE_TABLE", "P", "ENABLED", null, null, null,
                "NOT DEFERRABLE", null,
                List.of(new RawConstraintColumn("ID", 1, null)));
    }

    private RawConstraint uniqueConstraint() {
        return new RawConstraint(
                "UK_SAMPLE_KOD", "U", "ENABLED", null, null, null,
                "NOT DEFERRABLE", null,
                List.of(new RawConstraintColumn("KOD", 1, null)));
    }

    private RawConstraint foreignKeyConstraint() {
        return new RawConstraint(
                "FK_SAMPLE_TABLE", "R", "ENABLED", "UPSTREAM_SCHEMA", "SAMPLE_TIP",
                "NO ACTION", "NOT DEFERRABLE", null,
                List.of(
                        new RawConstraintColumn("SIRA_NO", 2, "SIRA_NO"),
                        new RawConstraintColumn("TIP_ID", 1, "ID")));
    }

    private RawConstraint checkConstraint() {
        return new RawConstraint(
                "CK_SAMPLE_TUTAR", "C", "ENABLED", null, null, null,
                "NOT DEFERRABLE", " TUTAR >= 0 ",
                List.of(new RawConstraintColumn("TUTAR", 1, null)));
    }

    private RawColumn number(String name, Integer precision, Integer scale, int ordinal) {
        return number(name, precision, scale, ordinal, false, null);
    }

    private RawColumn number(
            String name,
            Integer precision,
            Integer scale,
            int ordinal,
            boolean nullable,
            String defaultExpression) {
        return new RawColumn(
                name, "NUMBER", precision, scale, null, null, ordinal,
                nullable, defaultExpression);
    }

    private RawColumn varchar(String name, long length, int ordinal) {
        return new RawColumn(
                name, "VARCHAR2", null, null, length, null, ordinal, false, null);
    }

    private RawColumn timestamp(
            String name,
            Integer timePrecision,
            int ordinal,
            boolean nullable,
            String defaultExpression) {
        return new RawColumn(
                name, "TIMESTAMP", null, null, null, timePrecision,
                ordinal, nullable, defaultExpression);
    }

    private Column column(
            String name,
            String producerType,
            String canonicalType,
            int ordinal,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision,
            boolean nullable,
            String defaultExpression) {
        return new Column(
                name, producerType, canonicalType, ordinal, precision, scale,
                length, timePrecision, nullable, defaultExpression, name);
    }

    private Constraint constraint(
            String name,
            String type,
            boolean enabled,
            String details,
            List<String> columns) throws Exception {
        return new Constraint(
                name, type, enabled, 1, objectMapper.readTree(details), name, columns);
    }

    private void assertCodecFailure(RawColumn column) {
        assertThrows(
                OracleSchemaSnapshotCodecException.class,
                () -> codec.decode("UPSTREAM_SCHEMA", "TEST", List.of(column), List.of()));
    }
}

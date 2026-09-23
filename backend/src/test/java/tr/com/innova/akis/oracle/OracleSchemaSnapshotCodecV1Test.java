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
    void convertsHakedisTipiFixtureToTheGoldenSnapshotContract() throws Exception {
        SnapshotDefinition result = codec.decode(
                " ttbp ", " hakedis_tipi ", shuffledColumns(), shuffledConstraints());

        assertEquals("ORACLE_19C", result.engineVersion());
        assertEquals(1, result.propertyVersion());
        assertEquals(
                objectMapper.readTree("""
                        {"codec":"ORACLE_SCHEMA_V1","objectType":"TABLE",
                         "owner":"TTBP","table":"HAKEDIS_TIPI"}
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
                constraint("CK_HAKEDIS_TUTAR", "CHECK", true,
                        "{\"expression\":\"TUTAR >= 0\"}", List.of("TUTAR")),
                constraint("FK_HAKEDIS_TIPI", "FK", true,
                        """
                                {"deferrability":"NOT_DEFERRABLE","deleteRule":"NO_ACTION",
                                 "referencedOwner":"TTBP","referencedTable":"HAKEDIS_TIP",
                                 "referencedColumns":["ID","SIRA_NO"]}
                                """,
                        List.of("TIP_ID", "SIRA_NO")),
                constraint("PK_HAKEDIS_TIPI", "PK", true, "{}", List.of("ID")),
                constraint("UK_HAKEDIS_KOD", "UK", true, "{}", List.of("KOD"))),
                result.constraints());
    }

    @Test
    void outputIsIndependentOfDictionaryRowArrivalOrder() {
        SnapshotDefinition shuffled = codec.decode(
                "TTBP", "HAKEDIS_TIPI", shuffledColumns(), shuffledConstraints());
        SnapshotDefinition ordered = codec.decode(
                "TTBP", "HAKEDIS_TIPI",
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
                "TTBP", "VALUES_TABLE",
                List.of(number("VALUE", null, null, 1)), List.of()).columns().getFirst();

        assertEquals("NUMBER", result.producerType());
        assertEquals("DECIMAL", result.canonicalType());
        assertNull(result.precision());
        assertNull(result.scale());
    }

    @Test
    void decodesClobAsAnUnboundedTransferableStringColumn() {
        Column result = codec.decode(
                "TTBP", "VALUES_TABLE",
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
    void numberIntegerBoundaryIsNineteenDigits() {
        SnapshotDefinition result = codec.decode(
                "TTBP", "VALUES_TABLE",
                List.of(number("SMALL_VALUE", 19, 0, 1), number("BIG_VALUE", 20, 0, 2)),
                List.of());

        assertEquals("INTEGER", result.columns().get(0).canonicalType());
        assertEquals("DECIMAL", result.columns().get(1).canonicalType());
    }

    @Test
    void rejectsUnsupportedOrLossyColumnMetadata() {
        assertCodecFailure(new RawColumn(
                "VALUE", "NUMBER", 18, -2, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "NUMBER", null, 0, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "VARCHAR2", null, null, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "TIMESTAMP", null, null, null, 7, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "TIMESTAMP WITH TIME ZONE", null, null, null, 6,
                1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "CLOB", 1, null, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "CLOB", null, 1, null, null, 1, true, null));
        assertCodecFailure(new RawColumn(
                "VALUE", "CLOB", null, null, null, 6, 1, true, null));
    }

    @Test
    void rejectsMalformedConstraintPositionsAndForeignKeyDetails() {
        List<RawColumn> columns = List.of(
                number("TIP_ID", 19, 0, 1), number("SIRA_NO", 10, 0, 2));
        RawConstraint duplicatePosition = new RawConstraint(
                "FK_TEST", "R", "ENABLED", "TTBP", "TIP", "NO ACTION",
                "NOT DEFERRABLE", null,
                List.of(
                        new RawConstraintColumn("TIP_ID", 1, "ID"),
                        new RawConstraintColumn("SIRA_NO", 1, "SIRA_NO")));
        RawConstraint missingReferencedColumn = new RawConstraint(
                "FK_TEST", "R", "ENABLED", "TTBP", "TIP", "NO ACTION",
                "NOT DEFERRABLE", null,
                List.of(new RawConstraintColumn("TIP_ID", 1, null)));

        assertThrows(
                OracleSchemaSnapshotCodecException.class,
                () -> codec.decode("TTBP", "TEST", columns, List.of(duplicatePosition)));
        assertThrows(
                OracleSchemaSnapshotCodecException.class,
                () -> codec.decode("TTBP", "TEST", columns, List.of(missingReferencedColumn)));
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
                "PK_HAKEDIS_TIPI", "P", "ENABLED", null, null, null,
                "NOT DEFERRABLE", null,
                List.of(new RawConstraintColumn("ID", 1, null)));
    }

    private RawConstraint uniqueConstraint() {
        return new RawConstraint(
                "UK_HAKEDIS_KOD", "U", "ENABLED", null, null, null,
                "NOT DEFERRABLE", null,
                List.of(new RawConstraintColumn("KOD", 1, null)));
    }

    private RawConstraint foreignKeyConstraint() {
        return new RawConstraint(
                "FK_HAKEDIS_TIPI", "R", "ENABLED", "TTBP", "HAKEDIS_TIP",
                "NO ACTION", "NOT DEFERRABLE", null,
                List.of(
                        new RawConstraintColumn("SIRA_NO", 2, "SIRA_NO"),
                        new RawConstraintColumn("TIP_ID", 1, "ID")));
    }

    private RawConstraint checkConstraint() {
        return new RawConstraint(
                "CK_HAKEDIS_TUTAR", "C", "ENABLED", null, null, null,
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
                () -> codec.decode("TTBP", "TEST", List.of(column), List.of()));
    }
}

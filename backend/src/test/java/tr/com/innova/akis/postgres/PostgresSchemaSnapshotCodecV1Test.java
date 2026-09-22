package tr.com.innova.akis.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecException;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.Column;
import tr.com.innova.akis.oracle.OracleSchemaSnapshotCodecV1.SnapshotDefinition;
import tr.com.innova.akis.postgres.PostgresSchemaSnapshotCodecV1.RawColumn;
import tr.com.innova.akis.postgres.PostgresSchemaSnapshotCodecV1.RawConstraint;
import tr.com.innova.akis.postgres.PostgresSchemaSnapshotCodecV1.RawConstraintColumn;

class PostgresSchemaSnapshotCodecV1Test {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PostgresSchemaSnapshotCodecV1 codec = new PostgresSchemaSnapshotCodecV1(mapper);

    @Test
    void mapsFormatTypesToProducerAndCanonicalTypes() {
        SnapshotDefinition definition = codec.decode("public", "musteri", List.of(
                new RawColumn("id", "bigint", 1, false, "nextval('musteri_id_seq'::regclass)"),
                new RawColumn("tutar", "numeric(12,2)", 2, true, null),
                new RawColumn("kod", "numeric(10,0)", 3, true, null),
                new RawColumn("ad", "character varying(120)", 4, true, null),
                new RawColumn("aciklama", "text", 5, true, null),
                new RawColumn("olusturma", "timestamp(3) without time zone", 6, false, null),
                new RawColumn("guncelleme", "timestamp with time zone", 7, true, null),
                new RawColumn("dogum", "date", 8, true, null),
                new RawColumn("aktif", "boolean", 9, true, null),
                new RawColumn("veri", "bytea", 10, true, null),
                new RawColumn("etiket", "jsonb", 11, true, null)), List.of());

        assertEquals("POSTGRESQL", definition.engineVersion());
        assertEquals("POSTGRESQL_SCHEMA_V1", definition.properties().path("codec").asText());
        assertEquals("musteri", definition.properties().path("table").asText());
        List<Column> columns = definition.columns();
        assertColumn(columns.get(0), "BIGINT", "INTEGER", 19, 0, null, null);
        assertEquals("nextval('musteri_id_seq'::regclass)", columns.get(0).defaultExpression());
        assertColumn(columns.get(1), "NUMERIC(12,2)", "DECIMAL", 12, 2, null, null);
        assertColumn(columns.get(2), "NUMERIC(10,0)", "INTEGER", 10, 0, null, null);
        assertColumn(columns.get(3), "VARCHAR(120)", "STRING", null, null, 120L, null);
        assertColumn(columns.get(4), "TEXT", "STRING", null, null, null, null);
        assertColumn(columns.get(5), "TIMESTAMP(3)", "TIMESTAMP", null, null, null, 3);
        assertColumn(columns.get(6), "TIMESTAMPTZ(6)", "OFFSET_TIMESTAMP", null, null, null, 6);
        assertColumn(columns.get(7), "DATE", "DATE", null, null, null, null);
        assertColumn(columns.get(8), "BOOLEAN", "BOOLEAN", null, null, null, null);
        assertColumn(columns.get(9), "BYTEA", "BINARY", null, null, null, null);
        assertColumn(columns.get(10), "JSONB", "UNKNOWN", null, null, null, null);
    }

    @Test
    void keepsIdentifiersCaseSensitiveAndRejectsInvalidOnes() {
        SnapshotDefinition definition = codec.decode("Satis", "MusteriTablo", List.of(new RawColumn("MusteriId", "integer", 1, false, null)), List.of());
        assertEquals("MusteriId", definition.columns().getFirst().reference());
        assertEquals("Satis", definition.properties().path("owner").asText());
        assertThrows(OracleSchemaSnapshotCodecException.class,
                () -> codec.decode("public", "musteri; drop table x", List.of(new RawColumn("id", "integer", 1, false, null)), List.of()));
        assertThrows(OracleSchemaSnapshotCodecException.class,
                () -> codec.decode("public", "musteri", List.of(new RawColumn("id", "integer", 1, false, null), new RawColumn("id", "text", 2, true, null)), List.of()));
    }

    @Test
    void encodesConstraintsWithTheSharedDetailShape() {
        SnapshotDefinition definition = codec.decode("public", "siparis", List.of(
                new RawColumn("id", "bigint", 1, false, null),
                new RawColumn("musteri_id", "bigint", 2, false, null),
                new RawColumn("tutar", "numeric(12,2)", 3, false, null)), List.of(
                new RawConstraint("siparis_pkey", "p", true, false, null, null, null, null, List.of(new RawConstraintColumn("id", 1, null))),
                new RawConstraint("siparis_musteri_fkey", "f", true, true, "public", "musteri", "SET_NULL", null,
                        List.of(new RawConstraintColumn("musteri_id", 1, "id"))),
                new RawConstraint("siparis_tutar_check", "c", false, false, null, null, null, "CHECK ((tutar > (0)::numeric))",
                        List.of(new RawConstraintColumn("tutar", 1, null)))));

        assertEquals(List.of("siparis_musteri_fkey", "siparis_pkey", "siparis_tutar_check"),
                definition.constraints().stream().map(c -> c.externalReference()).toList());
        var fk = definition.constraints().get(0);
        assertEquals("FK", fk.type());
        assertEquals("DEFERRABLE", fk.details().path("deferrability").asText());
        assertEquals("SET_NULL", fk.details().path("deleteRule").asText());
        assertEquals("musteri", fk.details().path("referencedTable").asText());
        assertEquals("id", fk.details().path("referencedColumns").get(0).asText());
        var check = definition.constraints().get(2);
        assertEquals("CHECK", check.type());
        assertTrue(!check.enabled());
        assertEquals("CHECK ((tutar > (0)::numeric))", check.details().path("expression").asText());
        assertThrows(OracleSchemaSnapshotCodecException.class, () -> codec.decode("public", "siparis",
                List.of(new RawColumn("id", "bigint", 1, false, null)),
                List.of(new RawConstraint("x_fkey", "f", true, false, "public", "y", "NO_ACTION", null, List.of(new RawConstraintColumn("yok", 1, "id"))))));
    }

    @Test
    void fingerprintIgnoresServerPatchLevelBecauseEngineVersionIsAConstant() {
        SnapshotDefinition definition = codec.decode("public", "musteri", List.of(new RawColumn("id", "integer", 1, false, null)), List.of());
        SchemaFingerprint fingerprint = new SchemaFingerprint(mapper);
        String first = fingerprint.calculate(new SchemaFingerprintInput(definition.engineVersion(), definition.propertyVersion(),
                definition.properties(), inputColumns(definition), List.of()));
        String again = fingerprint.calculate(new SchemaFingerprintInput(PostgresSchemaSnapshotCodecV1.ENGINE_VERSION, definition.propertyVersion(),
                definition.properties(), inputColumns(definition), List.of()));
        assertEquals(first, again);
        assertNull(definition.columns().getFirst().length());
    }

    private static List<SchemaFingerprintInput.Column> inputColumns(SnapshotDefinition definition) {
        return definition.columns().stream().map(c -> new SchemaFingerprintInput.Column(
                c.reference(), c.producerType(), c.canonicalType(), c.ordinal(), c.precision(), c.scale(), c.length(), c.timePrecision(),
                c.nullable(), c.defaultExpression(), c.name())).toList();
    }

    private static void assertColumn(Column column, String producer, String canonical, Integer precision, Integer scale, Long length, Integer timePrecision) {
        assertEquals(producer, column.producerType());
        assertEquals(canonical, column.canonicalType());
        assertEquals(precision, column.precision());
        assertEquals(scale, column.scale());
        assertEquals(length, column.length());
        assertEquals(timePrecision, column.timePrecision());
    }
}

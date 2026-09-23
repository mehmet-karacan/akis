package tr.com.innova.akis.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.metadata.ApiException;

class PostgresSchemaProvisionerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generatesCreateSchemaAndTableWithPrimaryKey() {
        var id = column("ID", "NUMBER(12,0)", "INTEGER", 1, 12, 0, null, null, false);
        var email = column("EMAIL", "VARCHAR2(120)", "STRING", 2, null, null, 120L, null, true);
        var big = column("UZUN_SAYI", "NUMBER(19,0)", "INTEGER", 3, 19, 0, null, null, true);
        var seen = column("SON_GIRIS", "TIMESTAMP(3)", "TIMESTAMP", 4, null, null, null, 3, true);
        var pk = new ConstraintRow(UUID.randomUUID(), "PK_DENEME", "PK", true, 1, MAPPER.createObjectNode(), "PK_DENEME", List.of("ID"));
        var snapshot = snapshot(List.of(id, email, big, seen), List.of(pk));

        var plan = PostgresSchemaProvisioner.plan(snapshot, "akis_pg_target", "STG_DENEME");

        assertEquals("akis_pg_target", plan.schema());
        assertEquals("STG_DENEME", plan.table());
        assertTrue(plan.skippedColumns().isEmpty());
        assertTrue(plan.ddl().contains("CREATE SCHEMA IF NOT EXISTS \"akis_pg_target\";"));
        assertTrue(plan.ddl().contains("CREATE TABLE \"akis_pg_target\".\"STG_DENEME\""));
        assertTrue(plan.ddl().contains("\"ID\" bigint NOT NULL"), plan.ddl());
        assertTrue(plan.ddl().contains("\"EMAIL\" varchar(120)"), plan.ddl());
        assertTrue(plan.ddl().contains("\"UZUN_SAYI\" numeric(19,0)"), plan.ddl());
        assertTrue(plan.ddl().contains("\"SON_GIRIS\" timestamp(3)"), plan.ddl());
        assertTrue(plan.ddl().contains("PRIMARY KEY (\"ID\")"), plan.ddl());
    }

    @Test
    void skipsUnsupportedCanonicalTypesAndOmitsPkWhenAKeyColumnIsSkipped() {
        var id = column("ID", "NUMBER(12,0)", "INTEGER", 1, 12, 0, null, null, false);
        var raw = column("PAYLOAD", "RAW(16)", "BINARY", 2, null, null, 16L, null, true);
        var pk = new ConstraintRow(UUID.randomUUID(), "PK_X", "PK", true, 1, MAPPER.createObjectNode(), "PK_X", List.of("ID", "PAYLOAD"));
        var snapshot = snapshot(List.of(id, raw), List.of(pk));

        var plan = PostgresSchemaProvisioner.plan(snapshot, "akis_pg_target", "STG_X");

        assertEquals(List.of("PAYLOAD"), plan.skippedColumns());
        assertTrue(plan.ddl().contains("\"ID\" bigint"));
        assertTrue(!plan.ddl().contains("PAYLOAD"));
        assertTrue(!plan.ddl().contains("PRIMARY KEY"), "PK references a skipped column, so it must be dropped");
    }

    @Test
    void rejectsAnUnprovisionableSourceAndInvalidIdentifiers() {
        var raw = column("PAYLOAD", "RAW(16)", "BINARY", 1, null, null, 16L, null, true);
        var snapshot = snapshot(List.of(raw), List.of());
        assertThrows(ApiException.class, () -> PostgresSchemaProvisioner.plan(snapshot, "akis_pg_target", "STG_X"));

        var id = column("ID", "NUMBER(12,0)", "INTEGER", 1, 12, 0, null, null, false);
        var ok = snapshot(List.of(id), List.of());
        assertThrows(ApiException.class, () -> PostgresSchemaProvisioner.plan(ok, "public; drop table x", "STG_X"));
        assertThrows(ApiException.class, () -> PostgresSchemaProvisioner.plan(ok, "akis_pg_target", "STG X"));
    }

    private static ColumnRow column(String reference, String producerType, String canonicalType, int ordinal,
            Integer precision, Integer scale, Long length, Integer timePrecision, boolean nullable) {
        return new ColumnRow(UUID.randomUUID(), reference, producerType, canonicalType, ordinal, precision, scale, length, timePrecision, nullable, null, reference);
    }

    private static SnapshotRow snapshot(List<ColumnRow> columns, List<ConstraintRow> constraints) {
        return new SnapshotRow(1L, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), "ORACLE_19C", OffsetDateTime.now(), 1, MAPPER.createObjectNode(),
                OffsetDateTime.now(), columns, constraints);
    }
}

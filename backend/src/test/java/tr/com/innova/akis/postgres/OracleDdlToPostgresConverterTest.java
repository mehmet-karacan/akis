package tr.com.innova.akis.postgres;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OracleDdlToPostgresConverterTest {
    @Test
    void convertsPortableTableDefinitionToLowercasePostgresIdentifiers() {
        String oracle = """
                CREATE TABLE "UPSTREAM_SCHEMA"."CHANNEL" (
                  "ID" NUMBER NOT NULL ENABLE,
                  "ACIKLAMA" VARCHAR2(64) NOT NULL ENABLE,
                  "CHANNEL_GROUP_ID" NUMBER(19,0),
                  SUPPLEMENTAL LOG GROUP "GGS_CHANNEL_71815" ("ID") ALWAYS
                ) SEGMENT CREATION IMMEDIATE TABLESPACE "DATA";
                ALTER TABLE "UPSTREAM_SCHEMA"."CHANNEL" ADD CONSTRAINT "PK_CHANNEL" PRIMARY KEY ("ID") ENABLE
                """;

        var result = OracleDdlToPostgresConverter.convert(oracle, "UPSTREAM_SCHEMA", "CHANNEL");

        assertTrue(result.ddl().contains("CREATE SCHEMA IF NOT EXISTS upstream_schema;"));
        assertTrue(result.ddl().contains("CREATE TABLE upstream_schema.channel"));
        assertTrue(result.ddl().contains("id numeric NOT NULL"));
        assertTrue(result.ddl().contains("aciklama varchar(64) NOT NULL"));
        assertTrue(result.ddl().contains("channel_group_id numeric(19,0)"));
        assertTrue(result.ddl().contains("PRIMARY KEY (id)"));
        assertFalse(result.ddl().contains("\""));
        assertTrue(result.ignoredClauses().contains("SUPPLEMENTAL LOG GROUP"));
    }
}

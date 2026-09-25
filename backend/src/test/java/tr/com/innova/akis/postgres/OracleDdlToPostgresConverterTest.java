package tr.com.innova.akis.postgres;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OracleDdlToPostgresConverterTest {
    @Test
    void convertsPortableTableDefinitionToLowercasePostgresIdentifiers() {
        String oracle = """
                CREATE TABLE "TTBP"."SATIS_KANALI" (
                  "ID" NUMBER NOT NULL ENABLE,
                  "ACIKLAMA" VARCHAR2(64) NOT NULL ENABLE,
                  "SATIS_KANALI_GRUBU_ID" NUMBER(19,0),
                  SUPPLEMENTAL LOG GROUP "GGS_SATIS_KANALI_71815" ("ID") ALWAYS
                ) SEGMENT CREATION IMMEDIATE TABLESPACE "DATA";
                ALTER TABLE "TTBP"."SATIS_KANALI" ADD CONSTRAINT "PK_SATIS_KANALI" PRIMARY KEY ("ID") ENABLE
                """;

        var result = OracleDdlToPostgresConverter.convert(oracle, "TTBP", "SATIS_KANALI");

        assertTrue(result.ddl().contains("CREATE SCHEMA IF NOT EXISTS ttbp;"));
        assertTrue(result.ddl().contains("CREATE TABLE ttbp.satis_kanali"));
        assertTrue(result.ddl().contains("id numeric NOT NULL"));
        assertTrue(result.ddl().contains("aciklama varchar(64) NOT NULL"));
        assertTrue(result.ddl().contains("satis_kanali_grubu_id numeric(19,0)"));
        assertTrue(result.ddl().contains("PRIMARY KEY (id)"));
        assertFalse(result.ddl().contains("\""));
        assertTrue(result.ignoredClauses().contains("SUPPLEMENTAL LOG GROUP"));
    }
}

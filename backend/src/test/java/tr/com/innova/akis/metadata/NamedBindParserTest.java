package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class NamedBindParserTest {

    @Test
    void compilesOnlyExecutableTokensAndPreservesEverythingElse() {
        String sql = "select ':id', q'[:id]', nq'{:id}', \"colon:id\", :id, :ID, :Id$# from dual -- :id\n/* :id */";
        var compiled = NamedBindParser.compile(sql);
        assertEquals("select ':id', q'[:id]', nq'{:id}', \"colon:id\", ?, ?, ? from dual -- :id\n/* :id */", compiled.sql());
        assertEquals(List.of("ID", "ID", "ID$#"), compiled.names());
    }

    @Test
    void preservesLiteralPrefixesAcrossManyBindNames() {
        for (int index = 0; index < 250; index++) {
            String name = "v_" + index;
            String prefix = "/* :" + name + " */ select q'{İstanbul :" + name + "}', ";
            var compiled = NamedBindParser.compile(prefix + ":" + name + " from dual");
            assertEquals(prefix + "? from dual", compiled.sql());
            assertEquals(List.of(name.toUpperCase(java.util.Locale.ROOT)), compiled.names());
        }
    }

    @Test
    void extractsRepeatedBindsInPositionalOrder() {
        assertEquals(
                List.of("ID", "NAME", "ID"),
                NamedBindParser.parse(
                        "insert into T(ID, NAME, COPY_ID) values (:id, :Name, :ID)"));
    }

    @Test
    void ignoresColonsOutsideExecutableSql() {
        assertEquals(List.of("REAL_BIND"), NamedBindParser.parse("""
                insert into T(A,B,C,D,E) values (
                  ':literal', q'[ :q_bind ]', nq'{ :nq_bind }', "quoted:identifier", :real_bind)
                -- :line_comment
                /* :block_comment */
                """));
        assertEquals(List.of(), NamedBindParser.parse("begin value := 1; end;"));
    }

    @Test
    void rejectsUnterminatedQuotesAndComments() {
        assertThrows(IllegalArgumentException.class, () -> NamedBindParser.parse("select ':x"));
        assertThrows(IllegalArgumentException.class, () -> NamedBindParser.parse("select 1 /* :x"));
        assertThrows(IllegalArgumentException.class, () -> NamedBindParser.parse("select q'[ :x"));
    }
}

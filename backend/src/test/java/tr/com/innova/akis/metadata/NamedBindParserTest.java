package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class NamedBindParserTest {

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

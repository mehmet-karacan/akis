package tr.com.innova.akis.knowledge;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.knowledge.AkisKmLanguage.*;

class AkisKmLanguageTest {
    @Test void templatesParseAndCannotBeMutated() {
        for (Kind kind : Kind.values()) {
            var program = parse(example(kind));
            assertEquals(kind, program.kind());
            assertFalse(program.commands().isEmpty());
            assertTrue(program.steps().stream().allMatch(step -> program.commands().stream().anyMatch(command -> command.stepId().equals(step.id()))));
            assertThrows(UnsupportedOperationException.class, () -> program.steps().clear());
        }
    }
    @Test void acceptsCommentsAndWindowsLineEndings() {
        assertEquals(4, parse("-- Açıklama\r\n" + example(Kind.LKM).replace("\n", "\r\n")).steps().size());
    }
    @Test void currentOptionsAreTypedAndCanonical() {
        var program=parse(example(Kind.IKM));
        assertEquals(VERSION,program.language());
        assertEquals(List.of("WRITE_MODE","KEY_COLUMNS","TRUNCATE_TARGET","DROP_WORK_TABLE","ORACLE_HINT"),program.options().stream().map(Option::key).toList());
        assertEquals(OptionType.ENUM,program.options().getFirst().type());
        assertThrows(SyntaxException.class,()->parse(example(Kind.IKM).replace("ATOMIC_DELETE_INSERT APPEND", "UNKNOWN APPEND")));
        assertThrows(SyntaxException.class,()->parse(example(Kind.IKM).replace("SECENEK WRITE_MODE", "SECENEK WRITE_MODE\nSECENEK WRITE_MODE")));
    }
    @Test void rejectsUnsealedAndOutOfOrderSlots() {
        assertThrows(SyntaxException.class, () -> parse("AKIS_KM/1\nMODUL LKM\nADIM A STAGING CREATE_WORK W"));
        assertThrows(SyntaxException.class, () -> parse("AKIS_KM/1\nMODUL LKM\nADIM A STAGING TRANSFER_JDBC W"));
    }
    @Test void rejectsArbitraryCodeAndWrongLocation() {
        assertThrows(SyntaxException.class, () -> parse("AKIS_KM/1\nMODUL LKM\nADIM A STAGING DROP_TABLE W"));
        assertThrows(SyntaxException.class, () -> parse(example(Kind.IKM).replace("TARGET", "SOURCE")));
        assertThrows(SyntaxException.class, () -> parse(example(Kind.IKM).replace("MODUL IKM", "MODUL LKM")));
    }
    @Test void rejectsMissingOversizedAndDuplicateDefinitions() {
        assertThrows(SyntaxException.class, () -> parse(null));
        assertThrows(SyntaxException.class, () -> parse(" "));
        assertThrows(SyntaxException.class, () -> parse("x".repeat(65_537)));
        assertThrows(SyntaxException.class, () -> parse(example(Kind.LKM).replace("AKTAR", "HAZIRLA")));
    }
    @Test void quotedDefaultsPreserveSpacesQuotesAndLiteralYok() {
        String source=example(Kind.IKM).replace("SECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI YOK YOK", "SECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI \"FULL(T) PARALLEL(4)\" YOK\nSECENEK LABEL STRING ISTEGE_BAGLI \"Müşteri \\\"aktif\\\"\" YOK\nSECENEK EMPTY_MARKER STRING ISTEGE_BAGLI \"YOK\" YOK");
        var options=parse(source).options();
        assertEquals("FULL(T) PARALLEL(4)",options.stream().filter(option -> option.key().equals("ORACLE_HINT")).findFirst().orElseThrow().defaultValue());
        assertEquals("Müşteri \"aktif\"",options.stream().filter(option -> option.key().equals("LABEL")).findFirst().orElseThrow().defaultValue());
        assertEquals("YOK",options.stream().filter(option -> option.key().equals("EMPTY_MARKER")).findFirst().orElseThrow().defaultValue());
    }
    @Test void rejectsBrokenQuotesUnsafeHintAndEmptyOrDuplicateEnum() {
        String source=example(Kind.IKM);
        for(String replacement:List.of("\"OPEN", "\"APPEND\"junk", "\"FULL(T) */ DROP TABLE X\"", "\"FULL(T) -- ignored\"")) {
            assertThrows(SyntaxException.class,()->parse(source.replace("SQL_HINT ISTEGE_BAGLI YOK", "SQL_HINT ISTEGE_BAGLI "+replacement)));
        }
        for(String values:List.of("YOK", "APPEND,APPEND", "APPEND,,MERGE")) {
            assertThrows(SyntaxException.class,()->parse(source.replace("ATOMIC_DELETE_INSERT APPEND,MERGE,TRUNCATE_LOAD,ATOMIC_DELETE_INSERT", "YOK "+values)));
        }
    }
}

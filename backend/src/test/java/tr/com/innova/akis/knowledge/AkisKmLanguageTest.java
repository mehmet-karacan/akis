package tr.com.innova.akis.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.knowledge.AkisKmLanguage.*;

class AkisKmLanguageTest {
    @Test void templatesParseAndCannotBeMutated() {
        for (Kind kind : Kind.values()) {
            var program = parse(example(kind));
            assertEquals(kind, program.kind());
            assertThrows(UnsupportedOperationException.class, () -> program.steps().clear());
        }
    }
    @Test void acceptsCommentsAndWindowsLineEndings() {
        assertEquals(3, parse("-- Açıklama\r\n" + example(Kind.LKM).replace("\n", "\r\n")).steps().size());
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
}

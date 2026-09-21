package tr.com.innova.akis.knowledge;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkObjectPrefixesTest {
    @Test void enforcesIdentifiersAndDistinctPrefixes() {
        for (String value : new String[]{"", "a", "1", "A;DROP", "ABCDEFGHI"})
            assertThrows(IllegalArgumentException.class, () -> new WorkObjectPrefixes(value, "I$", "E$"));
        assertEquals("Prefixler farklı olmalıdır.", assertThrows(IllegalArgumentException.class,
                () -> new WorkObjectPrefixes("C$", "C$", "E$")).getMessage());
    }
    @Test void namesAreBoundedDeterministicAndExecutionSpecific() {
        var prefixes = new WorkObjectPrefixes("LOAD_123", "INT", "ERR");
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        String name = prefixes.objectName("LOADING", project, run, 1, "SOURCE_1");
        assertEquals(30, name.length());
        assertTrue(name.matches("AKIS_LOAD_123_[A-F0-9]+"));
        assertEquals(name, prefixes.objectName("LOADING", project, run, 1, "SOURCE_1"));
        assertNotEquals(name, prefixes.objectName("LOADING", project, run, 2, "SOURCE_1"));
        assertNotEquals(name, prefixes.objectName("LOADING", project, UUID.randomUUID(), 1, "SOURCE_1"));
        assertThrows(IllegalArgumentException.class, () -> prefixes.objectName("LOADING", project, run, 0, "SOURCE_1"));
    }
    @Test void odiStylePrefixesDoNotDoubleTheSeparator() {
        String name = WorkObjectPrefixes.DEFAULTS.objectName("LOADING", UUID.randomUUID(), UUID.randomUUID(), 1, "SOURCE_1");
        assertEquals(30, name.length());
        assertTrue(name.matches("AKIS_C\\$_[A-F0-9]+"));
    }
    @Test void targetNamesFollowOdiStyleAndFallBackToHashedTailOnShortHosts() {
        var prefixes = WorkObjectPrefixes.DEFAULTS;
        assertEquals("AKIS_C$_STG_MUSTERI", prefixes.targetObjectName("LOADING", "STG_MUSTERI", 0, 128));
        assertEquals("AKIS_C$_STG_MUSTERI_2", prefixes.targetObjectName("LOADING", "STG_MUSTERI", 2, 128));
        String cut = prefixes.targetObjectName("LOADING", "STG_CUSTOMER_PROFILE_HISTORY_LONG", 0, 30);
        assertEquals(30, cut.length());
        assertTrue(cut.startsWith("AKIS_C$_STG_CUSTOMER"));
        assertTrue(prefixes.ownsName("LOADING", cut));
        assertThrows(IllegalArgumentException.class, () -> prefixes.targetObjectName("LOADING", "bad name", 0, 128));
    }
    @Test void kmPatternNamesTheWorkTableAndKeepsTheOwnershipMarker() {
        var prefixes = WorkObjectPrefixes.DEFAULTS;
        assertEquals("AKIS_C$_STG_MUSTERI", prefixes.patternObjectName("LOADING", "C$_{HEDEF}", "STG_MUSTERI", "MUSTERI", "WORK_SOURCE_1", 0, 128));
        assertEquals("AKIS_W_MUSTERI_TO_STG_MUSTERI_3", prefixes.patternObjectName("LOADING", "W_{KAYNAK}_TO_{HEDEF}", "STG_MUSTERI", "MUSTERI", "WORK_SOURCE_1", 3, 128));
        assertEquals("AKIS_C$_STG_MUSTERI", prefixes.patternObjectName("LOADING", "", "STG_MUSTERI", "MUSTERI", "WORK_SOURCE_1", 0, 128));
        assertThrows(IllegalArgumentException.class, () -> prefixes.patternObjectName("LOADING", "C$ {HEDEF}", "STG_MUSTERI", null, null, 0, 128));
    }
}

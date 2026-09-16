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
}

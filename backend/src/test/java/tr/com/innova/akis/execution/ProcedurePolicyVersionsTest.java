package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProcedurePolicyVersionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyExactKnownVersionSet() {
        assertDoesNotThrow(() -> ProcedurePolicyVersions.requireSupported(ProcedurePolicyVersions.current(mapper)));
        for (String value : new String[] {"null", "\"1\"", "1.0", "0", "4294967297", "true"}) {
            var versions = ProcedurePolicyVersions.current(mapper);
            versions.set("sqlPolicy", mapper.readTree(value));
            assertThrows(IllegalArgumentException.class, () -> ProcedurePolicyVersions.requireSupported(versions));
        }
        var missing = ProcedurePolicyVersions.current(mapper);
        missing.remove("bindCompiler");
        assertThrows(IllegalArgumentException.class, () -> ProcedurePolicyVersions.requireSupported(missing));
        var extra = ProcedurePolicyVersions.current(mapper).put("unexpected", 1);
        assertThrows(IllegalArgumentException.class, () -> ProcedurePolicyVersions.requireSupported(extra));
    }
}

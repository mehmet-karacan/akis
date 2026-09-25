package tr.com.innova.akis.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AkisKmTemplateTest {
    @Test void rendersOnlyAllowlistedMetadataReferences() {
        String rendered = AkisKmTemplate.render("drop table {{ akisRef.table(\"WORK\", \"QUALIFIED\") }}",
                (function, arguments) -> function.equals("table") && arguments.equals(List.of("WORK", "QUALIFIED")) ? "ttbp.AKIS_C$_X" : null);
        assertEquals("drop table ttbp.AKIS_C$_X", rendered);
    }

    @Test void rejectsJavaAndUnknownFunctions() {
        assertThrows(IllegalArgumentException.class, () -> AkisKmTemplate.validate("<%= Runtime.getRuntime() %>"));
        assertThrows(IllegalArgumentException.class, () -> AkisKmTemplate.validate("{{ akisRef.secret(\"X\") }}"));
        assertThrows(IllegalArgumentException.class, () -> AkisKmTemplate.validate("{{ akisRef.table(System.getenv()) }}"));
    }
}

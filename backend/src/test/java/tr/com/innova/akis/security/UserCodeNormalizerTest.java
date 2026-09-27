package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UserCodeNormalizerTest {

    @Test
    void appliesNfkcAndLocaleIndependentLowercase() {
        assertEquals("developer", UserCodeNormalizer.normalize("DEVELOPER"));
        assertEquals("fullwidth", UserCodeNormalizer.normalize("ＦＵＬＬＷＩＤＴＨ"));
    }

    @Test
    void rejectsWhitespaceAndControlCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> UserCodeNormalizer.normalize("mehmet karacan"));
        assertThrows(IllegalArgumentException.class,
                () -> UserCodeNormalizer.normalize("developer\n"));
    }
}

package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncodingConfigurationTest {

    private final PasswordEncodingConfiguration configuration = new PasswordEncodingConfiguration();

    @Test
    void producesArgon2idEncodedPasswords() {
        PasswordEncoder encoder = configuration.applicationPasswordEncoder();

        String encoded = encoder.encode("uzun ve benzersiz parola 2026");

        assertNotNull(encoded);
        assertTrue(encoded.startsWith("{argon2}"));
        assertTrue(encoder.matches("uzun ve benzersiz parola 2026", encoded));
    }

    @Test
    void generatesUniqueSaltsForEachEncoding() {
        PasswordEncoder encoder = configuration.applicationPasswordEncoder();

        String first = encoder.encode("uzun ve benzersiz parola 2026");
        String second = encoder.encode("uzun ve benzersiz parola 2026");

        assertNotEquals(first, second);
        assertTrue(encoder.matches("uzun ve benzersiz parola 2026", first));
        assertTrue(encoder.matches("uzun ve benzersiz parola 2026", second));
    }
}

package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.metadata.ApiException;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongPassphrases() {
        assertDoesNotThrow(() -> policy.validate("uzun ve benzersiz parola 2026"));
    }

    @Test
    void rejectsShortAndOversizedPasswords() {
        assertThrows(ApiException.class, () -> policy.validate("kisa"));
        assertThrows(ApiException.class, () -> policy.validate("x".repeat(129)));
    }
}

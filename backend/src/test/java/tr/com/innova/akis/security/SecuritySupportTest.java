package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SecuritySupportTest {

    @Test
    void developmentModeAcceptsOnlyLoopbackAddresses() {
        assertDoesNotThrow(() -> SecuritySupport.requireLoopback("127.0.0.1"));
        assertDoesNotThrow(() -> SecuritySupport.requireLoopback("::1"));
        assertThrows(
                IllegalStateException.class,
                () -> SecuritySupport.requireLoopback("0.0.0.0"));
    }

    @Test
    void requiredSecretsRejectPlaceholders() {
        assertThrows(IllegalStateException.class, () -> SecuritySupport.required("", "value"));
        assertThrows(
                IllegalStateException.class,
                () -> SecuritySupport.required("change-me", "value"));
        assertDoesNotThrow(() -> SecuritySupport.required("configured", "value"));
    }
}

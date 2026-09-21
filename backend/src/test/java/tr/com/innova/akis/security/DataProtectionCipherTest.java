package tr.com.innova.akis.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataProtectionCipherTest {
    @AfterEach void reset() { DataProtectionCipher.configure(""); }

    @Test void roundTripsWithPrefixAndFreshNonces() {
        DataProtectionCipher.configure("test-key");
        String one = DataProtectionCipher.encrypt("ali@ornek.com.tr"), two = DataProtectionCipher.encrypt("ali@ornek.com.tr");
        assertTrue(one.startsWith(DataProtectionCipher.PREFIX));
        assertNotEquals(one, two);
        assertEquals("ali@ornek.com.tr", DataProtectionCipher.decrypt(one));
        assertNull(DataProtectionCipher.encrypt(null));
        assertEquals("plain", DataProtectionCipher.decrypt("plain"));
        assertTrue(one.length() <= DataProtectionCipher.requiredLength("ali@ornek.com.tr".length()));
    }

    @Test void refusesWithoutAKeyAndRejectsTamperedValues() {
        assertFalse(DataProtectionCipher.configured());
        assertThrows(IllegalStateException.class, () -> DataProtectionCipher.encrypt("x"));
        DataProtectionCipher.configure("test-key");
        String value = DataProtectionCipher.encrypt("x");
        DataProtectionCipher.configure("other-key");
        assertThrows(IllegalStateException.class, () -> DataProtectionCipher.decrypt(value));
    }
}

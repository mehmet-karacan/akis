package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class OracleDatabaseIdentityFingerprintV1Test {

    private static final String EXPECTED_PAYLOAD = "0000001d414b49535f4f5241434c455f"
            + "44415441424153455f4944454e5449545900000001310000000554544250320000000450444231";
    private static final String EXPECTED_FINGERPRINT =
            "2c426daf2cebdb13b173deed52db7135011d0ad07a1d46245dda6bc88214c731";

    private final OracleDatabaseIdentityFingerprintV1 fingerprint =
            new OracleDatabaseIdentityFingerprintV1();

    @Test
    void canonicalizesValuesAndMatchesTheStableV1Vector() {
        OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity identity =
                fingerprint.canonicalize("  ttbp2  ", " pdb1 ");

        assertEquals(1, identity.identityVersion());
        assertEquals("TTBP2", identity.databaseUniqueName());
        assertEquals("PDB1", identity.containerName());
        assertArrayEquals(HexFormat.of().parseHex(EXPECTED_PAYLOAD), identity.canonicalPayload());
        assertEquals(EXPECTED_FINGERPRINT, identity.fingerprint());
    }

    @Test
    void rejectsMissingBlankAndNulBearingIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize(null, "PDB1"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize(" ", "PDB1"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("DB\0X", "PDB1"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("TTBP2", null));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("TTBP2", "\t"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("TTBP2", "PDB\0X"));
    }

    @Test
    void lengthPrefixPreventsFieldBoundaryAmbiguity() {
        String first = fingerprint.canonicalize("AB", "C").fingerprint();
        String second = fingerprint.canonicalize("A", "BC").fingerprint();

        assertNotEquals(first, second);
    }

    @Test
    void canonicalPayloadIsDefensivelyCopied() {
        OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity identity =
                fingerprint.canonicalize("TTBP2", "PDB1");
        byte[] exposed = identity.canonicalPayload();
        exposed[0] = (byte) (exposed[0] + 1);

        assertArrayEquals(HexFormat.of().parseHex(EXPECTED_PAYLOAD), identity.canonicalPayload());
    }
}

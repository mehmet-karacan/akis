package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class OracleDatabaseIdentityFingerprintV1Test {

    private static final String EXPECTED_PAYLOAD = "0000001d414b49535f4f5241434c455f"
            + "44415441424153455f4944454e544954590000000131"
            + "00000011555053545245414d5f534348454d415f320000000450444231";
    private static final String EXPECTED_FINGERPRINT =
            "dc3ddd575f00052c39d3b8a234b880a2bcfd17097cd0cdf7de1bb30112852e66";

    private final OracleDatabaseIdentityFingerprintV1 fingerprint =
            new OracleDatabaseIdentityFingerprintV1();

    @Test
    void canonicalizesValuesAndMatchesTheStableV1Vector() {
        OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity identity =
                fingerprint.canonicalize("  upstream_schema_2  ", " pdb1 ");

        assertEquals(1, identity.identityVersion());
        assertEquals("UPSTREAM_SCHEMA_2", identity.databaseUniqueName());
        assertEquals("PDB1", identity.containerName());
        assertArrayEquals(HexFormat.of().parseHex(EXPECTED_PAYLOAD), identity.canonicalPayload());
        assertEquals(EXPECTED_FINGERPRINT, identity.fingerprint());
    }

    @Test
    void rejectsMissingBlankAndNulBearingIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize(null, "PDB1"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize(" ", "PDB1"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("DB\0X", "PDB1"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("UPSTREAM_SCHEMA_2", null));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("UPSTREAM_SCHEMA_2", "\t"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint.canonicalize("UPSTREAM_SCHEMA_2", "PDB\0X"));
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
                fingerprint.canonicalize("UPSTREAM_SCHEMA_2", "PDB1");
        byte[] exposed = identity.canonicalPayload();
        exposed[0] = (byte) (exposed[0] + 1);

        assertArrayEquals(HexFormat.of().parseHex(EXPECTED_PAYLOAD), identity.canonicalPayload());
    }
}

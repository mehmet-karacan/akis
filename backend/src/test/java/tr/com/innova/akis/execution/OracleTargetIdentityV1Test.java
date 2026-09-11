package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.OracleTargetIdentityV1.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.VerifiedDatabaseIdentity;

class OracleTargetIdentityV1Test {

    private final OracleTargetIdentityV1 canonicalizer = new OracleTargetIdentityV1();

    @Test
    void producesStableLengthPrefixedUtf8PayloadAndLowercaseSha256() {
        CanonicalTargetIdentity identity = canonicalizer.canonicalize(
                new VerifiedDatabaseIdentity("ct_gpu_testdb", "CT_GPU_TESTDB"),
                "INNOVA_ODI",
                "TABLE",
                "STG_HAKEDIS_TIPI");

        assertEquals(1, identity.targetIdentityVersion());
        assertEquals("CT_GPU_TESTDB", identity.databaseUniqueName());
        assertEquals("CT_GPU_TESTDB", identity.containerName());
        assertEquals(
                "0000001b414b49535f4f5241434c455f5441524745545f4944454e54495459"
                        + "00000001310000000d43545f4750555f5445535444420000000d43545f4750"
                        + "555f5445535444420000000a494e4e4f56415f4f4449000000055441424c"
                        + "45000000105354475f48414b454449535f54495049",
                HexFormat.of().formatHex(identity.canonicalPayload()));
        assertEquals(
                "0385c5624b49aa4da18d249032db5387da0df0e6889195764c88a16c77cc1803",
                identity.canonicalTargetHash());
        assertFalse(identity.canonicalTargetHash().matches(".*[A-F].*"));
        assertFalse(new String(identity.canonicalPayload(), StandardCharsets.UTF_8)
                .contains("jdbc:"));
    }

    @Test
    void failsClosedForUnsupportedOrNonCanonicalPilotObjects() {
        VerifiedDatabaseIdentity database =
                new VerifiedDatabaseIdentity("AKISDB", "AKISPDB");

        assertInvalid(database, "innova_odi", "TABLE", "STG_TABLE");
        assertInvalid(database, "\"INNOVA_ODI\"", "TABLE", "STG_TABLE");
        assertInvalid(database, "", "TABLE", "STG_TABLE");
        assertInvalid(database, "INNOVA_ODI", "VIEW", "STG_TABLE");
        assertInvalid(database, "INNOVA_ODI", "TABLE", "MixedCase");
        assertInvalid(database, "INNOVA_ODI", "TABLE", "\"STG_TABLE\"");
        assertInvalid(database, "INNOVA_ODI", "TABLE", "1_INVALID");
    }

    @Test
    void failsClosedWhenVerifiedDatabaseOrContainerIdentityIsMissing() {
        assertInvalid(
                new VerifiedDatabaseIdentity(null, "AKISPDB"),
                "INNOVA_ODI", "TABLE", "STG_TABLE");
        assertInvalid(
                new VerifiedDatabaseIdentity("AKISDB", "  "),
                "INNOVA_ODI", "TABLE", "STG_TABLE");
    }

    @Test
    void canonicalPayloadIsDefensivelyCopied() {
        CanonicalTargetIdentity identity = canonicalizer.canonicalize(
                new VerifiedDatabaseIdentity("AKISDB", "AKISPDB"),
                "INNOVA_ODI", "TABLE", "STG_TABLE");
        byte[] first = identity.canonicalPayload();
        first[0] = 127;

        assertEquals(0, identity.canonicalPayload()[0]);
    }

    private void assertInvalid(
            VerifiedDatabaseIdentity database,
            String owner,
            String type,
            String name) {
        assertThrows(
                OracleTargetIdentityException.class,
                () -> canonicalizer.canonicalize(database, owner, type, name));
    }
}

package tr.com.innova.akis.oracle;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Canonical fingerprint for the Oracle database container reached by a connection. */
public final class OracleDatabaseIdentityFingerprintV1 {

    public static final int IDENTITY_VERSION = 1;

    private static final String DOMAIN = "AKIS_ORACLE_DATABASE_IDENTITY";

    public CanonicalDatabaseIdentity canonicalize(
            String databaseUniqueName,
            String containerName) {
        String canonicalDatabaseUniqueName = requireValue(databaseUniqueName, "DB_UNIQUE_NAME");
        String canonicalContainerName = requireValue(containerName, "CON_NAME");
        byte[] payload = encode(List.of(
                DOMAIN,
                Integer.toString(IDENTITY_VERSION),
                canonicalDatabaseUniqueName,
                canonicalContainerName));
        return new CanonicalDatabaseIdentity(
                IDENTITY_VERSION,
                canonicalDatabaseUniqueName,
                canonicalContainerName,
                payload,
                sha256(payload));
    }

    private String requireValue(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Oracle " + name + " is missing or invalid.");
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private byte[] encode(List<String> fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    private String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record CanonicalDatabaseIdentity(
            int identityVersion,
            String databaseUniqueName,
            String containerName,
            byte[] canonicalPayload,
            String fingerprint) {

        public CanonicalDatabaseIdentity {
            canonicalPayload = canonicalPayload.clone();
        }

        @Override
        public byte[] canonicalPayload() {
            return canonicalPayload.clone();
        }
    }
}

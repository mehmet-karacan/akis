package tr.com.innova.akis.execution;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical Oracle table identity used by the V1 target-fencing contract. */
final class OracleTargetIdentityV1 {

    static final int TARGET_IDENTITY_VERSION = 1;

    private static final String DOMAIN = "AKIS_ORACLE_TARGET_IDENTITY";
    private static final String SUPPORTED_OBJECT_TYPE = "TABLE";
    private static final Pattern UNQUOTED_ORACLE_IDENTIFIER =
            Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

    CanonicalTargetIdentity canonicalize(
            VerifiedDatabaseIdentity database,
            String owner,
            String objectType,
            String objectName) {
        Objects.requireNonNull(database, "Verified Oracle database identity is required.");
        String databaseUniqueName = requireDatabaseValue(
                database.databaseUniqueName(), "DB_UNIQUE_NAME");
        String containerName = requireDatabaseValue(database.containerName(), "CON_NAME");
        ValidatedTargetObject target = validateTarget(owner, objectType, objectName);
        byte[] payload = encode(List.of(
                DOMAIN,
                Integer.toString(TARGET_IDENTITY_VERSION),
                databaseUniqueName,
                containerName,
                target.owner(),
                target.objectType(),
                target.objectName()));
        return new CanonicalTargetIdentity(
                TARGET_IDENTITY_VERSION,
                databaseUniqueName,
                containerName,
                target.owner(),
                target.objectType(),
                target.objectName(),
                payload,
                sha256(payload));
    }

    ValidatedTargetObject validateTarget(
            String owner, String objectType, String objectName) {
        String canonicalOwner = requirePilotIdentifier(owner, "Owner");
        String canonicalType = requirePilotIdentifier(objectType, "Object type");
        String canonicalObjectName = requirePilotIdentifier(objectName, "Object name");
        if (!SUPPORTED_OBJECT_TYPE.equals(canonicalType)) {
            throw new OracleTargetIdentityException(
                    "Oracle target identity V1 supports TABLE objects only.");
        }
        return new ValidatedTargetObject(canonicalOwner, canonicalType, canonicalObjectName);
    }

    private String requireDatabaseValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new OracleTargetIdentityException(
                    "Verified Oracle " + name + " is missing or ambiguous.");
        }
        String canonical = value.trim().toUpperCase(Locale.ROOT);
        if (canonical.indexOf('\0') >= 0) {
            throw new OracleTargetIdentityException(
                    "Verified Oracle " + name + " contains an invalid character.");
        }
        return canonical;
    }

    private String requirePilotIdentifier(String value, String name) {
        if (value == null || value.isBlank()
                || !UNQUOTED_ORACLE_IDENTIFIER.matcher(value).matches()) {
            throw new OracleTargetIdentityException(
                    name + " must be an unquoted uppercase Oracle identifier.");
        }
        return value;
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record VerifiedDatabaseIdentity(String databaseUniqueName, String containerName) {
    }

    record ValidatedTargetObject(String owner, String objectType, String objectName) {
    }

    record CanonicalTargetIdentity(
            int targetIdentityVersion,
            String databaseUniqueName,
            String containerName,
            String owner,
            String objectType,
            String objectName,
            byte[] canonicalPayload,
            String canonicalTargetHash) {

        CanonicalTargetIdentity {
            canonicalPayload = canonicalPayload.clone();
        }

        @Override
        public byte[] canonicalPayload() {
            return canonicalPayload.clone();
        }
    }
}

package tr.com.innova.akis.execution;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.CanonicalTargetIdentity;

/**
 * Canonical PostgreSQL table identity (POSTGRES_TARGET_V1): ledger installation uuid, database name, namespace and
 * relation (with their oids) and relkind. The oids make a dropped-and-recreated table a different target while TRUNCATE
 * keeps it the same; the installation uuid ties the identity to one ledger installation even when a database is cloned
 * under another name. Reuses the V1 identity record: databaseUniqueName = database, containerName = installation uuid.
 */
final class PostgresTargetIdentityV1 {
    static final int TARGET_IDENTITY_VERSION = 1;
    private static final String DOMAIN = "AKIS_POSTGRES_TARGET_IDENTITY";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    record VerifiedRelation(String installationUuid, String databaseName, long databaseOid, String schemaName, long schemaOid,
            String relationName, long relationOid, String relkind) { }

    CanonicalTargetIdentity canonicalize(VerifiedRelation relation, String objectType) {
        Objects.requireNonNull(relation, "Verified PostgreSQL relation is required.");
        if (!"TABLE".equals(objectType)) throw new OracleTargetIdentityException("PostgreSQL target identity V1 supports TABLE objects only.");
        String installation = requireText(relation.installationUuid(), "installation uuid");
        if (!UUID_TEXT.matcher(installation).matches()) throw new OracleTargetIdentityException("PostgreSQL ledger installation uuid is invalid.");
        String database = requireText(relation.databaseName(), "database name");
        String schema = requireIdentifier(relation.schemaName(), "Schema");
        String table = requireIdentifier(relation.relationName(), "Object name");
        if (!"r".equals(relation.relkind()) && !"p".equals(relation.relkind())) {
            throw new OracleTargetIdentityException("PostgreSQL target must be an ordinary or partitioned table.");
        }
        byte[] payload = encode(List.of(DOMAIN, Integer.toString(TARGET_IDENTITY_VERSION), installation, database,
                Long.toString(relation.databaseOid()), schema, Long.toString(relation.schemaOid()), table,
                Long.toString(relation.relationOid()), relation.relkind()));
        return new CanonicalTargetIdentity(TARGET_IDENTITY_VERSION, database, installation, schema, "TABLE", table, payload, sha256(payload));
    }

    String requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new OracleTargetIdentityException(name + " must be a PostgreSQL identifier (63 characters, letters, digits, _ and $).");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) throw new OracleTargetIdentityException("Verified PostgreSQL " + name + " is missing.");
        return value.trim();
    }

    private static byte[] encode(List<String> fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

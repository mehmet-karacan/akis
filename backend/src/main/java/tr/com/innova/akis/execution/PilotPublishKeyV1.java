package tr.com.innova.akis.execution;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Retry-stable identity of one logical pilot publication. */
final class PilotPublishKeyV1 {

    static final int VERSION = 1;
    static final String PILOT_STEP_CODE = "PILOT_PUBLISH";

    private static final String DOMAIN = "AKIS_ORACLE_PILOT_PUBLISH";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STEP = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    String create(
            UUID jobRequestUuid,
            String runtimePlanHash,
            String canonicalTargetHash,
            String stepCode) {
        String safeStep = stepCode == null
                ? "" : stepCode.strip().toUpperCase(Locale.ROOT);
        if (jobRequestUuid == null || !hash(runtimePlanHash)
                || !hash(canonicalTargetHash) || !STEP.matcher(safeStep).matches()) {
            throw new IllegalArgumentException("Pilot publish key contract is invalid.");
        }
        MessageDigest digest = sha256();
        field(digest, DOMAIN);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(VERSION).array());
        field(digest, jobRequestUuid.toString());
        field(digest, runtimePlanHash);
        field(digest, canonicalTargetHash);
        field(digest, safeStep);
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean hash(String value) {
        return value != null && HASH.matcher(value).matches();
    }

    private void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

package tr.com.innova.akis.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Prefix is presentation/naming policy, never authorization to DROP. */
public record WorkObjectPrefixes(String loading, String integration, String error) {
    public static final WorkObjectPrefixes DEFAULTS = new WorkObjectPrefixes("C$_", "I$_", "E$_");
    public WorkObjectPrefixes {
        for (String prefix : new String[]{loading, integration, error}) {
            if (prefix == null || !prefix.matches("[A-Z][A-Z0-9_$]{0,7}"))
                throw new IllegalArgumentException("Prefix 1 ile 8 karakter arasında olmalı; A-Z ile başlayıp A-Z, 0-9, _ veya $ içermelidir.");
        }
        if (loading.equals(integration) || loading.equals(error) || integration.equals(error))
            throw new IllegalArgumentException("Prefixler farklı olmalıdır.");
    }
    /**
     * ODI-style work name: {@code AKIS_<prefix><TARGET>} (e.g. {@code AKIS_C$_STG_MUSTERI}), {@code _2}, {@code _3}… when the
     * plain name is still held by an earlier attempt. On hosts limited to {@code maxLength} bytes the target part is cut and
     * a 4-hex tail keeps the name unique.
     */
    public String targetObjectName(String role, String targetTable, int sequence, int maxLength) {
        if (targetTable == null || !targetTable.matches("[A-Z][A-Z0-9_$#]{0,127}") || sequence < 0 || maxLength < 20)
            throw new IllegalArgumentException("Çalışma nesnesi hedef adı geçersiz.");
        String prefix = switch (role) { case "LOADING" -> loading; case "INTEGRATION" -> integration; case "ERROR" -> error;
            default -> throw new IllegalArgumentException("Çalışma nesnesi rolü geçersiz."); };
        String marker = "AKIS_" + (prefix.endsWith("_") ? prefix : prefix + "_");
        String suffix = sequence == 0 ? "" : "_" + sequence;
        String name = marker + targetTable + suffix;
        if (name.length() <= maxLength) return name;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(targetTable.getBytes(StandardCharsets.UTF_8)));
            String tail = "_" + hash.substring(0, 4).toUpperCase(java.util.Locale.ROOT) + suffix;
            return marker + targetTable.substring(0, maxLength - marker.length() - tail.length()) + tail;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * KM contract: the LKM option {@code WORK_TABLE_PATTERN} (e.g. {@code C$_{HEDEF}}) names the work table; placeholders are
     * {@code {HEDEF}} (target table), {@code {KAYNAK}} (first source table) and {@code {SLOT}}. The {@code AKIS_} ownership
     * marker is always prepended so the registry can tell AKIS tables from business tables. A blank pattern falls back to the
     * physical schema's loading prefix + target.
     */
    public String patternObjectName(String role, String pattern, String targetTable, String sourceTable, String slot, int sequence, int maxLength) {
        if (pattern == null || pattern.isBlank()) return targetObjectName(role, targetTable, sequence, maxLength);
        String resolved = pattern.trim().toUpperCase(java.util.Locale.ROOT)
                .replace("{HEDEF}", targetTable).replace("{TARGET}", targetTable)
                .replace("{KAYNAK}", sourceTable == null ? "" : sourceTable).replace("{SOURCE}", sourceTable == null ? "" : sourceTable)
                .replace("{SLOT}", slot == null ? "" : slot);
        if (!resolved.matches("[A-Z][A-Z0-9_$#]{0,127}")) throw new IllegalArgumentException("Çalışma tablosu deseni geçersiz bir ad üretti: " + resolved);
        String suffix = sequence == 0 ? "" : "_" + sequence;
        String name = "AKIS_" + resolved + suffix;
        if (name.length() <= maxLength) return name;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resolved.getBytes(StandardCharsets.UTF_8)));
            String tail = "_" + hash.substring(0, 4).toUpperCase(java.util.Locale.ROOT) + suffix;
            return "AKIS_" + resolved.substring(0, maxLength - 5 - tail.length()) + tail;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** True when the name carries this role's AKIS ownership marker (the hash part is opaque to the caller). */
    public boolean ownsName(String role, String name) {
        String prefix = switch (role) { case "LOADING" -> loading; case "INTEGRATION" -> integration; case "ERROR" -> error;
            default -> throw new IllegalArgumentException("Çalışma nesnesi rolü geçersiz."); };
        String marker = prefix.endsWith("_") ? prefix : prefix + "_";
        return name != null && name.length() <= 128 && name.startsWith("AKIS_") && (name.startsWith("AKIS_" + marker) || name.substring(5).matches("[A-Z][A-Z0-9_$#]+"));
    }
    public String objectName(String role, UUID project, UUID run, long generation, String slot) {
        if (project == null || run == null || generation < 1 || slot == null || !slot.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Çalışma nesnesi kimliği geçersiz.");
        String prefix = switch (role) { case "LOADING" -> loading; case "INTEGRATION" -> integration; case "ERROR" -> error;
            default -> throw new IllegalArgumentException("Çalışma nesnesi rolü geçersiz."); };
        try {
            String identity = "AKIS_NAME/1|" + project + "|" + run + "|" + generation + "|" + slot + "|" + role;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            // Conservative 30-byte ASCII limit, including fixed AKIS ownership marker.
            // ODI-style prefixes already end with "_"; do not double the separator.
            String marker = prefix.endsWith("_") ? prefix : prefix + "_";
            return "AKIS_" + marker + hash.substring(0, 30 - 5 - marker.length()).toUpperCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

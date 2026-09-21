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
    /** True when the name carries this role's AKIS ownership marker (the hash part is opaque to the caller). */
    public boolean ownsName(String role, String name) {
        String prefix = switch (role) { case "LOADING" -> loading; case "INTEGRATION" -> integration; case "ERROR" -> error;
            default -> throw new IllegalArgumentException("Çalışma nesnesi rolü geçersiz."); };
        String marker = prefix.endsWith("_") ? prefix : prefix + "_";
        return name != null && name.length() == 30 && name.startsWith("AKIS_" + marker) && name.substring(5 + marker.length()).matches("[0-9A-F]+");
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

package tr.com.innova.akis.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Column-level protection for values marked sensitive on a data store: AES-256-GCM with the deployment key
 * ({@code akis.security.data-encryption-key}); the key never enters the metadata database. Output is
 * {@code enc:v1:<base64(nonce || ciphertext || tag)>}, so a protected VARCHAR2 column needs
 * {@link #requiredLength(int)} characters.
 */
@Component
public final class DataProtectionCipher {
    public static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12, TAG_BITS = 128;
    private static volatile SecretKeySpec key;
    private static final SecureRandom RANDOM = new SecureRandom();

    public DataProtectionCipher(@Value("${akis.security.data-encryption-key:}") String configured) { configure(configured); }

    /** Any non-blank passphrase is accepted; it is stretched to 256 bits with SHA-256. Blank disables encryption. */
    public static void configure(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) { key = null; return; }
        try { key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(passphrase.trim().getBytes(StandardCharsets.UTF_8)), "AES"); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    public static boolean configured() { return key != null; }

    public static String encrypt(String plain) {
        if (plain == null) return null;
        SecretKeySpec current = key;
        if (current == null) throw new IllegalStateException("Veri şifreleme anahtarı yapılandırılmamış (AKIS_DATA_ENCRYPTION_KEY).");
        try {
            byte[] nonce = new byte[NONCE_BYTES]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + body.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length); System.arraycopy(body, 0, out, nonce.length, body.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Değer şifrelenemedi.", failure); }
    }

    public static String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) return value;
        SecretKeySpec current = key;
        if (current == null) throw new IllegalStateException("Veri şifreleme anahtarı yapılandırılmamış (AKIS_DATA_ENCRYPTION_KEY).");
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw new IllegalStateException("Değer çözülemedi.", failure); }
    }

    /** Characters a protected column needs for a plaintext of {@code plainChars} characters (UTF-8 worst case 4 bytes/char is not assumed; 2 is). */
    public static int requiredLength(int plainChars) {
        int bytes = NONCE_BYTES + plainChars * 2 + TAG_BITS / 8;
        return PREFIX.length() + ((bytes + 2) / 3) * 4;
    }
}

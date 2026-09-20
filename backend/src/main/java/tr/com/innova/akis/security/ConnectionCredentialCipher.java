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
 * Encrypts connection credentials (username/password) for storage in baglanti_kimligi.gizli_deger_saglayicisi = 'TABLO'.
 * Credentials live in the database, never in OS environment variables or Git-tracked files.
 * The cipher key itself is a single application-wide secret (akis.security.credential-key), separate from any
 * individual connection's password; a fixed local-dev default is used when unset so a fresh checkout works with
 * zero configuration. Real deployments must override it.
 */
@Component
public final class ConnectionCredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ConnectionCredentialCipher(
            @Value("${akis.security.credential-key:akis-local-development-credential-key}") String configuredKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(configuredKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        }
        catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Kimlik bilgisi şifrelenemedi.", exception);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= GCM_IV_BYTES) throw new IllegalArgumentException("Şifreli değer geçersiz.");
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            byte[] ciphertext = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Kimlik bilgisi çözülemedi.", exception);
        }
    }
}

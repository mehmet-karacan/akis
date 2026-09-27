package tr.com.innova.akis.security;

import java.text.Normalizer;
import java.util.Locale;

/** Canonical user-code contract shared by login, bootstrap and user management. */
public final class UserCodeNormalizer {

    public static final int MAX_CODE_POINTS = 100;

    private UserCodeNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Kullanıcı kodu zorunludur.");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length == 0 || length > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("Kullanıcı kodu 1-100 karakter olmalıdır.");
        }
        if (normalized.codePoints().anyMatch(UserCodeNormalizer::forbidden)) {
            throw new IllegalArgumentException(
                    "Kullanıcı kodu boşluk veya kontrol karakteri içeremez.");
        }
        return normalized;
    }

    private static boolean forbidden(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint);
    }
}

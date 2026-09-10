package tr.com.innova.akis.security;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class SecurityModeGuard {

    private static final Set<String> MODES = Set.of("fail-closed", "development", "oidc");

    SecurityModeGuard(@Value("${akis.security.mode:fail-closed}") String mode) {
        String normalized = SecuritySupport.mode(mode);
        if (!MODES.contains(normalized)) {
            throw new IllegalStateException(
                    "AKIS_SECURITY_MODE must be fail-closed, development, or oidc.");
        }
    }
}

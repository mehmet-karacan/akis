package tr.com.innova.akis.execution;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.metadata.ApiException;

@Component
final class RunActorResolver {

    private static final String LOCAL_BASIC_PROVIDER = "LOCAL_BASIC";
    private final ExecutionStore store;

    RunActorResolver(ExecutionStore store) {
        this.store = store;
    }

    Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Çalıştırma işlemi için kimlik doğrulaması gereklidir.");
        }

        String provider;
        String subject;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            provider = jwt.getToken().getIssuer() == null
                    ? null : jwt.getToken().getIssuer().toString();
            subject = jwt.getToken().getSubject();
        }
        else if (authentication instanceof UsernamePasswordAuthenticationToken) {
            provider = LOCAL_BASIC_PROVIDER;
            subject = authentication.getName();
        }
        else {
            provider = null;
            subject = null;
        }
        if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_RUN_PRINCIPAL",
                    "Çalıştırma kullanıcısı kimliği çözümlenemedi.");
        }
        return store.findActiveActor(provider, subject)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "RUN_ACTOR_NOT_PROVISIONED",
                        "Çalıştırma kullanıcısı Akış kullanıcı kataloğunda aktif değil."));
    }
}

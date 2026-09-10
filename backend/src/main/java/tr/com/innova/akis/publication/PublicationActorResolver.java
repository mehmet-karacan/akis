package tr.com.innova.akis.publication;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.publication.PublicationModels.ApprovalActor;

@Component
final class PublicationActorResolver {

    private static final String LOCAL_BASIC_PROVIDER = "LOCAL_BASIC";
    private final PublicationStore store;

    PublicationActorResolver(PublicationStore store) {
        this.store = store;
    }

    ApprovalActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Onay için kimlik doğrulaması gereklidir.");
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
                    "INVALID_APPROVER_PRINCIPAL",
                    "Onaylayan kullanıcı kimliği çözümlenemedi.");
        }
        return store.findActiveActor(provider, subject)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "APPROVER_NOT_PROVISIONED",
                        "Onaylayan kullanıcı Akış kullanıcı kataloğunda aktif değil."));
    }
}

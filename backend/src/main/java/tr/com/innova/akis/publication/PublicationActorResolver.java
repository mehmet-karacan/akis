package tr.com.innova.akis.publication;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.publication.PublicationModels.ApprovalActor;
import tr.com.innova.akis.security.ApplicationUserPrincipal;

@Component
final class PublicationActorResolver {

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

        if (!(authentication.getPrincipal() instanceof ApplicationUserPrincipal principal)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_APPROVER_PRINCIPAL",
                    "Onaylayan kullanıcı kimliği çözümlenemedi.");
        }
        return store.findActiveActor(principal.userId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "APPROVER_NOT_PROVISIONED",
                        "Onaylayan kullanıcı Akış kullanıcı kataloğunda aktif değil."));
    }
}

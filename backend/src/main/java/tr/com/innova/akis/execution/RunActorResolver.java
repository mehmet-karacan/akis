package tr.com.innova.akis.execution;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.ApplicationUserPrincipal;

@Component
class RunActorResolver {

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

        if (!(authentication.getPrincipal() instanceof ApplicationUserPrincipal principal)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_RUN_PRINCIPAL",
                    "Çalıştırma kullanıcısı kimliği çözümlenemedi.");
        }
        return store.findActiveActor(principal.userId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "RUN_ACTOR_NOT_PROVISIONED",
                        "Çalıştırma kullanıcısı Akış kullanıcı kataloğunda aktif değil."));
    }
}

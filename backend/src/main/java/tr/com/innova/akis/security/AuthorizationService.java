package tr.com.innova.akis.security;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;
import tr.com.innova.akis.security.AuthorizationRepository.ProjectAccess;

@Service
public class AuthorizationService {

    private final AuthorizationRepository repository;

    @Autowired
    public AuthorizationService(AuthorizationRepository repository) {
        this.repository = repository;
    }

    /** Kept package/API compatible while callers migrate; mode no longer grants a bypass. */
    public AuthorizationService(AuthorizationRepository repository, String ignoredLegacyMode) {
        this(repository);
    }

    /**
     * Requires an active project membership whose active project role grants
     * the requested permission. A caller without visibility receives the same
     * response as a missing project, avoiding project enumeration.
     */
    public void requireProjectPermission(UUID projectUuid, String permissionCode) {
        UUID safeProjectUuid = requiredProjectUuid(projectUuid);
        String safePermissionCode = normalizedPermission(permissionCode);
        Authentication authentication = currentAuthentication();
        PrincipalIdentity principal = resolvePrincipal(authentication);
        ProjectAccess access = repository.projectAccess(
                principal, safeProjectUuid, safePermissionCode);
        if (!access.visible()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PROJECT_NOT_FOUND",
                    "Proje bulunamadı.");
        }
        if (!access.permitted()) {
            throw permissionDenied();
        }
    }

    /** Requires a permission assigned through an active system role. */
    public void requireSystemPermission(String permissionCode) {
        String safePermissionCode = normalizedPermission(permissionCode);
        Authentication authentication = currentAuthentication();
        PrincipalIdentity principal = resolvePrincipal(authentication);
        if (!repository.hasSystemPermission(principal, safePermissionCode)) {
            throw permissionDenied();
        }
    }

    /** Lists only projects visible through active membership. */
    public Set<UUID> visibleProjectUuids() {
        Authentication authentication = currentAuthentication();
        return repository.visibleProjectUuids(resolvePrincipal(authentication));
    }

    /** Returns the authenticated application user code for audit use. */
    public String currentPrincipalName() {
        return resolvePrincipal(currentAuthentication()).name();
    }

    /** Returns the stable application user identity used by audited workflows. */
    public PrincipalIdentity currentPrincipalIdentity() {
        return resolvePrincipal(currentAuthentication());
    }

    public ProjectAuthorization projectAuthorization(UUID projectUuid) {
        UUID safeProjectUuid = requiredProjectUuid(projectUuid);
        Authentication authentication = currentAuthentication();
        PrincipalIdentity principal = resolvePrincipal(authentication);
        ProjectAccess visibility = repository.projectAccess(principal, safeProjectUuid, PermissionCodes.PROJECT_READ);
        if (!visibility.visible()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Proje bulunamadı.");
        }
        var grants = repository.projectGrants(principal, safeProjectUuid);
        return new ProjectAuthorization(
                grants.stream().map(AuthorizationRepository.ProjectGrant::roleCode).collect(Collectors.toUnmodifiableSet()),
                grants.stream().map(AuthorizationRepository.ProjectGrant::permissionCode).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet()));
    }

    public record ProjectAuthorization(Set<String> roles, Set<String> permissions) { }

    PrincipalIdentity resolvePrincipal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Kimlik doğrulaması gereklidir.");
        }

        if (authentication.getPrincipal() instanceof ApplicationUserPrincipal principal
                && principal.enabled()) {
            return new PrincipalIdentity(
                    principal.userId(), principal.userUuid(), principal.userCode(), principal.displayName());
        }

        throw new ApiException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_APPLICATION_PRINCIPAL",
                "Uygulama kullanıcısı kimliği çözümlenemedi.");
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private UUID requiredProjectUuid(UUID projectUuid) {
        if (projectUuid == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUTHORIZATION_REQUEST",
                    "Proje UUID değeri gereklidir.");
        }
        return projectUuid;
    }

    private String normalizedPermission(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUTHORIZATION_REQUEST",
                    "Yetki kodu gereklidir.");
        }
        return permissionCode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private ApiException permissionDenied() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "PERMISSION_DENIED",
                "Bu işlem için yetkiniz yok.");
    }
}

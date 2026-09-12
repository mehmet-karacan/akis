package tr.com.innova.akis.security;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;
import tr.com.innova.akis.security.AuthorizationRepository.ProjectAccess;

@Service
public class AuthorizationService {

    static final String LOCAL_BASIC_PROVIDER = "LOCAL_BASIC";
    private static final String LOCAL_DEVELOPER_ROLE = "ROLE_LOCAL_DEVELOPER";

    private final AuthorizationRepository repository;
    private final String securityMode;

    public AuthorizationService(
            AuthorizationRepository repository,
            @Value("${akis.security.mode:fail-closed}") String securityMode) {
        this.repository = repository;
        this.securityMode = SecuritySupport.mode(securityMode);
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
        if (isLoopbackDevelopmentAdministrator(authentication)) {
            return;
        }

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
        if (isLoopbackDevelopmentAdministrator(authentication)) {
            return;
        }

        PrincipalIdentity principal = resolvePrincipal(authentication);
        if (!repository.hasSystemPermission(principal, safePermissionCode)) {
            throw permissionDenied();
        }
    }

    /** Lists only projects visible through active membership; local loopback development is unrestricted. */
    public Set<UUID> visibleProjectUuids() {
        Authentication authentication = currentAuthentication();
        if (isLoopbackDevelopmentAdministrator(authentication)) {
            return repository.activeProjectUuids();
        }
        return repository.visibleProjectUuids(resolvePrincipal(authentication));
    }

    /** Returns the authenticated OIDC or HTTP Basic principal name for audit use. */
    public String currentPrincipalName() {
        return resolvePrincipal(currentAuthentication()).name();
    }

    /** Returns the stable provider/subject pair used by audited provisioning workflows. */
    public PrincipalIdentity currentPrincipalIdentity() {
        return resolvePrincipal(currentAuthentication());
    }

    public ProjectAuthorization projectAuthorization(UUID projectUuid) {
        UUID safeProjectUuid = requiredProjectUuid(projectUuid);
        Authentication authentication = currentAuthentication();
        if (isLoopbackDevelopmentAdministrator(authentication)) {
            return new ProjectAuthorization(Set.of("GELISTIRICI"), Set.of(
                    PermissionCodes.PROJECT_READ, PermissionCodes.PROJECT_WRITE,
                    PermissionCodes.DEFINITION_READ, PermissionCodes.DEFINITION_WRITE, PermissionCodes.DEFINITION_VALIDATE,
                    PermissionCodes.TOPOLOGY_READ, PermissionCodes.TOPOLOGY_WRITE,
                    PermissionCodes.CATALOG_READ, PermissionCodes.CATALOG_WRITE,
                    PermissionCodes.SCENARIO_READ, PermissionCodes.SCENARIO_COMPILE, PermissionCodes.PUBLICATION_APPROVE,
                    PermissionCodes.RUN_READ, PermissionCodes.RUN_START, PermissionCodes.RUN_CANCEL,
                    PermissionCodes.PROJECT_MEMBERSHIP_MANAGE));
        }
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

        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String issuer = jwtAuthentication.getToken().getIssuer() == null
                    ? null
                    : jwtAuthentication.getToken().getIssuer().toString();
            String subject = jwtAuthentication.getToken().getSubject();
            if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
                throw new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_OIDC_PRINCIPAL",
                        "OIDC issuer ve subject bilgileri gereklidir.");
            }
            return new PrincipalIdentity(issuer, subject, authentication.getName());
        }

        if (authentication instanceof UsernamePasswordAuthenticationToken) {
            String name = authentication.getName();
            if (name == null || name.isBlank()) {
                throw new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_BASIC_PRINCIPAL",
                        "HTTP Basic kullanıcı adı gereklidir.");
            }
            return new PrincipalIdentity(LOCAL_BASIC_PROVIDER, name, name);
        }

        throw new ApiException(
                HttpStatus.UNAUTHORIZED,
                "UNSUPPORTED_PRINCIPAL",
                "Desteklenmeyen kimlik doğrulama türü.");
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private boolean isLoopbackDevelopmentAdministrator(Authentication authentication) {
        if (!"development".equals(securityMode)
                || authentication == null
                || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> LOCAL_DEVELOPER_ROLE.equals(authority.getAuthority()))) {
            return false;
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        return isLoopback(attributes.getRequest());
    }

    private boolean isLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address != null && (address.equals("::1")
                || address.equals("0:0:0:0:0:0:0:1")
                || address.startsWith("127."));
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
        return permissionCode.trim().toUpperCase(Locale.ROOT);
    }

    private ApiException permissionDenied() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "PERMISSION_DENIED",
                "Bu işlem için yetkiniz yok.");
    }
}

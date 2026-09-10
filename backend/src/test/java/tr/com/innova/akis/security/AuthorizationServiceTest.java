package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;
import tr.com.innova.akis.security.AuthorizationRepository.ProjectAccess;

class AuthorizationServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();

    @AfterEach
    void cleanSecurityContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void authorizesOidcPrincipalThroughActiveProjectRolePermission() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(true, true), false);
        AuthorizationService service = new AuthorizationService(repository, "oidc");
        authenticateOidc("https://identity.example/realms/akis", "user-42", "mehmet");

        service.requireProjectPermission(PROJECT_UUID, " project.read ");

        assertEquals("https://identity.example/realms/akis", repository.principal.provider());
        assertEquals("user-42", repository.principal.subject());
        assertEquals("PROJECT.READ", repository.permissionCode);
        assertEquals("mehmet", service.currentPrincipalName());
    }

    @Test
    void hidesProjectWhenPrincipalHasNoActiveMembership() {
        AuthorizationService service = new AuthorizationService(
                new FakeRepository(new ProjectAccess(false, false), false), "oidc");
        authenticateOidc("https://identity.example", "outsider", "outsider");

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireProjectPermission(PROJECT_UUID, "PROJECT.READ"));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("PROJECT_NOT_FOUND", error.code());
    }

    @Test
    void deniesVisibleProjectWhenRoleLacksPermission() {
        AuthorizationService service = new AuthorizationService(
                new FakeRepository(new ProjectAccess(true, false), false), "oidc");
        authenticateOidc("https://identity.example", "member", "member");

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireProjectPermission(PROJECT_UUID, "PROJECT.WRITE"));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("PERMISSION_DENIED", error.code());
    }

    @Test
    void checksSystemPermissionWithOidcProviderAndSubject() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(false, false), true);
        AuthorizationService service = new AuthorizationService(repository, "oidc");
        authenticateOidc("https://identity.example", "administrator", "admin-name");

        service.requireSystemPermission(" system.admin ");

        assertEquals("https://identity.example", repository.principal.provider());
        assertEquals("administrator", repository.principal.subject());
        assertEquals("SYSTEM.ADMIN", repository.permissionCode);
    }

    @Test
    void grantsLocalDeveloperOnlyInDevelopmentModeFromLoopback() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(false, false), false);
        AuthorizationService service = new AuthorizationService(repository, "development");
        authenticateLocalDeveloper("local-user");
        requestFrom("127.0.0.1");

        service.requireProjectPermission(PROJECT_UUID, "PROJECT.WRITE");
        service.requireSystemPermission("SYSTEM.ADMIN");

        assertEquals(0, repository.callCount);
        assertEquals("local-user", service.currentPrincipalName());
    }

    @Test
    void doesNotBypassRepositoryOutsideLoopback() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(false, false), false);
        AuthorizationService service = new AuthorizationService(repository, "development");
        authenticateLocalDeveloper("local-user");
        requestFrom("10.0.0.8");

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireProjectPermission(PROJECT_UUID, "PROJECT.READ"));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals(AuthorizationService.LOCAL_BASIC_PROVIDER, repository.principal.provider());
        assertEquals(1, repository.callCount);
    }

    @Test
    void doesNotGrantLocalDeveloperBypassOutsideDevelopmentMode() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(true, false), false);
        AuthorizationService service = new AuthorizationService(repository, "oidc");
        authenticateLocalDeveloper("local-user");
        requestFrom("::1");

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireProjectPermission(PROJECT_UUID, "PROJECT.WRITE"));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals(1, repository.callCount);
    }

    @Test
    void rejectsOidcTokenWithoutIssuer() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(true, true), true);
        AuthorizationService service = new AuthorizationService(repository, "oidc");
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-42")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("SCOPE_openid"))));

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireSystemPermission("SYSTEM.ADMIN"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("INVALID_OIDC_PRINCIPAL", error.code());
    }

    @Test
    void validatesAuthorizationRequestEvenForDevelopmentAdministrator() {
        AuthorizationService service = new AuthorizationService(
                new FakeRepository(new ProjectAccess(false, false), false), "development");
        authenticateLocalDeveloper("local-user");
        requestFrom("127.0.0.1");

        ApiException missingProject = assertThrows(ApiException.class,
                () -> service.requireProjectPermission(null, "PROJECT.READ"));
        ApiException missingPermission = assertThrows(ApiException.class,
                () -> service.requireSystemPermission(" "));

        assertEquals(HttpStatus.BAD_REQUEST, missingProject.status());
        assertEquals("INVALID_AUTHORIZATION_REQUEST", missingProject.code());
        assertEquals(HttpStatus.BAD_REQUEST, missingPermission.status());
    }

    private void authenticateOidc(String issuer, String subject, String principalName) {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuer(issuer)
                .subject(subject)
                .claim("preferred_username", principalName)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("SCOPE_openid")), principalName));
    }

    private void authenticateLocalDeveloper(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        username,
                        "not-used",
                        List.of(new SimpleGrantedAuthority("ROLE_LOCAL_DEVELOPER"))));
    }

    private void requestFrom(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static final class FakeRepository extends AuthorizationRepository {

        private final ProjectAccess projectAccess;
        private final boolean systemPermission;
        private PrincipalIdentity principal;
        private String permissionCode;
        private int callCount;

        private FakeRepository(ProjectAccess projectAccess, boolean systemPermission) {
            super(null);
            this.projectAccess = projectAccess;
            this.systemPermission = systemPermission;
        }

        @Override
        public ProjectAccess projectAccess(
                PrincipalIdentity principal,
                UUID projectUuid,
                String permissionCode) {
            this.principal = principal;
            this.permissionCode = permissionCode;
            callCount++;
            return projectAccess;
        }

        @Override
        public boolean hasSystemPermission(
                PrincipalIdentity principal,
                String permissionCode) {
            this.principal = principal;
            this.permissionCode = permissionCode;
            callCount++;
            return systemPermission;
        }
    }
}

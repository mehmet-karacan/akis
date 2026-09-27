package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;
import tr.com.innova.akis.security.AuthorizationRepository.ProjectAccess;
import tr.com.innova.akis.security.AuthorizationRepository.ProjectGrant;

class AuthorizationServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID USER_UUID = UUID.randomUUID();

    @AfterEach
    void cleanSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authorizesTrustedApplicationUserByStableUserId() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(true, true), false);
        AuthorizationService service = new AuthorizationService(repository);
        authenticate(42, "mehmet");

        service.requireProjectPermission(PROJECT_UUID, " project.read ");

        assertEquals(42, repository.principal.userId());
        assertEquals(USER_UUID, repository.principal.userUuid());
        assertEquals("PROJECT.READ", repository.permissionCode);
        assertEquals("Test User", service.currentPrincipalName());
    }

    @Test
    void hidesProjectWhenUserHasNoActiveMembership() {
        AuthorizationService service = new AuthorizationService(
                new FakeRepository(new ProjectAccess(false, false), false));
        authenticate(7, "outsider");

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireProjectPermission(PROJECT_UUID, "PROJECT.READ"));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("PROJECT_NOT_FOUND", error.code());
    }

    @Test
    void deniesVisibleProjectWhenRoleLacksPermission() {
        AuthorizationService service = new AuthorizationService(
                new FakeRepository(new ProjectAccess(true, false), false));
        authenticate(8, "member");

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireProjectPermission(PROJECT_UUID, "PROJECT.WRITE"));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
    }

    @Test
    void projectsCurrentRolesAndPermissionsForRoleAwareUi() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(true, true), false);
        repository.grants = List.of(
                new ProjectGrant("OPERASYON", PermissionCodes.PROJECT_READ),
                new ProjectGrant("OPERASYON", PermissionCodes.RUN_READ));
        AuthorizationService service = new AuthorizationService(repository);
        authenticate(9, "operator");

        var access = service.projectAuthorization(PROJECT_UUID);

        assertEquals(java.util.Set.of("OPERASYON"), access.roles());
        assertEquals(java.util.Set.of(PermissionCodes.PROJECT_READ, PermissionCodes.RUN_READ), access.permissions());
    }

    @Test
    void checksSystemPermissionWithoutDevelopmentBypass() {
        FakeRepository repository = new FakeRepository(new ProjectAccess(false, false), true);
        AuthorizationService service = new AuthorizationService(repository);
        authenticate(10, "administrator");

        service.requireSystemPermission(" system.admin ");

        assertEquals(10, repository.principal.userId());
        assertEquals("SYSTEM.ADMIN", repository.permissionCode);
        assertEquals(1, repository.callCount);
    }

    @Test
    void rejectsAnyPrincipalNotIssuedByTheApplicationAuthenticationProvider() {
        AuthorizationService service = new AuthorizationService(
                new FakeRepository(new ProjectAccess(true, true), true));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("forged", "", List.of()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireSystemPermission("SYSTEM.ADMIN"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("INVALID_APPLICATION_PRINCIPAL", error.code());
    }

    private void authenticate(long userId, String userCode) {
        var principal = new ApplicationUserPrincipal(
                userId, USER_UUID, userCode, "Test User", "{argon2}test", true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static final class FakeRepository extends AuthorizationRepository {
        private final ProjectAccess access;
        private final boolean systemPermission;
        private PrincipalIdentity principal;
        private String permissionCode;
        private int callCount;
        private List<ProjectGrant> grants = List.of();

        private FakeRepository(ProjectAccess access, boolean systemPermission) {
            super(null); this.access = access; this.systemPermission = systemPermission;
        }

        @Override public ProjectAccess projectAccess(PrincipalIdentity principal, UUID projectUuid, String permissionCode) {
            this.principal = principal; this.permissionCode = permissionCode; callCount++; return access;
        }

        @Override public boolean hasSystemPermission(PrincipalIdentity principal, String permissionCode) {
            this.principal = principal; this.permissionCode = permissionCode; callCount++; return systemPermission;
        }

        @Override public List<ProjectGrant> projectGrants(PrincipalIdentity principal, UUID projectUuid) {
            return grants;
        }
    }
}

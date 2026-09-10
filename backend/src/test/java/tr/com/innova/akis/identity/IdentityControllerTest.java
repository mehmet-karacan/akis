package tr.com.innova.akis.identity;

import static tr.com.innova.akis.security.PermissionCodes.IDENTITY_USER_PROVISION;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_MEMBERSHIP_MANAGE;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.identity.IdentityModels.MembershipRow;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleView;
import tr.com.innova.akis.identity.IdentityModels.UserRow;
import tr.com.innova.akis.security.AuthorizationService;

class IdentityControllerTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID USER_UUID = UUID.randomUUID();

    @Test
    void protectsUserProvisioningWithSystemPermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        IdentityController controller = new IdentityController(
                new StubIdentityService(), authorization);

        controller.listOidcUsers();

        assertEquals(
                IDENTITY_USER_PROVISION,
                authorization.systemPermission);
    }

    @Test
    void protectsMembershipManagementWithProjectPermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        IdentityController controller = new IdentityController(
                new StubIdentityService(), authorization);

        controller.listMemberships(PROJECT_UUID);

        assertEquals(PROJECT_UUID, authorization.projectUuid);
        assertEquals(
                PROJECT_MEMBERSHIP_MANAGE,
                authorization.projectPermission);
    }

    private static final class CapturingAuthorization extends AuthorizationService {

        private String systemPermission;
        private UUID projectUuid;
        private String projectPermission;

        private CapturingAuthorization() {
            super(null, "fail-closed");
        }

        @Override
        public void requireSystemPermission(String permissionCode) {
            systemPermission = permissionCode;
        }

        @Override
        public void requireProjectPermission(UUID projectUuid, String permissionCode) {
            this.projectUuid = projectUuid;
            projectPermission = permissionCode;
        }
    }

    private static final class StubIdentityService extends IdentityService {

        private StubIdentityService() {
            super(null, Clock.systemUTC());
        }

        @Override
        public List<UserRow> listOidcUsers() {
            return List.of();
        }

        @Override
        public List<MembershipRow> listMemberships(UUID projectUuid) {
            return List.of(membership(projectUuid));
        }

        private MembershipRow membership(UUID projectUuid) {
            return new MembershipRow(
                    1,
                    UUID.randomUUID(),
                    2,
                    projectUuid,
                    3,
                    USER_UUID,
                    "AKTIF",
                    OffsetDateTime.of(2026, 9, 11, 9, 0, 0, 0, ZoneOffset.UTC),
                    null,
                    1,
                    List.of(new ProjectRoleView(UUID.randomUUID(), "IZLEYICI")));
        }
    }
}

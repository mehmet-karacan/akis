package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectAuthorizationControllerTest {
    @Test
    void exposesOnlyServerResolvedRoleAndPermissionCodes() {
        var service = new AuthorizationService(null, "fail-closed") {
            @Override public ProjectAuthorization projectAuthorization(UUID projectUuid) {
                return new ProjectAuthorization(Set.of("OPERASYON"), Set.of(PermissionCodes.RUN_READ));
            }
        };
        var view = new ProjectAuthorizationController(service).get(UUID.randomUUID());
        assertEquals(Set.of("OPERASYON"), view.roles());
        assertEquals(Set.of(PermissionCodes.RUN_READ), view.permissions());
    }
}

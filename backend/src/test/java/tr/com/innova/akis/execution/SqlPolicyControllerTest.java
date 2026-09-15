package tr.com.innova.akis.execution;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.security.AuthorizationService;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

class SqlPolicyControllerTest {
    @Test
    void authorizesProjectBeforeInspectingSql() {
        UUID project = UUID.randomUUID();
        var auth = new AuthorizationService(null, "fail-closed") {
            @Override public void requireProjectPermission(UUID actual, String permission) {
                assertEquals(project, actual);
                assertEquals(PROJECT_READ, permission);
                throw new SecurityException("denied");
            }
        };
        var controller = new SqlPolicyController(auth);
        assertThrows(SecurityException.class, () -> controller.validate(project,
                new SqlPolicyController.Input("SELECT 1 FROM DUAL", "SOURCE")));
    }
}

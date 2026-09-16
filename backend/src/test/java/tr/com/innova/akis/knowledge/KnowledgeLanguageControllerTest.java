package tr.com.innova.akis.knowledge;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.security.AuthorizationService;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

class KnowledgeLanguageControllerTest {
    private final UUID project = UUID.randomUUID();
    private KnowledgeLanguageController controller(boolean allow) {
        return new KnowledgeLanguageController(new AuthorizationService(null, "fail-closed") {
            @Override public void requireProjectPermission(UUID id, String permission) {
                assertEquals(project, id);
                assertEquals(PROJECT_READ, permission);
                if (!allow) throw new SecurityException("denied");
            }
        });
    }
    @Test void authorizesBothEndpointsBeforeWork() {
        assertThrows(SecurityException.class, () -> controller(false).templates(project));
        assertThrows(SecurityException.class, () -> controller(false).validate(project, null));
    }
    @Test void validationDoesNotClaimRuntimeReadiness() {
        var controller = controller(true);
        for (var template : controller.templates(project)) {
            var result = controller.validate(project, new KnowledgeLanguageController.Input(template.source()));
            assertTrue(result.valid());
            assertFalse(result.runnable());
        }
    }
    @Test void returnsLineDiagnosticWithoutProgramOnInvalidSource() {
        var result = controller(true).validate(project, new KnowledgeLanguageController.Input("AKIS_KM/1\nINVALID"));
        assertFalse(result.valid());
        assertEquals(2, result.line());
        assertNull(result.program());
    }
}

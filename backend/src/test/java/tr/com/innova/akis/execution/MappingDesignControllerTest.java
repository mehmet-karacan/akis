package tr.com.innova.akis.execution;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.security.AuthorizationService;
import static org.junit.jupiter.api.Assertions.*;

class MappingDesignControllerTest {
    private final JsonMapper mapper = new JsonMapper();
    private final UUID project = UUID.randomUUID();
    private MappingDesignController controller(boolean allow) {
        return new MappingDesignController(new AuthorizationService(null, "fail-closed") {
            @Override public void requireProjectPermission(UUID actual, String permission) {
                assertEquals(project, actual);
                assertEquals(tr.com.innova.akis.security.PermissionCodes.PROJECT_READ, permission);
                if (!allow) throw new SecurityException("denied");
            }
        }, new PilotRuntimePlanResolver(mapper, new SecretValueSanitizer()), new DefinitionContentValidator());
    }
    private ObjectNode content() {
        return (ObjectNode) mapper.readTree("""
            {"datasets":[{"id":"S","role":"SOURCE","ui":{"name":"Source","dataObjectUuid":"hint"}},
                         {"id":"T","role":"TARGET"}],
             "columnMappings":[{"source":{"dataset":"S","column":"ID"},"target":{"dataset":"T","column":"ID"}}],
             "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"}}
            """);
    }
    @Test void supportsAuthoringHintsButNeverClaimsExecution() {
        var result = controller(true).assess(project, new MappingDesignController.Input(2, content()));
        assertTrue(result.shapeSupported());
        assertFalse(result.executionVerified());
        assertEquals(1000, result.maximumSourceRows());
        assertTrue(result.remainingChecks().contains("VERSIONED_BINDINGS"));
    }
    @Test void rejectsLegacySchemaAndUnsupportedStrategy() {
        assertFalse(controller(true).assess(project, new MappingDesignController.Input(1, content())).shapeSupported());
        var input = content();
        ((ObjectNode) input.get("writeStrategy")).put("kind", "APPEND");
        assertEquals("UNSUPPORTED_WRITE_STRATEGY", controller(true).assess(project,
                new MappingDesignController.Input(2, input)).reasonCode());
    }
    @Test void doesNotSilentlyDiscardSemanticFields() {
        var input = content();
        ((ObjectNode) input.get("datasets").get(0)).put("unsafeSql", "secret-text");
        var result = controller(true).assess(project, new MappingDesignController.Input(2, input));
        assertFalse(result.shapeSupported());
        assertFalse(result.toString().contains("secret-text"));
    }
    @Test void authorizesBeforeValidation() {
        assertThrows(SecurityException.class, () -> controller(false).assess(project,
                new MappingDesignController.Input(2, null)));
    }
}

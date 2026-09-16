package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

/** No repositories, JDBC or executors: this cannot certify physical execution. */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/mapping-design")
final class MappingDesignController {
    private final AuthorizationService authorization;
    private final PilotRuntimePlanResolver resolver;
    private final DefinitionContentValidator validator;

    MappingDesignController(AuthorizationService authorization, PilotRuntimePlanResolver resolver,
                            DefinitionContentValidator validator) {
        this.authorization = authorization;
        this.resolver = resolver;
        this.validator = validator;
    }

    record Input(int schemaVersion, JsonNode content) { }
    record Assessment(boolean shapeSupported, boolean executionVerified, String capability,
                      String reasonCode, int maximumSourceRows, List<String> remainingChecks) { }

    @PostMapping("/assess")
    Assessment assess(@PathVariable UUID projectUuid, @RequestBody Input input) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        try {
            validator.validate(DefinitionType.MAPPING, input.schemaVersion(), input.content());
            resolver.validateDraftShape(input.schemaVersion(), input.content());
            return new Assessment(true, false, PilotRuntimePlanResolver.PILOT_CAPABILITY, null,
                    PilotRuntimePlan.MAXIMUM_SOURCE_ROWS,
                    List.of("VERSIONED_BINDINGS", "ENVIRONMENT_PUBLICATION", "ORACLE_PREFLIGHT", "RUNTIME_FLAGS"));
        } catch (PilotRuntimePlanException invalid) {
            return rejected(input.schemaVersion() == 2 ? invalid.failure().name() : "MAPPING_SCHEMA_VERSION_REQUIRED");
        } catch (ApiException invalid) {
            return rejected("INVALID_MAPPING_CONTENT");
        }
    }

    private Assessment rejected(String code) {
        return new Assessment(false, false, PilotRuntimePlanResolver.DEFINITION_ONLY_CAPABILITY,
                code, PilotRuntimePlan.MAXIMUM_SOURCE_ROWS, List.of());
    }
}

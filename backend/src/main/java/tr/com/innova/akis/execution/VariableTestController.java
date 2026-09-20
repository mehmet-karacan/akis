package tr.com.innova.akis.execution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/definitions/{definitionUuid}/value-tests")
final class VariableTestController {
    private final VariableTestService service;
    private final AuthorizationService authorization;
    VariableTestController(VariableTestService service, AuthorizationService authorization) {
        this.service = service; this.authorization = authorization;
    }
    record Request(@NotNull UUID logicalSchemaUuid, @NotNull UUID environmentUuid,
                   @NotBlank @Size(max=20) String dataType, @NotBlank @Size(max=20000) String query) { }
    @PostMapping
    VariableTestService.Result test(@PathVariable UUID projectUuid, @PathVariable UUID definitionUuid,
                                   @Valid @RequestBody Request request) {
        authorization.requireProjectPermission(projectUuid, DEFINITION_WRITE);
        authorization.requireProjectPermission(projectUuid, RUN_START);
        return service.test(projectUuid, definitionUuid, request);
    }
    @GetMapping
    List<VariableTestService.Result> history(@PathVariable UUID projectUuid, @PathVariable UUID definitionUuid,
            @RequestParam(defaultValue="9223372036854775807") long before) {
        authorization.requireProjectPermission(projectUuid, DEFINITION_READ);
        return service.history(projectUuid, definitionUuid, before);
    }
}

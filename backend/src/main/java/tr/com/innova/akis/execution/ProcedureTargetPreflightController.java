package tr.com.innova.akis.execution;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.PUBLICATION_READ;
import static tr.com.innova.akis.security.PermissionCodes.RUN_START;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/procedure-preflights/target")
final class ProcedureTargetPreflightController {

    private final ProcedureTargetPreflightService service;
    private final AuthorizationService authorization;

    ProcedureTargetPreflightController(
            ProcedureTargetPreflightService service,
            AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ProcedureTargetPreflightService.Result preflight(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody Request request) {
        authorization.requireProjectPermission(projectUuid, PUBLICATION_READ);
        authorization.requireProjectPermission(projectUuid, RUN_START);
        return service.preflight(projectUuid, request.publicationUuid());
    }

    record Request(@NotNull UUID publicationUuid) {
    }
}

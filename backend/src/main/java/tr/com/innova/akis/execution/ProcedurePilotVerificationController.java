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
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/procedure-verifications")
final class ProcedurePilotVerificationController {
    private final ProcedurePilotVerificationService service;
    private final AuthorizationService authorization;

    ProcedurePilotVerificationController(
            ProcedurePilotVerificationService service,
            AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ProcedurePilotVerificationService.Result verify(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody Request request) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        return service.verify(projectUuid, request.publicationUuid());
    }

    record Request(@NotNull UUID publicationUuid) { }
}

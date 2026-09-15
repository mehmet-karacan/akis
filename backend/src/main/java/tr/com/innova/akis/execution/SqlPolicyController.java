package tr.com.innova.akis.execution;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.metadata.ApiException;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/sql")
final class SqlPolicyController {
    private final AuthorizationService authorization;
    SqlPolicyController(AuthorizationService authorization) { this.authorization = authorization; }

    record Input(String command, String connectionRole) { }

    @PostMapping("/validate")
    SqlStatementPolicy.Diagnostic validate(@PathVariable UUID projectUuid, @RequestBody Input input) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        try {
            return SqlStatementPolicy.inspect(input.command(), input.connectionRole());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SQL_POLICY_REJECTED", invalid.getMessage());
        }
    }
}

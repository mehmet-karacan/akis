package tr.com.innova.akis.security;

import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/access")
public class ProjectAuthorizationController {
    private final AuthorizationService authorization;
    public ProjectAuthorizationController(AuthorizationService authorization) { this.authorization = authorization; }

    @GetMapping
    public ProjectAccessView get(@PathVariable UUID projectUuid) {
        var access = authorization.projectAuthorization(projectUuid);
        return new ProjectAccessView(access.roles(), access.permissions());
    }

    public record ProjectAccessView(Set<String> roles, Set<String> permissions) { }
}

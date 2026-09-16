package tr.com.innova.akis.topology;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.knowledge.WorkAreaPolicyService;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/physical-schemas/{schemaUuid}/work-area-policy")
final class WorkAreaPolicyController {
    private final AuthorizationService auth;
    private final WorkAreaPolicyService policies;
    WorkAreaPolicyController(AuthorizationService auth,WorkAreaPolicyService policies) { this.auth=auth; this.policies=policies; }
    record Input(long expectedVersion,WorkAreaPolicyService.Policy policy) { }
    @GetMapping
    WorkAreaPolicyService.View get(@PathVariable UUID projectUuid,@PathVariable UUID schemaUuid) {
        auth.requireProjectPermission(projectUuid,TOPOLOGY_READ); return policies.get(projectUuid,schemaUuid);
    }
    @PutMapping
    WorkAreaPolicyService.View save(@PathVariable UUID projectUuid,@PathVariable UUID schemaUuid,@RequestBody Input input) {
        auth.requireProjectPermission(projectUuid,TOPOLOGY_WRITE); return policies.save(projectUuid,schemaUuid,input.expectedVersion(),input.policy());
    }
}

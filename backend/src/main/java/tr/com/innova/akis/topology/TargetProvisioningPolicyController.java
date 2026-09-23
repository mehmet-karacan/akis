package tr.com.innova.akis.topology;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_READ;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_WRITE;

/** Read/write for the target DDL provisioning policy of one physical schema (see V048, {@link TargetProvisioningPolicyService}). */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/physical-schemas/{schemaUuid}/target-provisioning-policy")
final class TargetProvisioningPolicyController {
    private final AuthorizationService auth;
    private final TargetProvisioningPolicyService policies;

    TargetProvisioningPolicyController(AuthorizationService auth, TargetProvisioningPolicyService policies) {
        this.auth = auth; this.policies = policies;
    }

    record Input(long expectedVersion, TargetProvisioningPolicyService.Policy policy) { }

    @GetMapping
    TargetProvisioningPolicyService.View get(@PathVariable UUID projectUuid, @PathVariable UUID schemaUuid) {
        auth.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return policies.get(projectUuid, schemaUuid);
    }

    @PutMapping
    TargetProvisioningPolicyService.View save(@PathVariable UUID projectUuid, @PathVariable UUID schemaUuid, @RequestBody Input input) {
        auth.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return policies.save(projectUuid, schemaUuid, input.expectedVersion(), input.policy());
    }
}

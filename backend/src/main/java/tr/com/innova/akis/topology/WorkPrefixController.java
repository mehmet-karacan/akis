package tr.com.innova.akis.topology;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.knowledge.WorkObjectPrefixes;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}")
final class WorkPrefixController {
    private final AuthorizationService auth;
    private final WorkPrefixService service;
    WorkPrefixController(AuthorizationService auth, WorkPrefixService service) { this.auth=auth; this.service=service; }
    record Input(long expectedVersion, WorkObjectPrefixes prefixes) { }
    @GetMapping("/connections/{connectionUuid}/work-prefixes")
    WorkPrefixService.View getConnection(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid) {
        auth.requireProjectPermission(projectUuid,TOPOLOGY_READ); return service.get(projectUuid,connectionUuid,null);
    }
    @PutMapping("/connections/{connectionUuid}/work-prefixes")
    WorkPrefixService.View saveConnection(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid, @RequestBody Input input) {
        auth.requireProjectPermission(projectUuid,TOPOLOGY_WRITE); return service.save(projectUuid,connectionUuid,null,input.expectedVersion(),input.prefixes());
    }
    @GetMapping("/physical-schemas/{schemaUuid}/work-prefixes")
    WorkPrefixService.View getSchema(@PathVariable UUID projectUuid, @PathVariable UUID schemaUuid) {
        auth.requireProjectPermission(projectUuid,TOPOLOGY_READ); return service.get(projectUuid,null,schemaUuid);
    }
    @PutMapping("/physical-schemas/{schemaUuid}/work-prefixes")
    WorkPrefixService.View saveSchema(@PathVariable UUID projectUuid, @PathVariable UUID schemaUuid, @RequestBody Input input) {
        auth.requireProjectPermission(projectUuid,TOPOLOGY_WRITE); return service.save(projectUuid,null,schemaUuid,input.expectedVersion(),input.prefixes());
    }
    @DeleteMapping("/physical-schemas/{schemaUuid}/work-prefixes")
    WorkPrefixService.View inherit(@PathVariable UUID projectUuid, @PathVariable UUID schemaUuid, @RequestParam long expectedVersion) {
        auth.requireProjectPermission(projectUuid,TOPOLOGY_WRITE); return service.inherit(projectUuid,schemaUuid,expectedVersion);
    }
}

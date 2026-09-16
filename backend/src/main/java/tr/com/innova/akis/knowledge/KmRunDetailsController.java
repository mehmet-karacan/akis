package tr.com.innova.akis.knowledge;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/runs/{runUuid}/knowledge-modules")
final class KmRunDetailsController {
    private final AuthorizationService auth;private final KmStepJournal steps;private final WorkObjectStore objects;
    KmRunDetailsController(AuthorizationService auth,KmStepJournal steps,WorkObjectStore objects) {this.auth=auth;this.steps=steps;this.objects=objects;}
    record View(List<KmStepJournal.Row> steps,List<WorkObjectStore.ObjectRow> workObjects,KmStepJournal.Reconciliation reconciliation) { }
    @GetMapping
    View get(@PathVariable UUID projectUuid,@PathVariable UUID runUuid) {
        auth.requireProjectPermission(projectUuid,RUN_READ);return new View(steps.list(projectUuid,runUuid),objects.list(projectUuid,runUuid),steps.reconciliation(projectUuid,runUuid));
    }
}

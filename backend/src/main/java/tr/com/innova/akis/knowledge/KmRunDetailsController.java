package tr.com.innova.akis.knowledge;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;
import static tr.com.innova.akis.security.PermissionCodes.RUN_CANCEL;
import tr.com.innova.akis.execution.ReviewedWorkObjectCleanupService;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/runs/{runUuid}/knowledge-modules")
final class KmRunDetailsController {
    private final AuthorizationService auth;private final KmStepJournal steps;private final WorkObjectStore objects;private final ReviewedWorkObjectCleanupService cleanup;
    KmRunDetailsController(AuthorizationService auth,KmStepJournal steps,WorkObjectStore objects,ReviewedWorkObjectCleanupService cleanup) {this.auth=auth;this.steps=steps;this.objects=objects;this.cleanup=cleanup;}
    record View(List<KmStepJournal.Row> steps,List<WorkObjectStore.ObjectRow> workObjects,KmStepJournal.Reconciliation reconciliation) { }
    @GetMapping
    View get(@PathVariable UUID projectUuid,@PathVariable UUID runUuid) {
        auth.requireProjectPermission(projectUuid,RUN_READ);return new View(steps.list(projectUuid,runUuid),objects.list(projectUuid,runUuid),steps.reconciliation(projectUuid,runUuid));
    }
    @PostMapping("/work-objects/{objectUuid}:cleanup-reviewed")
    View cleanupReviewed(@PathVariable UUID projectUuid,@PathVariable UUID runUuid,@PathVariable UUID objectUuid) {
        auth.requireProjectPermission(projectUuid,RUN_CANCEL);cleanup.cleanup(projectUuid,runUuid,objectUuid);return get(projectUuid,runUuid);
    }
}

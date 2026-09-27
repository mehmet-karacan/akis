package tr.com.innova.akis.projectbundle;

import static tr.com.innova.akis.security.PermissionCodes.PROJECT_CREATE;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_WRITE;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_READ;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.projectbundle.ProjectBundleModels.ConflictPolicy;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ImportResult;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportPlan;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportRequest;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportResult;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetPlanRequest;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ValidationReport;
import tr.com.innova.akis.security.AuthorizationService;

@RestController
@RequestMapping("/api/v1")
final class ProjectBundleController {

    private final ProjectBundleService service;
    private final AuthorizationService authorization;

    ProjectBundleController(
            ProjectBundleService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/projects/{projectUuid}/bundle/export")
    ResponseEntity<ProjectBundle> export(
            @PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        ProjectBundle bundle = service.exportBundle(projectUuid);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + bundle.project().code() + "-bundle-v3.json\"")
                .body(bundle);
    }

    @PostMapping("/project-bundles/validate")
    ValidationReport validate(@RequestBody ProjectBundle bundle) {
        authorization.requireSystemPermission(PROJECT_CREATE);
        return service.validate(bundle);
    }

    @PostMapping("/project-bundles/import")
    ResponseEntity<ImportResult> importBundle(
            @RequestBody ProjectBundle bundle,
            @RequestParam(defaultValue = "FAIL") ConflictPolicy conflict,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        authorization.requireSystemPermission(PROJECT_CREATE);
        ImportResult result = service.importBundle(bundle, conflict, dryRun);
        if (result.dryRun()) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.created(
                URI.create("/api/v1/projects/" + result.projectUuid())).body(result);
    }

    @PostMapping("/projects/{targetProjectUuid}/bundle/plan")
    TargetImportPlan planTargetImport(
            @PathVariable UUID targetProjectUuid,
            @RequestBody TargetPlanRequest request) {
        authorization.requireProjectPermission(targetProjectUuid, PROJECT_WRITE);
        authorization.requireProjectPermission(targetProjectUuid, TOPOLOGY_READ);
        return service.planTargetImport(
                targetProjectUuid, request.bundle(), request.globalBindings());
    }

    @PostMapping("/projects/{targetProjectUuid}/bundle/import")
    ResponseEntity<TargetImportResult> importIntoTarget(
            @PathVariable UUID targetProjectUuid,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TargetImportRequest request) {
        authorization.requireProjectPermission(targetProjectUuid, PROJECT_WRITE);
        authorization.requireProjectPermission(targetProjectUuid, TOPOLOGY_READ);
        long actorId = authorization.currentPrincipalIdentity().userId();
        TargetImportResult result = service.importIntoTarget(
                targetProjectUuid, request, idempotencyKey, actorId);
        return result.replayed()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(result);
    }
}

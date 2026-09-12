package tr.com.innova.akis.projectbundle;

import static tr.com.innova.akis.security.PermissionCodes.PROJECT_CREATE;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.projectbundle.ProjectBundleModels.ConflictPolicy;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ImportResult;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
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
                        "attachment; filename=\"" + bundle.project().code() + "-bundle-v2.json\"")
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
}

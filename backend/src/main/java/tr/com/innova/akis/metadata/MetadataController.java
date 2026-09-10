package tr.com.innova.akis.metadata;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.metadata.MetadataModels.DefinitionRow;
import tr.com.innova.akis.metadata.MetadataModels.DraftRow;
import tr.com.innova.akis.metadata.MetadataModels.FolderRow;
import tr.com.innova.akis.metadata.MetadataModels.ProjectRow;
import tr.com.innova.akis.metadata.MetadataModels.VersionRow;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1")
final class MetadataController {

    private final MetadataService service;
    private final AuthorizationService authorization;

    MetadataController(MetadataService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/definition-types")
    List<DefinitionTypeView> definitionTypes() {
        return service.definitionTypes().stream().map(DefinitionTypeView::from).toList();
    }

    @PostMapping("/global-definitions")
    ResponseEntity<DefinitionView> createGlobalDefinition(
            @Valid @RequestBody CreateGlobalDefinitionRequest request) {
        authorization.requireSystemPermission(GLOBAL_DEFINITION_WRITE);
        DefinitionRow definition = service.createGlobalDefinition(
                request.type(), request.code(), request.name(), request.description());
        return ResponseEntity.created(URI.create(
                "/api/v1/global-definitions/" + definition.uuid()))
                .body(DefinitionView.from(definition));
    }

    @GetMapping("/global-definitions")
    List<DefinitionView> listGlobalDefinitions(
            @RequestParam(required = false) DefinitionType type) {
        authorization.requireSystemPermission(GLOBAL_DEFINITION_READ);
        return service.listGlobalDefinitions(type).stream().map(DefinitionView::from).toList();
    }

    @GetMapping("/global-definitions/{definitionUuid}")
    DefinitionView globalDefinition(@PathVariable UUID definitionUuid) {
        authorization.requireSystemPermission(GLOBAL_DEFINITION_READ);
        return DefinitionView.from(service.globalDefinition(definitionUuid));
    }

    @GetMapping("/global-definitions/{definitionUuid}/draft")
    DraftView globalDraft(@PathVariable UUID definitionUuid) {
        authorization.requireSystemPermission(GLOBAL_DEFINITION_READ);
        return DraftView.from(service.globalDraft(definitionUuid));
    }

    @PutMapping("/global-definitions/{definitionUuid}/draft")
    DraftView saveGlobalDraft(
            @PathVariable UUID definitionUuid,
            @Valid @RequestBody SaveDraftRequest request) {
        authorization.requireSystemPermission(GLOBAL_DEFINITION_WRITE);
        return DraftView.from(service.saveGlobalDraft(
                definitionUuid,
                request.expectedVersion(),
                request.schemaVersion(),
                request.content()));
    }

    @PostMapping("/global-definitions/{definitionUuid}/versions")
    ResponseEntity<VersionRow> createGlobalVersion(
            @PathVariable UUID definitionUuid,
            @Valid @RequestBody CreateVersionRequest request) {
        authorization.requireSystemPermission(GLOBAL_DEFINITION_WRITE);
        VersionRow version = service.createGlobalVersion(
                definitionUuid,
                request.expectedDraftVersion(),
                request.description());
        return ResponseEntity.status(201).body(version);
    }

    @GetMapping("/global-definitions/{definitionUuid}/versions")
    List<VersionRow> listGlobalVersions(@PathVariable UUID definitionUuid) {
        authorization.requireSystemPermission(GLOBAL_DEFINITION_READ);
        return service.listGlobalVersions(definitionUuid);
    }

    @PostMapping("/projects")
    ResponseEntity<ProjectView> createProject(@Valid @RequestBody CreateProjectRequest request) {
        authorization.requireSystemPermission(PROJECT_CREATE);
        ProjectRow project = service.createProject(request.code(), request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + project.uuid()))
                .body(ProjectView.from(project));
    }

    @GetMapping("/projects")
    List<ProjectView> listProjects() {
        var visibleProjects = authorization.visibleProjectUuids();
        return service.listProjects().stream()
                .filter(project -> visibleProjects.contains(project.uuid()))
                .map(ProjectView::from)
                .toList();
    }

    @GetMapping("/projects/{projectUuid}")
    ProjectView project(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return ProjectView.from(service.project(projectUuid));
    }

    @PostMapping("/projects/{projectUuid}/folders")
    ResponseEntity<FolderView> createFolder(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateFolderRequest request) {
        authorization.requireProjectPermission(projectUuid, PROJECT_WRITE);
        FolderRow folder = service.createFolder(
                projectUuid,
                request.parentUuid(),
                request.code(),
                request.type(),
                request.name(),
                request.description());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/folders/" + folder.uuid()))
                .body(FolderView.from(folder));
    }

    @GetMapping("/projects/{projectUuid}/folders")
    List<FolderView> listFolders(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return service.listFolders(projectUuid).stream().map(FolderView::from).toList();
    }

    @PostMapping("/projects/{projectUuid}/definitions")
    ResponseEntity<DefinitionView> createDefinition(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateDefinitionRequest request) {
        authorization.requireProjectPermission(projectUuid, PROJECT_WRITE);
        DefinitionRow definition = service.createDefinition(
                projectUuid,
                request.folderUuid(),
                request.type(),
                request.code(),
                request.name(),
                request.description());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/definitions/" + definition.uuid()))
                .body(DefinitionView.from(definition));
    }

    @GetMapping("/projects/{projectUuid}/definitions")
    List<DefinitionView> listDefinitions(
            @PathVariable UUID projectUuid,
            @RequestParam(required = false) DefinitionType type) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return service.listDefinitions(projectUuid, type).stream()
                .map(DefinitionView::from)
                .toList();
    }

    @GetMapping("/projects/{projectUuid}/definitions/{definitionUuid}")
    DefinitionView definition(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return DefinitionView.from(service.definition(projectUuid, definitionUuid));
    }

    @GetMapping("/projects/{projectUuid}/definitions/{definitionUuid}/draft")
    DraftView draft(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return DraftView.from(service.draft(projectUuid, definitionUuid));
    }

    @PutMapping("/projects/{projectUuid}/definitions/{definitionUuid}/draft")
    DraftView saveDraft(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @Valid @RequestBody SaveDraftRequest request) {
        authorization.requireProjectPermission(projectUuid, PROJECT_WRITE);
        return DraftView.from(service.saveDraft(
                projectUuid,
                definitionUuid,
                request.expectedVersion(),
                request.schemaVersion(),
                request.content()));
    }

    @PostMapping("/projects/{projectUuid}/definitions/{definitionUuid}/versions")
    ResponseEntity<VersionRow> createVersion(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @Valid @RequestBody CreateVersionRequest request) {
        authorization.requireProjectPermission(projectUuid, PROJECT_WRITE);
        VersionRow version = service.createVersion(
                projectUuid,
                definitionUuid,
                request.expectedDraftVersion(),
                request.description());
        return ResponseEntity.status(201).body(version);
    }

    @GetMapping("/projects/{projectUuid}/definitions/{definitionUuid}/versions")
    List<VersionRow> listVersions(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return service.listVersions(projectUuid, definitionUuid);
    }

    record CreateProjectRequest(@NotBlank String code, @NotBlank String name, String description) {
    }

    record CreateFolderRequest(
            UUID parentUuid,
            @NotBlank String code,
            String type,
            @NotBlank String name,
            String description) {
    }

    record CreateDefinitionRequest(
            UUID folderUuid,
            @NotNull DefinitionType type,
            @NotBlank String code,
            @NotBlank String name,
            String description) {
    }

    record CreateGlobalDefinitionRequest(
            @NotNull DefinitionType type,
            @NotBlank String code,
            @NotBlank String name,
            String description) {
    }

    record SaveDraftRequest(
            Long expectedVersion,
            @Min(1) int schemaVersion,
            @NotNull JsonNode content) {
    }

    record CreateVersionRequest(@NotNull Long expectedDraftVersion, String description) {
    }

    record DefinitionTypeView(
            String code,
            String label,
            String category,
            boolean folderRequired,
            boolean globalAllowed,
            List<String> requiredContentFields) {

        static DefinitionTypeView from(DefinitionType type) {
            return new DefinitionTypeView(
                    type.name(),
                    type.label(),
                    type.category(),
                    type.folderRequired(),
                    type.globalAllowed(),
                    type.requiredContentFields());
        }
    }

    record ProjectView(
            UUID uuid,
            String code,
            String status,
            String name,
            String description,
            long version,
            OffsetDateTime createdAt) {

        static ProjectView from(ProjectRow row) {
            return new ProjectView(
                    row.uuid(), row.code(), row.status(), row.name(), row.description(),
                    row.version(), row.createdAt());
        }
    }

    record FolderView(
            UUID uuid,
            UUID parentUuid,
            String code,
            String type,
            String status,
            String name,
            String description,
            long version) {

        static FolderView from(FolderRow row) {
            return new FolderView(
                    row.uuid(), row.parentUuid(), row.code(), row.type(), row.status(),
                    row.name(), row.description(), row.version());
        }
    }

    record DefinitionView(
            UUID uuid,
            UUID folderUuid,
            DefinitionType type,
            String code,
            String status,
            String name,
            String description,
            long version) {

        static DefinitionView from(DefinitionRow row) {
            return new DefinitionView(
                    row.uuid(), row.folderUuid(), row.type(), row.code(), row.status(),
                    row.name(), row.description(), row.version());
        }
    }

    record DraftView(
            UUID uuid,
            int schemaVersion,
            JsonNode content,
            long version) {

        static DraftView from(DraftRow row) {
            return new DraftView(row.uuid(), row.schemaVersion(), row.content(), row.version());
        }
    }
}

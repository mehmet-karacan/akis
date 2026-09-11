package tr.com.innova.akis.binding;

import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_WRITE;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.binding.BindingModels.BindingRow;
import tr.com.innova.akis.binding.BindingModels.BindingCandidateRow;
import tr.com.innova.akis.security.AuthorizationService;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/definitions/{definitionUuid}/versions/{definitionVersionUuid}/data-bindings")
final class DefinitionDataBindingController {

    private final DefinitionDataBindingService service;
    private final AuthorizationService authorization;

    DefinitionDataBindingController(
            DefinitionDataBindingService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ResponseEntity<BindingView> create(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @PathVariable UUID definitionVersionUuid,
            @Valid @RequestBody CreateBindingRequest request) {
        authorization.requireProjectPermission(projectUuid, PROJECT_WRITE);
        BindingRow row = service.create(
                projectUuid, definitionUuid, definitionVersionUuid,
                request.nodeCode(), request.role(), request.dataObjectUuid(),
                request.schemaSnapshotUuid());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/definitions/" + definitionUuid
                        + "/versions/" + definitionVersionUuid + "/data-bindings/"
                        + row.uuid()))
                .body(BindingView.from(row));
    }

    @GetMapping
    List<BindingView> list(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @PathVariable UUID definitionVersionUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return service.list(projectUuid, definitionUuid, definitionVersionUuid).stream()
                .map(BindingView::from)
                .toList();
    }

    @GetMapping("/candidates")
    List<BindingCandidateView> candidates(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @PathVariable UUID definitionVersionUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return service.candidates(projectUuid, definitionUuid, definitionVersionUuid)
                .stream()
                .map(BindingCandidateView::from)
                .toList();
    }

    @GetMapping("/{bindingUuid}")
    BindingView get(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @PathVariable UUID definitionVersionUuid,
            @PathVariable UUID bindingUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return BindingView.from(service.get(
                projectUuid, definitionUuid, definitionVersionUuid, bindingUuid));
    }

    record CreateBindingRequest(
            @NotBlank String nodeCode,
            @NotNull BindingRole role,
            @NotNull UUID dataObjectUuid,
            @NotNull UUID schemaSnapshotUuid) {
    }

    record BindingView(
            UUID uuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            String nodeCode,
            BindingRole role,
            UUID dataObjectUuid,
            UUID schemaSnapshotUuid,
            OffsetDateTime createdAt) {

        static BindingView from(BindingRow row) {
            return new BindingView(
                    row.uuid(), row.definitionUuid(), row.definitionVersionUuid(),
                    row.nodeCode(), row.role(), row.dataObjectUuid(),
                    row.schemaSnapshotUuid(), row.createdAt());
        }
    }

    record BindingCandidateView(
            UUID dataObjectUuid,
            String dataObjectCode,
            String dataObjectName,
            String objectReference,
            UUID schemaSnapshotUuid,
            String snapshotFingerprint,
            OffsetDateTime discoveredAt,
            UUID physicalSchemaUuid,
            String physicalSchemaCode,
            String physicalSchemaReference,
            UUID connectionUuid,
            String connectionCode,
            String connectionName,
            UUID connectionVersionUuid,
            int connectionVersionNumber,
            List<String> environmentCodes) {

        static BindingCandidateView from(BindingCandidateRow row) {
            return new BindingCandidateView(
                    row.dataObjectUuid(), row.dataObjectCode(), row.dataObjectName(),
                    row.objectReference(), row.schemaSnapshotUuid(),
                    row.snapshotFingerprint(), row.discoveredAt(),
                    row.physicalSchemaUuid(), row.physicalSchemaCode(),
                    row.physicalSchemaReference(), row.connectionUuid(),
                    row.connectionCode(), row.connectionName(),
                    row.connectionVersionUuid(), row.connectionVersionNumber(),
                    row.environmentCodes());
        }
    }
}

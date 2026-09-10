package tr.com.innova.akis.catalog;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.catalog.CatalogModels.DataObjectRow;
import tr.com.innova.akis.catalog.CatalogModels.ModelRow;
import tr.com.innova.akis.catalog.CatalogModels.SubmodelRow;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/models")
final class CatalogController {

    private final CatalogService service;
    private final AuthorizationService authorization;

    CatalogController(CatalogService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ResponseEntity<ModelView> createModel(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateModelRequest request) {
        authorization.requireProjectPermission(projectUuid, CATALOG_WRITE);
        ModelRow row = service.createModel(
                projectUuid, request.logicalSchemaUuid(), request.code(),
                request.name(), request.description());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/models/" + row.uuid()))
                .body(ModelView.from(row));
    }

    @GetMapping
    List<ModelView> listModels(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, CATALOG_READ);
        return service.listModels(projectUuid).stream().map(ModelView::from).toList();
    }

    @GetMapping("/{modelUuid}")
    ModelView model(@PathVariable UUID projectUuid, @PathVariable UUID modelUuid) {
        authorization.requireProjectPermission(projectUuid, CATALOG_READ);
        return ModelView.from(service.model(projectUuid, modelUuid));
    }

    @PostMapping("/{modelUuid}/submodels")
    ResponseEntity<SubmodelView> createSubmodel(
            @PathVariable UUID projectUuid,
            @PathVariable UUID modelUuid,
            @Valid @RequestBody CreateSubmodelRequest request) {
        authorization.requireProjectPermission(projectUuid, CATALOG_WRITE);
        SubmodelRow row = service.createSubmodel(
                projectUuid, modelUuid, request.parentUuid(), request.code(), request.name());
        return ResponseEntity.status(201).body(SubmodelView.from(row));
    }

    @GetMapping("/{modelUuid}/submodels")
    List<SubmodelView> listSubmodels(
            @PathVariable UUID projectUuid,
            @PathVariable UUID modelUuid) {
        authorization.requireProjectPermission(projectUuid, CATALOG_READ);
        return service.listSubmodels(projectUuid, modelUuid).stream()
                .map(SubmodelView::from)
                .toList();
    }

    @PostMapping("/{modelUuid}/data-objects")
    ResponseEntity<DataObjectView> createDataObject(
            @PathVariable UUID projectUuid,
            @PathVariable UUID modelUuid,
            @Valid @RequestBody CreateDataObjectRequest request) {
        authorization.requireProjectPermission(projectUuid, CATALOG_WRITE);
        DataObjectRow row = service.createDataObject(
                projectUuid, modelUuid, request.submodelUuid(), request.code(),
                request.objectReference(), request.type(), request.querySchemaVersion(),
                request.queryDefinition(), request.name());
        return ResponseEntity.status(201).body(DataObjectView.from(row));
    }

    @GetMapping("/{modelUuid}/data-objects")
    List<DataObjectView> listDataObjects(
            @PathVariable UUID projectUuid,
            @PathVariable UUID modelUuid) {
        authorization.requireProjectPermission(projectUuid, CATALOG_READ);
        return service.listDataObjects(projectUuid, modelUuid).stream()
                .map(DataObjectView::from)
                .toList();
    }

    record CreateModelRequest(
            @NotNull UUID logicalSchemaUuid,
            @NotBlank String code,
            @NotBlank String name,
            String description) {
    }

    record CreateSubmodelRequest(
            UUID parentUuid,
            @NotBlank String code,
            @NotBlank String name) {
    }

    record CreateDataObjectRequest(
            UUID submodelUuid,
            @NotBlank String code,
            @NotBlank String objectReference,
            @NotBlank String type,
            @Min(1) Integer querySchemaVersion,
            JsonNode queryDefinition,
            @NotBlank String name) {
    }

    record ModelView(
            UUID uuid,
            UUID logicalSchemaUuid,
            String code,
            String status,
            String name,
            String description,
            long version) {

        static ModelView from(ModelRow row) {
            return new ModelView(
                    row.uuid(), row.logicalSchemaUuid(), row.code(), row.status(), row.name(),
                    row.description(), row.version());
        }
    }

    record SubmodelView(
            UUID uuid,
            UUID modelUuid,
            UUID parentUuid,
            String code,
            String name,
            long version) {

        static SubmodelView from(SubmodelRow row) {
            return new SubmodelView(
                    row.uuid(), row.modelUuid(), row.parentUuid(), row.code(), row.name(),
                    row.version());
        }
    }

    record DataObjectView(
            UUID uuid,
            UUID modelUuid,
            UUID submodelUuid,
            String code,
            String objectReference,
            String type,
            String status,
            Integer querySchemaVersion,
            JsonNode queryDefinition,
            String name,
            long version) {

        static DataObjectView from(DataObjectRow row) {
            return new DataObjectView(
                    row.uuid(), row.modelUuid(), row.submodelUuid(), row.code(),
                    row.objectReference(), row.type(), row.status(), row.querySchemaVersion(),
                    row.queryDefinition(), row.name(), row.version());
        }
    }
}

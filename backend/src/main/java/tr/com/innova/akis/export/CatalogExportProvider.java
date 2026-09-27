package tr.com.innova.akis.export;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.CatalogExportReader.DataObjectRef;
import tr.com.innova.akis.export.CatalogExportReader.ModelRef;
import tr.com.innova.akis.export.CatalogExportReader.SubmodelRef;
import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportRequest;

/**
 * Export provider for dataset=models, resource=data-objects.
 *
 * <p>Exports the model/data-object catalog: models, submodels, data objects,
 * columns and sensitivity markings. Query definitions and credentials are not
 * emitted.
 */
@Component
public class CatalogExportProvider implements ExportProvider {

    private final CatalogExportReader catalog;
    private final ObjectMapper objectMapper;

    public CatalogExportProvider(CatalogExportReader catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_MODELS.equals(providerId)
                && ExportProviderRegistry.RESOURCE_DATA_OBJECTS.equals(resourceId);
    }

    @Override
    public void validate(ExportRequest request) {
        if (request.scope() == null) {
            throw new ExportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_SCOPE_REQUIRED",
                    "Aktarım kapsamı zorunludur.");
        }
        if (request.filters() == null) {
            return;
        }
        for (ExportFilter filter : request.filters()) {
            if (filter == null || filter.field() == null) {
                continue;
            }
            if (("modelUuid".equals(filter.field()) || "folderUuid".equals(filter.field()))
                    && filter.value() != null && filter.value().isTextual()) {
                try {
                    UUID.fromString(filter.value().asText());
                } catch (IllegalArgumentException exception) {
                    throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "EXPORT_CATALOG_FILTER_INVALID",
                            "Catalog filter UUID is invalid: " + filter.field());
                }
            }
        }
    }

    @Override
    public void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException {
        writer.writeHeader(
                ExportProviderRegistry.DATASET_MODELS,
                ExportProviderRegistry.RESOURCE_DATA_OBJECTS,
                context.scope(), context.locale(), context.timeZone(),
                context.filters(),
                "Data object catalog with columns, submodels, statuses and sensitivity policy");

        UUID projectUuid = context.projectUuid();
        catalog.forEachModel(projectUuid, model -> {
            if (!includeModel(context, model)) {
                return;
            }
            try {
                writer.writeRecord(modelNode(model));
                catalog.forEachSubmodel(projectUuid, model.uuid(), submodel -> {
                    try { writer.writeRecord(submodelNode(submodel)); }
                    catch (IOException e) { throw streamError(e); }
                });
                catalog.forEachDataObject(projectUuid, model.uuid(), object -> {
                    if (includeObject(context, object)) {
                        try { writer.writeRecord(objectNode(projectUuid, object)); }
                        catch (IOException e) { throw streamError(e); }
                    }
                });
            } catch (IOException e) {
                throw streamError(e);
            }
        });
    }

    private ExportException streamError(IOException exception) {
        return new ExportException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_CATALOG_STREAM_ERROR",
                "Catalog export stream failed: " + exception.getMessage());
    }

    private boolean includeObject(ExportContext context, DataObjectRef object) {
        if (context.filters() == null || context.filters().isEmpty()) {
            return true;
        }
        for (ExportFilter filter : context.filters()) {
            if (filter == null || filter.field() == null || filter.value() == null) {
                continue;
            }
            if ("status".equals(filter.field()) && filter.value().isTextual()
                    && !object.status().equalsIgnoreCase(filter.value().asText())) {
                return false;
            }
            if ("type".equals(filter.field()) && filter.value().isTextual()
                    && !object.type().equalsIgnoreCase(filter.value().asText())) {
                return false;
            }
            if ("modelUuid".equals(filter.field()) && filter.value().isTextual()
                    && !object.modelUuid().equals(UUID.fromString(filter.value().asText()))) {
                return false;
            }
            if ("folderUuid".equals(filter.field()) && filter.value().isTextual()
                    && !java.util.Objects.equals(object.submodelUuid(), UUID.fromString(filter.value().asText()))) {
                return false;
            }
            if ("query".equals(filter.field()) && filter.value().isTextual()
                    && !containsObjectText(object, filter.value().asText())) {
                return false;
            }
        }
        return true;
    }

    private boolean includeModel(ExportContext context, ModelRef model) {
        if (context.filters() == null || context.filters().isEmpty()) {
            return true;
        }
        for (ExportFilter filter : context.filters()) {
            if (filter == null || filter.field() == null || filter.value() == null) {
                continue;
            }
            if ("modelUuid".equals(filter.field()) && filter.value().isTextual()
                    && !model.uuid().equals(UUID.fromString(filter.value().asText()))) {
                return false;
            }
            if ("query".equals(filter.field()) && filter.value().isTextual()
                    && !modelUuidFilter(context).isPresent()
                    && !containsModelText(model, filter.value().asText())) {
                return false;
            }
        }
        return true;
    }

    private boolean containsModelText(ModelRef model, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(model.name(), needle) || contains(model.code(), needle)
                || contains(model.technologyCode(), needle);
    }

    private boolean containsObjectText(DataObjectRef object, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(object.name(), needle) || contains(object.code(), needle)
                || contains(object.objectReference(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private java.util.Optional<UUID> modelUuidFilter(ExportContext context) {
        if (context.filters() == null) {
            return java.util.Optional.empty();
        }
        return context.filters().stream()
                .filter(filter -> filter != null && "modelUuid".equals(filter.field())
                        && filter.value() != null && filter.value().isTextual())
                .map(filter -> UUID.fromString(filter.value().asText()))
                .findFirst();
    }

    private JsonNode modelNode(ModelRef model) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "MODEL");
        node.put("uuid", model.uuid().toString());
        node.put("code", model.code());
        node.put("name", model.name());
        node.put("description", model.description());
        node.put("technologyCode", model.technologyCode());
        node.put("status", model.status());
        node.put("dataObjectCount", model.dataObjectCount());
        node.put("lastMetadataUpdate", model.lastMetadataUpdate() == null ? null : model.lastMetadataUpdate().toString());
        node.put("logicalSchemaUuid", model.logicalSchemaUuid() == null ? null : model.logicalSchemaUuid().toString());
        return node;
    }

    private JsonNode submodelNode(SubmodelRef submodel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "SUBMODEL");
        node.put("uuid", submodel.uuid().toString());
        node.put("modelUuid", submodel.modelUuid().toString());
        node.put("parentUuid", submodel.parentUuid() == null ? null : submodel.parentUuid().toString());
        node.put("code", submodel.code());
        node.put("name", submodel.name());
        return node;
    }

    private JsonNode objectNode(UUID projectUuid, DataObjectRef object) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "DATA_OBJECT");
        node.put("uuid", object.uuid().toString());
        node.put("modelUuid", object.modelUuid().toString());
        node.put("submodelUuid", object.submodelUuid() == null ? null : object.submodelUuid().toString());
        node.put("code", object.code());
        node.put("objectReference", object.objectReference());
        node.put("type", object.type());
        node.put("status", object.status());
        node.put("name", object.name());
        // queryDefinition deliberately omitted; it may contain connection details.
        List<String> sensitive = catalog.sensitiveColumns(projectUuid, object.modelUuid(), object.uuid());
        node.set("sensitiveColumns", objectMapper.valueToTree(sensitive));
        return node;
    }
}

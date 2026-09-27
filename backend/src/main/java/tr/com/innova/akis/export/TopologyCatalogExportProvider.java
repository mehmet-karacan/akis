package tr.com.innova.akis.export;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportRequest;

/** Export provider for credential-safe environment/schema topology catalogs. */
@Component
public class TopologyCatalogExportProvider implements ExportProvider {

    private final TopologyCatalogExportReader reader;
    private final ObjectMapper objectMapper;

    public TopologyCatalogExportProvider(TopologyCatalogExportReader reader, ObjectMapper objectMapper) {
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_TOPOLOGY.equals(providerId)
                && (ExportProviderRegistry.RESOURCE_ENVIRONMENTS.equals(resourceId)
                || ExportProviderRegistry.RESOURCE_LOGICAL_SCHEMAS.equals(resourceId)
                || ExportProviderRegistry.RESOURCE_PHYSICAL_SCHEMAS.equals(resourceId));
    }

    @Override
    public void validate(ExportRequest request) {
        if (request.scope() == null) {
            throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_SCOPE_REQUIRED",
                    "Aktarım kapsamı zorunludur.");
        }
    }

    @Override
    public void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException {
        String resource = context.resourceId();
        if (resource == null || resource.isBlank()) {
            throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "EXPORT_RESOURCE_MISSING", "Topology export resource is missing.");
        }
        writer.writeHeader(ExportProviderRegistry.DATASET_TOPOLOGY, resource, context.scope(),
                context.locale(), context.timeZone(), context.filters(),
                "Credential-safe topology catalog export");
        if (ExportProviderRegistry.RESOURCE_ENVIRONMENTS.equals(resource)) {
            reader.forEachEnvironment(item -> write(writer, environment(item), context));
        } else if (ExportProviderRegistry.RESOURCE_LOGICAL_SCHEMAS.equals(resource)) {
            reader.forEachLogicalSchema(item -> write(writer, logicalSchema(item), context));
        } else {
            reader.forEachPhysicalSchema(item -> write(writer, physicalSchema(item), context));
        }
    }

    private void write(JsonExportWriter writer, ObjectNode record, ExportContext context) {
        if (include(record, context)) {
            try { writer.writeRecord(record); }
            catch (IOException exception) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_TOPOLOGY_STREAM_ERROR",
                        "Topology catalog export failed: " + exception.getMessage());
            }
        }
    }

    private boolean include(ObjectNode record, ExportContext context) {
        if (context.scope().name().equals("ALL") || context.filters() == null) return true;
        for (ExportFilter filter : context.filters()) {
            if (filter == null || filter.value() == null || !filter.value().isTextual()) continue;
            if ("query".equals(filter.field())) {
                String query = filter.value().asText().toLowerCase(Locale.ROOT);
                String text = record.toString().toLowerCase(Locale.ROOT);
                if (!text.contains(query)) return false;
            }
            if ("status".equals(filter.field()) && record.has("status")
                    && !record.get("status").asText().equals(filter.value().asText())) return false;
            if ("databaseType".equals(filter.field()) && record.has("databaseType")
                    && !record.get("databaseType").asText().equals(filter.value().asText())) return false;
            if ("risk".equals(filter.field()) && record.has("risk")
                    && !record.get("risk").asText().equals(filter.value().asText())) return false;
        }
        return true;
    }

    private ObjectNode environment(TopologyCatalogExportReader.EnvironmentRef item) {
        ObjectNode node = objectMapper.createObjectNode(); node.put("recordType", "ENVIRONMENT");
        node.put("uuid", item.uuid().toString()); node.put("code", item.code()); node.put("name", item.name());
        node.put("description", item.description()); node.put("risk", item.risk()); node.put("default", item.defaultEnvironment());
        node.put("policyVersion", item.policyVersion()); node.put("status", item.status()); node.put("mappingCount", item.mappingCount()); return node;
    }

    private ObjectNode logicalSchema(TopologyCatalogExportReader.LogicalSchemaRef item) {
        ObjectNode node = objectMapper.createObjectNode(); node.put("recordType", "LOGICAL_SCHEMA");
        node.put("uuid", item.uuid().toString()); node.put("code", item.code()); node.put("name", item.name());
        node.put("description", item.description()); node.put("databaseType", item.databaseType()); node.put("status", item.status());
        node.put("mappingCount", item.mappingCount()); node.put("modelCount", item.modelCount()); return node;
    }

    private ObjectNode physicalSchema(TopologyCatalogExportReader.PhysicalSchemaRef item) {
        ObjectNode node = objectMapper.createObjectNode(); node.put("recordType", "PHYSICAL_SCHEMA");
        node.put("uuid", item.uuid().toString()); node.put("connectionUuid", item.connectionUuid().toString());
        node.put("connectionCode", item.connectionCode()); node.put("code", item.code()); node.put("name", item.name());
        node.put("description", item.description()); node.put("databaseType", item.databaseType()); node.put("catalogName", item.catalogName());
        node.put("schemaName", item.schemaName()); node.put("workCatalogName", item.workCatalogName()); node.put("workSchemaName", item.workSchemaName());
        node.put("default", item.defaultSchema()); node.put("loadingPrefix", item.loadingPrefix()); node.put("integrationPrefix", item.integrationPrefix());
        node.put("errorPrefix", item.errorPrefix()); node.put("tempPrefix", item.tempPrefix()); node.put("objectPattern", item.objectPattern());
        node.put("remoteObjectPattern", item.remoteObjectPattern()); node.put("sequencePattern", item.sequencePattern()); node.put("status", item.status()); return node;
    }
}

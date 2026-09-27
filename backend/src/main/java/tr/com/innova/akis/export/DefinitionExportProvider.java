package tr.com.innova.akis.export;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.export.DefinitionExportReader.DefinitionRef;
import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportRequest;

/** Exports safe definition metadata without draft content, SQL or credentials. */
@Component
class DefinitionExportProvider implements ExportProvider {

    private final DefinitionExportReader reader;
    private final ObjectMapper objectMapper;

    DefinitionExportProvider(DefinitionExportReader reader, ObjectMapper objectMapper) {
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_DEFINITIONS.equals(providerId)
                && ExportProviderRegistry.RESOURCE_DEFINITIONS.equals(resourceId);
    }

    @Override
    public void validate(ExportRequest request) {
        if (request.scope() == null) {
            throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_SCOPE_REQUIRED", "Aktarım kapsamı zorunludur.");
        }
        if (request.filters() == null) return;
        for (ExportFilter filter : request.filters()) {
            if (filter != null && "folderUuid".equals(filter.field()) && filter.value() != null && filter.value().isTextual()) {
                try { UUID.fromString(filter.value().asText()); }
                catch (IllegalArgumentException e) {
                    throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_DEFINITION_FILTER_INVALID", "Klasör UUID değeri geçersiz.");
                }
            }
        }
    }

    @Override
    public void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException {
        writer.writeHeader(ExportProviderRegistry.DATASET_DEFINITIONS, ExportProviderRegistry.RESOURCE_DEFINITIONS,
                context.scope(), context.locale(), context.timeZone(), context.filters(),
                "Project definition catalog metadata; version contents and secrets are omitted");
        reader.forEach(context.projectUuid(), definition -> {
            if (!include(context.filters(), definition)) return;
            try { writer.writeRecord(node(definition)); }
            catch (IOException e) { throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_DEFINITION_STREAM_ERROR", e.getMessage()); }
        });
    }

    private boolean include(List<ExportFilter> filters, DefinitionRef value) {
        if (filters == null) return true;
        for (ExportFilter filter : filters) {
            if (filter == null || filter.field() == null || filter.value() == null || !filter.value().isTextual()) continue;
            String expected = filter.value().asText();
            switch (filter.field()) {
                case "query" -> { if (!contains(value, expected)) return false; }
                case "type" -> { if (!Objects.equals(value.type(), expected) && !value.type().equalsIgnoreCase(expected)) return false; }
                case "status" -> { if (!value.status().equalsIgnoreCase(expected)) return false; }
                case "folderUuid" -> { if (value.folderUuid() == null || !value.folderUuid().equals(UUID.fromString(expected))) return false; }
                default -> { }
            }
        }
        return true;
    }

    private boolean contains(DefinitionRef value, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return text(value.type(), needle) || text(value.code(), needle) || text(value.name(), needle)
                || text(value.description(), needle);
    }

    private boolean text(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private ObjectNode node(DefinitionRef value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "DEFINITION");
        node.put("uuid", value.uuid().toString());
        node.put("type", value.type());
        node.put("code", value.code());
        node.put("name", value.name());
        node.put("description", value.description());
        node.put("status", value.status());
        node.put("folderUuid", value.folderUuid() == null ? null : value.folderUuid().toString());
        node.put("version", value.version());
        node.put("versionCount", value.versionCount());
        node.put("latestVersionNumber", value.latestVersionNumber());
        return node;
    }
}

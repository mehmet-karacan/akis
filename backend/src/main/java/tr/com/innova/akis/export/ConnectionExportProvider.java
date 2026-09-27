package tr.com.innova.akis.export;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.ConnectionExportReader.ConnectionRef;
import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportModels.ExportScope;

/** Typed export provider for the credential-safe connection catalog. */
@Component
public class ConnectionExportProvider implements ExportProvider {

    private final ConnectionExportReader connections;
    private final ObjectMapper objectMapper;

    public ConnectionExportProvider(ConnectionExportReader connections, ObjectMapper objectMapper) {
        this.connections = connections;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_TOPOLOGY.equals(providerId)
                && ExportProviderRegistry.RESOURCE_CONNECTIONS.equals(resourceId);
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
        writer.writeHeader(ExportProviderRegistry.DATASET_TOPOLOGY,
                ExportProviderRegistry.RESOURCE_CONNECTIONS, context.scope(), context.locale(),
                context.timeZone(), context.filters(),
                "Credential-safe global connection catalog with topology counts");
        connections.forEach(connection -> {
            if (include(context, connection)) {
                try {
                    writer.writeRecord(record(connection));
                } catch (IOException exception) {
                    throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "EXPORT_CONNECTION_STREAM_ERROR",
                            "Bağlantı kataloğu aktarılırken hata oluştu: " + exception.getMessage());
                }
            }
        });
    }

    private boolean include(ExportContext context, ConnectionRef connection) {
        if (context.scope() == ExportScope.ALL || context.filters() == null) return true;
        for (ExportFilter filter : context.filters()) {
            if ("query".equals(filter.field()) && filter.value().isTextual()) {
                String query = filter.value().asText().toLowerCase(Locale.ROOT);
                String haystack = (safe(connection.name()) + " " + safe(connection.code()) + " "
                        + safe(connection.databaseType()) + " " + safe(connection.host())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) return false;
            }
            if ("databaseType".equals(filter.field()) && filter.value().isTextual()
                    && !safe(connection.databaseType()).equals(filter.value().asText())) return false;
            if ("status".equals(filter.field()) && filter.value().isTextual()
                    && !safe(connection.status()).equals(filter.value().asText())) return false;
        }
        return true;
    }

    private ObjectNode record(ConnectionRef connection) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "CONNECTION");
        node.put("uuid", connection.uuid().toString());
        node.put("code", connection.code());
        node.put("name", connection.name());
        node.put("description", connection.description());
        node.put("databaseType", connection.databaseType());
        node.put("mode", connection.mode());
        node.put("host", connection.host());
        node.put("port", connection.port());
        node.put("serviceName", connection.serviceName());
        node.put("sid", connection.sid());
        node.put("databaseName", connection.databaseName());
        node.put("username", connection.username());
        node.put("passwordConfigured", connection.passwordConfigured());
        node.put("lastTestedAt", connection.lastTestedAt() == null ? null : connection.lastTestedAt().toString());
        node.put("lastTestPassed", connection.lastTestPassed());
        node.put("status", connection.status());
        node.put("createdBy", connection.createdBy());
        node.put("createdAt", connection.createdAt() == null ? null : connection.createdAt().toString());
        node.put("updatedAt", connection.updatedAt() == null ? null : connection.updatedAt().toString());
        node.put("physicalSchemaCount", connection.physicalSchemaCount());
        node.put("logicalSchemaCount", connection.logicalSchemaCount());
        return node;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

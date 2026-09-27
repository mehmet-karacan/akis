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
import tr.com.innova.akis.export.ExportModels.ExportScope;

/** Global export provider for the non-sensitive user catalog. */
@Component
class UserExportProvider implements ExportProvider {

    private final UserExportReader users;
    private final ObjectMapper objectMapper;

    UserExportProvider(UserExportReader users, ObjectMapper objectMapper) {
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_IDENTITY.equals(providerId)
                && ExportProviderRegistry.RESOURCE_USERS.equals(resourceId);
    }

    @Override
    public void validate(ExportRequest request) {
        if (request.scope() == null) {
            throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_SCOPE_REQUIRED", "Aktarım kapsamı zorunludur.");
        }
        if (request.filters() == null) return;
        for (ExportFilter filter : request.filters()) {
            if (filter == null || filter.field() == null) continue;
            if (!filter.field().equals("query") && !filter.field().equals("status")) {
                throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "EXPORT_USER_FILTER_INVALID", "Desteklenmeyen kullanıcı filtresi: " + filter.field());
            }
        }
    }

    @Override
    public void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException {
        writer.writeHeader(ExportProviderRegistry.DATASET_IDENTITY,
                ExportProviderRegistry.RESOURCE_USERS, context.scope(), context.locale(),
                context.timeZone(), context.filters(),
                "Credential-free application user catalog");
        users.forEach(user -> {
            if (!include(context, user)) return;
            try {
                writer.writeRecord(record(user));
            } catch (IOException exception) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EXPORT_USER_STREAM_ERROR", "Kullanıcı kataloğu aktarılırken hata oluştu.");
            }
        });
    }

    private boolean include(ExportContext context, UserExportReader.UserRef user) {
        if (context.scope() == ExportScope.ALL || context.filters() == null) return true;
        for (ExportFilter filter : context.filters()) {
            if (filter == null || filter.field() == null || filter.value() == null) continue;
            if ("status".equals(filter.field()) && filter.value().isTextual()
                    && !safe(user.status()).equalsIgnoreCase(filter.value().asText())) return false;
            if ("query".equals(filter.field()) && filter.value().isTextual()) {
                String needle = filter.value().asText().toLowerCase(Locale.ROOT);
                String haystack = (safe(user.userCode()) + " " + safe(user.firstName()) + " "
                        + safe(user.lastName()) + " " + safe(user.employeeNumber()) + " "
                        + safe(user.email())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(needle)) return false;
            }
        }
        return true;
    }

    private ObjectNode record(UserExportReader.UserRef user) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "USER");
        node.put("uuid", user.uuid().toString());
        node.put("userCode", user.userCode());
        node.put("firstName", user.firstName());
        node.put("lastName", user.lastName());
        node.put("employeeNumber", user.employeeNumber());
        node.put("email", user.email());
        node.put("status", user.status());
        node.put("createdAt", user.createdAt() == null ? null : user.createdAt().toString());
        return node;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

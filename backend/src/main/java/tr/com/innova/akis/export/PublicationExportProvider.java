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
import tr.com.innova.akis.export.PublicationExportReader.PublicationRef;

/** Typed export provider for the visible publication catalog fields. */
@Component
public class PublicationExportProvider implements ExportProvider {

    private final PublicationExportReader publications;
    private final ObjectMapper objectMapper;

    public PublicationExportProvider(PublicationExportReader publications, ObjectMapper objectMapper) {
        this.publications = publications;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_OPERATIONS.equals(providerId)
                && ExportProviderRegistry.RESOURCE_PUBLICATIONS.equals(resourceId);
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
        writer.writeHeader(ExportProviderRegistry.DATASET_OPERATIONS,
                ExportProviderRegistry.RESOURCE_PUBLICATIONS, context.scope(), context.locale(),
                context.timeZone(), context.filters(),
                "Safe publication catalog without physical manifest or credentials");
        publications.forEach(context.projectUuid(), publication -> {
            if (!include(context, publication)) return;
            try {
                writer.writeRecord(record(publication));
            } catch (IOException exception) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EXPORT_PUBLICATION_STREAM_ERROR",
                        "Yayın kataloğu aktarılırken hata oluştu: " + exception.getMessage());
            }
        });
    }

    private boolean include(ExportContext context, PublicationRef publication) {
        if (context.scope() == ExportScope.ALL || context.filters() == null) return true;
        for (ExportFilter filter : context.filters()) {
            if ("query".equals(filter.field()) && filter.value().isTextual()) {
                String query = filter.value().asText().toLowerCase(Locale.ROOT);
                String haystack = (safe(publication.definitionName()) + " " + safe(publication.definitionCode())
                        + " " + safe(publication.environmentCode()) + " " + safe(publication.releaseHash())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) return false;
            }
            if ("status".equals(filter.field()) && filter.value().isTextual()
                    && !safe(publication.status()).equals(filter.value().asText())) return false;
            if ("environment".equals(filter.field()) && filter.value().isTextual()
                    && !safe(publication.environmentCode()).equals(filter.value().asText())) return false;
        }
        return true;
    }

    private ObjectNode record(PublicationRef publication) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "PUBLICATION");
        node.put("uuid", publication.uuid().toString());
        node.put("scenarioUuid", publication.scenarioUuid().toString());
        node.put("definitionUuid", publication.definitionUuid().toString());
        node.put("definitionVersionUuid", publication.definitionVersionUuid().toString());
        node.put("environmentUuid", publication.environmentUuid().toString());
        node.put("definitionCode", publication.definitionCode());
        node.put("definitionName", publication.definitionName());
        node.put("environmentCode", publication.environmentCode());
        node.put("environmentRisk", publication.environmentRisk());
        node.put("publicationNumber", publication.publicationNumber());
        node.put("status", publication.status());
        node.put("releaseHash", publication.releaseHash());
        node.put("publishedAt", publication.publishedAt() == null ? null : publication.publishedAt().toString());
        node.put("createdAt", publication.createdAt() == null ? null : publication.createdAt().toString());
        node.put("version", publication.version());
        return node;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

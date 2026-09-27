package tr.com.innova.akis.export;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.ScheduleExportReader.ScheduleRef;

/** Typed export provider for project schedules; no publication payload is exported. */
@Component
public class ScheduleExportProvider implements ExportProvider {

    private final ScheduleExportReader schedules;
    private final ObjectMapper objectMapper;

    public ScheduleExportProvider(ScheduleExportReader schedules, ObjectMapper objectMapper) {
        this.schedules = schedules;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_EXECUTION.equals(providerId)
                && ExportProviderRegistry.RESOURCE_SCHEDULES.equals(resourceId);
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
        writer.writeHeader(ExportProviderRegistry.DATASET_EXECUTION,
                ExportProviderRegistry.RESOURCE_SCHEDULES, context.scope(), context.locale(),
                context.timeZone(), context.filters(), "Project schedules and trigger policies");
        schedules.forEach(context.projectUuid(), schedule -> {
            if (!include(context, schedule)) return;
            try {
                writer.writeRecord(record(schedule));
            } catch (IOException exception) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EXPORT_SCHEDULE_STREAM_ERROR",
                        "Zamanlama kataloğu aktarılırken hata oluştu: " + exception.getMessage());
            }
        });
    }

    private boolean include(ExportContext context, ScheduleRef schedule) {
        if (context.scope() == ExportScope.ALL || context.filters() == null) return true;
        for (ExportFilter filter : context.filters()) {
            if ("query".equals(filter.field()) && filter.value().isTextual()) {
                String query = filter.value().asText().toLowerCase(java.util.Locale.ROOT);
                String haystack = safe(schedule.name()) + " " + safe(schedule.code()) + " "
                        + safe(schedule.cronExpression()) + " " + safe(schedule.timeZone());
                if (!haystack.toLowerCase(java.util.Locale.ROOT).contains(query)) return false;
            }
            if ("status".equals(filter.field()) && filter.value().isTextual()
                    && !safe(schedule.status()).equals(filter.value().asText())) return false;
        }
        return true;
    }

    private ObjectNode record(ScheduleRef schedule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", "SCHEDULE");
        node.put("uuid", schedule.uuid().toString());
        node.put("code", schedule.code());
        node.put("name", schedule.name());
        node.put("publicationUuid", schedule.publicationUuid().toString());
        node.put("cronExpression", schedule.cronExpression());
        node.put("timeZone", schedule.timeZone());
        node.put("status", schedule.status());
        node.put("conflictPolicy", schedule.conflictPolicy());
        node.put("misfirePolicy", schedule.misfirePolicy());
        node.put("publicationPolicy", schedule.publicationPolicy());
        node.put("nextFireTime", schedule.nextFireTime() == null ? null : schedule.nextFireTime().toString());
        node.put("lastFireTime", schedule.lastFireTime() == null ? null : schedule.lastFireTime().toString());
        node.put("startsAt", schedule.startsAt() == null ? null : schedule.startsAt().toString());
        node.put("endsAt", schedule.endsAt() == null ? null : schedule.endsAt().toString());
        node.put("version", schedule.version());
        return node;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

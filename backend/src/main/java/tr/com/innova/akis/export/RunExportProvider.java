package tr.com.innova.akis.export;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.RunExportReader.EventRef;
import tr.com.innova.akis.export.RunExportReader.RunQuery;
import tr.com.innova.akis.export.RunExportReader.RunRef;
import tr.com.innova.akis.export.RunExportReader.StepRef;
import tr.com.innova.akis.knowledge.KmStepJournal;
import tr.com.innova.akis.knowledge.WorkObjectStore;

/**
 * Export provider for dataset=runs, resource=run-history.
 *
 * <p>Exports run summaries plus step/event/KM detail hierarchies.
 * Child runs are included recursively; SQL evidence is redacted by the writer.
 *
 * <p>This provider is bounded: it never materialises the whole project run
 * list in memory. Runs are fetched with project-scoped, parametrized SQL and
 * processed through streaming callbacks. The hard {@code maxRecords} limit
 * from {@link ExportConfiguration} is enforced as a definitive cap on the
 * number of exported top-level run records.
 */
@Component
public class RunExportProvider implements ExportProvider {

    private static final int RUN_BATCH_SIZE = 16;
    private static final int MAX_BUFFERED_DETAIL_ROWS = 2_048;


    private final RunExportReader runs;
    private final KmStepJournal kmStepJournal;
    private final WorkObjectStore workObjectStore;
    private final ObjectMapper objectMapper;

    public RunExportProvider(
            RunExportReader runs,
            KmStepJournal kmStepJournal,
            WorkObjectStore workObjectStore,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.kmStepJournal = kmStepJournal;
        this.workObjectStore = workObjectStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_RUNS.equals(providerId)
                && ExportProviderRegistry.RESOURCE_RUN_HISTORY.equals(resourceId);
    }

    @Override
    public void validate(ExportRequest request) {
        if (request.scope() == null) {
            throw new ExportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_SCOPE_REQUIRED",
                    "Aktarım kapsamı zorunludur.");
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 1800)
    public void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException {
        List<UUID> selectedUuids = context.scope() == ExportScope.SELECTED
                ? selectedRunUuids(context.filters()) : List.of();
        if (selectedUuids.size() > context.maxRecords()) {
            throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_SELECTION_LIMIT", "Seçilen çalıştırma sayısı aktarım sınırını aşıyor.");
        }
        writer.writeHeader(
                ExportProviderRegistry.DATASET_RUNS,
                ExportProviderRegistry.RESOURCE_RUN_HISTORY,
                context.scope(), context.locale(), context.timeZone(),
                context.filters(),
                "Project run history with step/event/KM detail hierarchy");

        UUID projectUuid = context.projectUuid();
        ExportScope scope = context.scope();
        List<ExportFilter> filters = context.filters();
        long maxRecords = context.maxRecords();

        Set<UUID> exportedRuns = new HashSet<>();
        Set<UUID> detailRuns = new HashSet<>();
        AtomicLong emitted = new AtomicLong();

        if (scope == ExportScope.SELECTED) {
            for (int offset = 0; offset < selectedUuids.size(); offset += RUN_BATCH_SIZE) {
                List<UUID> ids = selectedUuids.subList(offset,
                        Math.min(offset + RUN_BATCH_SIZE, selectedUuids.size()));
                Map<UUID, RunRef> found = runs.findBatch(projectUuid, ids);
                List<RunRef> batch = new ArrayList<>(ids.size());
                for (UUID runUuid : ids) {
                    RunRef run = found.get(runUuid);
                    if (run == null) {
                        throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "EXPORT_SELECTED_RUN_NOT_FOUND",
                                "Seçilen çalıştırma bu projede bulunamadı veya erişilemiyor.");
                    }
                    batch.add(run);
                }
                flushRunBatch(writer, projectUuid, batch, context.includeDetails(),
                        exportedRuns, detailRuns, emitted, maxRecords);
            }
            return;
        }

        // Keep the cursor open until one additional distinct run proves the
        // limit is exceeded. A SQL LIMIT here would silently publish a partial export.
        RunQuery query = buildQuery(scope, filters, 0);
        List<RunRef> batch = new ArrayList<>(RUN_BATCH_SIZE);
        Set<UUID> pending = new HashSet<>();
        runs.forEachRun(projectUuid, query, run -> {
            if (exportedRuns.contains(run.runUuid()) || !pending.add(run.runUuid())) return;
            if (emitted.get() >= maxRecords) {
                throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "EXPORT_RUN_LIMIT", "Çalıştırma sayısı aktarım sınırını aşıyor; eksik geçmiş üretilemez.");
            }
            batch.add(run);
            if (batch.size() >= RUN_BATCH_SIZE || batch.size() >= maxRecords - emitted.get()) {
                flushRunBatch(writer, projectUuid, batch, context.includeDetails(),
                        exportedRuns, detailRuns, emitted, maxRecords);
                batch.clear();
                pending.clear();
            }
        });
        if (!batch.isEmpty()) {
            flushRunBatch(writer, projectUuid, batch, context.includeDetails(),
                    exportedRuns, detailRuns, emitted, maxRecords);
        }
    }

    private record RunBatchDetails(Map<UUID, List<StepRef>> steps, Map<UUID, List<EventRef>> events,
            Map<UUID, List<KmStepJournal.Row>> kmSteps,
            Map<UUID, List<WorkObjectStore.ObjectRow>> workObjects,
            Map<UUID, KmStepJournal.Reconciliation> reconciliations,
            Set<UUID> fallbackRuns) {}

    private static final class BatchAccumulator {
        final Map<UUID, List<StepRef>> steps = new HashMap<>();
        final Map<UUID, List<EventRef>> events = new HashMap<>();
        final Map<UUID, List<KmStepJournal.Row>> kmSteps = new HashMap<>();
        final Map<UUID, List<WorkObjectStore.ObjectRow>> workObjects = new HashMap<>();
        final Set<UUID> fallbackRuns = new HashSet<>();
        int buffered;

        <T> void add(UUID runUuid, T row, Map<UUID, List<T>> target) {
            if (fallbackRuns.contains(runUuid)) return;
            if (buffered == MAX_BUFFERED_DETAIL_ROWS) {
                fallbackRuns.add(runUuid);
                buffered -= discard(steps, runUuid) + discard(events, runUuid)
                        + discard(kmSteps, runUuid) + discard(workObjects, runUuid);
                return;
            }
            target.computeIfAbsent(runUuid, ignored -> new ArrayList<>()).add(row);
            buffered++;
        }

        private static int discard(Map<UUID, ? extends List<?>> source, UUID runUuid) {
            List<?> removed = source.remove(runUuid);
            return removed == null ? 0 : removed.size();
        }
    }

    private void flushRunBatch(JsonExportWriter writer, UUID projectUuid, List<RunRef> batch,
            boolean includeDetails, Set<UUID> exportedRuns, Set<UUID> detailRuns,
            AtomicLong emitted, long maxRecords) {
        List<UUID> ids = batch.stream().map(RunRef::runUuid).toList();
        RunBatchDetails details = loadBatchDetails(projectUuid, ids, includeDetails);
        ChildPrefetchPool childPool = prefetchChildren(projectUuid, batch, details, includeDetails);
        for (RunRef run : batch) {
            Deque<PendingChild> children = new ArrayDeque<>();
            try {
                exportRun(writer, projectUuid, run, includeDetails, exportedRuns, detailRuns,
                        true, emitted, maxRecords, null, run.runUuid(), null,
                        details.fallbackRuns().contains(run.runUuid()) ? null : details, children);
            } catch (IOException exception) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EXPORT_RUN_STREAM_ERROR", "Çalıştırma aktarımı yazılamadı.");
            }
            drainChildren(writer, projectUuid, children, includeDetails,
                    exportedRuns, detailRuns, emitted, maxRecords, childPool);
        }
    }

    private RunBatchDetails loadBatchDetails(UUID projectUuid, List<UUID> ids, boolean includeDetails) {
        BatchAccumulator buffer = new BatchAccumulator();
        Map<UUID, KmStepJournal.Reconciliation> reconciliations = Map.of();
        runs.forEachStepBatch(projectUuid, ids,
                (runUuid, step) -> buffer.add(runUuid, step, buffer.steps));
        runs.forEachEventBatch(projectUuid, ids,
                (runUuid, event) -> buffer.add(runUuid, event, buffer.events));
        if (includeDetails) {
            kmStepJournal.forEachRowBatch(projectUuid, ids,
                    (runUuid, row) -> buffer.add(runUuid, row, buffer.kmSteps));
            workObjectStore.forEachObjectRowBatch(projectUuid, ids,
                    (runUuid, row) -> buffer.add(runUuid, row, buffer.workObjects));
            reconciliations = kmStepJournal.reconciliationBatch(projectUuid, ids);
        }
        return new RunBatchDetails(buffer.steps, buffer.events, buffer.kmSteps,
                buffer.workObjects, reconciliations, buffer.fallbackRuns);
    }

    private ChildPrefetchPool prefetchChildren(UUID projectUuid, List<RunRef> parents,
            RunBatchDetails details, boolean includeDetails) {
        LinkedHashSet<UUID> childIds = new LinkedHashSet<>();
        for (RunRef parent : parents) {
            for (StepRef step : details.steps().getOrDefault(parent.runUuid(), List.of())) {
                if (step.childRunUuid() != null) childIds.add(step.childRunUuid());
            }
        }
        return childIds.isEmpty() ? null
                : new ChildPrefetchPool(projectUuid, List.copyOf(childIds), includeDetails);
    }

    private record PrefetchedChild(RunRef run, RunBatchDetails details,
            ChildPrefetchPool descendants) {}

    /** One bounded child wave is retained while DFS emits its parents in original order. */
    private final class ChildPrefetchPool {
        private final UUID projectUuid;
        private final List<UUID> ids;
        private final Set<UUID> planned;
        private final boolean includeDetails;
        private int next;
        private Map<UUID, RunRef> found = Map.of();
        private RunBatchDetails details;
        private ChildPrefetchPool descendants;

        ChildPrefetchPool(UUID projectUuid, List<UUID> ids, boolean includeDetails) {
            this.projectUuid = projectUuid;
            this.ids = ids;
            this.planned = new HashSet<>(ids);
            this.includeDetails = includeDetails;
        }

        PrefetchedChild get(UUID runUuid) {
            if (!planned.contains(runUuid)) return null;
            if (!found.containsKey(runUuid)) {
                while (next < ids.size()) {
                    List<UUID> wave = ids.subList(next, Math.min(next + RUN_BATCH_SIZE, ids.size()));
                    next += wave.size();
                    found = runs.findBatch(projectUuid, wave);
                    for (UUID id : wave) {
                        if (!found.containsKey(id)) {
                            throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "EXPORT_CHILD_RUN_MISSING",
                                    "Alt çalıştırma bulunamadı; eksik geçmiş aktarımı tamamlanamaz.");
                        }
                    }
                    details = loadBatchDetails(projectUuid, wave, includeDetails);
                    descendants = prefetchChildren(projectUuid,
                            wave.stream().map(found::get).toList(), details, includeDetails);
                    if (found.containsKey(runUuid)) break;
                }
            }
            RunRef run = found.get(runUuid);
            if (run == null) return null;
            return new PrefetchedChild(run, details, descendants);
        }
    }

    private void drainChildren(JsonExportWriter writer, UUID projectUuid, Deque<PendingChild> children,
            boolean includeDetails, Set<UUID> exportedRuns, Set<UUID> detailRuns,
            AtomicLong emitted, long maxRecords, ChildPrefetchPool prefetch) {
        if (prefetch != null) {
            while (!children.isEmpty()) {
                PendingChild child = children.removeFirst();
                if (exportedRuns.contains(child.runUuid())) continue;
                PrefetchedChild loaded = prefetch.get(child.runUuid());
                if (loaded == null) {
                    Deque<PendingChild> unmatched = new ArrayDeque<>();
                    unmatched.add(child);
                    drainChildren(writer, projectUuid, unmatched, includeDetails,
                            exportedRuns, detailRuns, emitted, maxRecords, null);
                    continue;
                }
                Deque<PendingChild> descendants = new ArrayDeque<>();
                try {
                    exportRun(writer, projectUuid, loaded.run(), includeDetails,
                            exportedRuns, detailRuns, true, emitted, maxRecords,
                            child.parentRunUuid(), child.rootRunUuid(), child.packageStepUuid(),
                            loaded.details().fallbackRuns().contains(child.runUuid())
                                    ? null : loaded.details(), descendants);
                } catch (IOException exception) {
                    throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "EXPORT_RUN_STREAM_ERROR", "Çalıştırma aktarımı yazılamadı.");
                }
                drainChildren(writer, projectUuid, descendants, includeDetails,
                        exportedRuns, detailRuns, emitted, maxRecords, loaded.descendants());
            }
            return;
        }
        while (!children.isEmpty()) {
            List<PendingChild> batch = new ArrayList<>(RUN_BATCH_SIZE);
            Set<UUID> ids = new LinkedHashSet<>();
            while (!children.isEmpty() && batch.size() < RUN_BATCH_SIZE) {
                PendingChild child = children.removeFirst();
                if (!exportedRuns.contains(child.runUuid()) && ids.add(child.runUuid())) batch.add(child);
            }
            if (batch.isEmpty()) continue;
            Map<UUID, RunRef> found = runs.findBatch(projectUuid, List.copyOf(ids));
            for (PendingChild child : batch) {
                if (!found.containsKey(child.runUuid())) {
                    throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "EXPORT_CHILD_RUN_MISSING", "Alt çalıştırma bulunamadı; eksik geçmiş aktarımı tamamlanamaz.");
                }
            }
            writeChildBatch(writer, projectUuid, batch, found, includeDetails,
                    exportedRuns, detailRuns, emitted, maxRecords);
        }
    }

    private void writeChildBatch(JsonExportWriter writer, UUID projectUuid, List<PendingChild> batch,
            Map<UUID, RunRef> found, boolean includeDetails, Set<UUID> exportedRuns,
            Set<UUID> detailRuns, AtomicLong emitted, long maxRecords) {
        RunBatchDetails details = loadBatchDetails(projectUuid,
                batch.stream().map(PendingChild::runUuid).toList(), includeDetails);
        ChildPrefetchPool descendantPool = prefetchChildren(projectUuid,
                batch.stream().map(child -> found.get(child.runUuid())).toList(), details, includeDetails);
        for (PendingChild child : batch) {
            Deque<PendingChild> descendants = new ArrayDeque<>();
            try {
                exportRun(writer, projectUuid, found.get(child.runUuid()), includeDetails,
                        exportedRuns, detailRuns, true, emitted, maxRecords,
                        child.parentRunUuid(), child.rootRunUuid(), child.packageStepUuid(),
                        details.fallbackRuns().contains(child.runUuid()) ? null : details, descendants);
            } catch (IOException exception) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EXPORT_RUN_STREAM_ERROR", "Çalıştırma aktarımı yazılamadı.");
            }
            drainChildren(writer, projectUuid, descendants, includeDetails,
                    exportedRuns, detailRuns, emitted, maxRecords, descendantPool);
        }
    }

    private record PendingChild(UUID runUuid, UUID parentRunUuid, UUID rootRunUuid, UUID packageStepUuid) {}

    private void exportRun(JsonExportWriter writer, UUID projectUuid, RunRef run,
            boolean includeDetails, Set<UUID> exportedRuns, Set<UUID> detailRuns,
            boolean emitRecord, AtomicLong emitted, long maxRecords,
            UUID parentRunUuid, UUID rootRunUuid, UUID packageStepUuid,
            RunBatchDetails batchDetails, Deque<PendingChild> childQueue) throws IOException {
        if (!exportedRuns.add(run.runUuid())) {
            return;
        }

        List<ChildReference> childRuns = new ArrayList<>();
        if (emitRecord) {
            if (emitted.incrementAndGet() > maxRecords) {
                throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "EXPORT_RUN_TREE_LIMIT",
                        "Paketin alt çalıştırmaları aktarım sınırını aşıyor; eksik geçmiş üretilemez.");
            }
            writer.writeStreamingRecord(record -> {
                try {
                    record.put("runUuid", run.runUuid().toString());
                    record.put("rootRunUuid", rootRunUuid == null ? run.runUuid().toString() : rootRunUuid.toString());
                    record.put("parentRunUuid", parentRunUuid == null ? null : parentRunUuid.toString());
                    record.put("packageStepUuid", packageStepUuid == null ? null : packageStepUuid.toString());
                    record.put("publicationUuid", run.publicationUuid().toString());
                    record.put("status", run.status());
                    record.put("startType", run.startType());
                    record.put("attemptNumber", (long) run.attemptNumber());
                    record.put("createdAt", run.createdAt() == null ? null : run.createdAt().toString());
                    record.put("startedAt", run.startedAt() == null ? null : run.startedAt().toString());
                    record.put("finishedAt", run.finishedAt() == null ? null : run.finishedAt().toString());

                    long[] counters = new long[4];
                    record.startArray("steps");
                    java.util.function.Consumer<StepRef> stepConsumer = step -> {
                        try {
                            record.writeArrayValue(stepNode(step));
                            if (step.childRunUuid() != null) {
                                childRuns.add(new ChildReference(step.childRunUuid(), step.uuid()));
                            }
                            addRowCount(counters, step);
                        } catch (IOException e) {
                            throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "EXPORT_RUN_STREAM_ERROR", "Çalıştırma adımı yazılamadı.");
                        }
                    };
                    if (batchDetails == null) runs.forEachStep(projectUuid, run.runUuid(), stepConsumer);
                    else batchDetails.steps().getOrDefault(run.runUuid(), List.of()).forEach(stepConsumer);
                    record.endArray();

                    record.startArray("events");
                    java.util.function.Consumer<EventRef> eventConsumer = event -> {
                        try {
                            record.writeArrayValue(eventNode(event));
                        } catch (IOException e) {
                            throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "EXPORT_RUN_STREAM_ERROR", "Çalıştırma olayı yazılamadı.");
                        }
                    };
                    if (batchDetails == null) runs.forEachEvent(projectUuid, run.runUuid(), eventConsumer);
                    else batchDetails.events().getOrDefault(run.runUuid(), List.of()).forEach(eventConsumer);
                    record.endArray();
                    record.put("selectedRows", Long.toString(counters[0]));
                    record.put("insertedRows", Long.toString(counters[1]));
                    record.put("updatedRows", Long.toString(counters[2]));
                    record.put("deletedRows", Long.toString(counters[3]));

                    if (includeDetails && detailRuns.add(run.runUuid())) {
                        writeRunDetails(record, projectUuid, run.runUuid(), batchDetails);
                    } else {
                        record.put("details", (JsonNode) null);
                    }
                } catch (IOException e) {
                    throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "EXPORT_RUN_STREAM_ERROR", "Çalıştırma aktarımı yazılamadı.");
                }
            });
        }

        for (ChildReference childReference : childRuns) {
            if (childReference.runUuid() != null) {
                childQueue.addLast(new PendingChild(childReference.runUuid(), run.runUuid(),
                        rootRunUuid == null ? run.runUuid() : rootRunUuid,
                        childReference.packageStepUuid()));
            }
        }
    }

    private record ChildReference(UUID runUuid, UUID packageStepUuid) {
    }

    private void addRowCount(long[] counters, StepRef step) {
        Long count = step.rowCount();
        if (count == null || count < 0) return;
        int index = switch (step.logCounter() == null ? "" : step.logCounter()) {
            case "SELECT" -> 0;
            case "INSERT" -> 1;
            case "UPDATE" -> 2;
            case "DELETE" -> 3;
            default -> step.connectionRole() != null && step.connectionRole().contains("SOURCE") ? 0 : -1;
        };
        if (index < 0) return;
        try {
            counters[index] = Math.addExact(counters[index], count);
        } catch (ArithmeticException exception) {
            throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_ROW_COUNT_OVERFLOW", "Satır toplamı BIGINT sınırını aşıyor; yanlış toplam dışa aktarılamaz.");
        }
    }

    private RunQuery buildQuery(ExportScope scope, List<ExportFilter> filters, long maxRecords) {
        String view = null;
        String query = null;
        String statuses = null;
        String environment = null;
        String definitionType = null;
        OffsetDateTime from = null;
        OffsetDateTime to = null;
        Boolean scheduled = null;

        if (filters != null) {
            for (ExportFilter filter : filters) {
                String field = filter.field();
                JsonNode value = filter.value();
                switch (field) {
                    case "view" -> view = textValue(value);
                    case "query" -> query = textValue(value);
                    case "status", "statuses" -> statuses = textValue(value);
                    case "environment" -> environment = textValue(value);
                    case "definitionType" -> definitionType = textValue(value);
                    case "from" -> from = parseDateTime(textValue(value));
                    case "to" -> to = parseDateTime(textValue(value));
                    case "scheduled" -> scheduled = parseBoolean(value);
                    default -> { /* ignored */ }
                }
            }
        }

        if (scope == ExportScope.VISIBLE) {
            // VISIBLE mirrors the runs page default: RECENT view with the same
            // fallback time window used by JdbcExecutionStore.
            view = (view == null || view.isBlank()) ? "RECENT" : view;
            if (from == null && to == null) {
                from = OffsetDateTime.now().minusHours(24);
                to = OffsetDateTime.now();
            }
        }

        return new RunQuery(view, query, statuses, environment, definitionType, from, to, scheduled, maxRecords);
    }

    private String textValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Boolean parseBoolean(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            String text = value.asText().trim().toLowerCase(Locale.ROOT);
            return switch (text) {
                case "true", "yes", "1", "on" -> true;
                case "false", "no", "0", "off" -> false;
                default -> null;
            };
        }
        if (value.isNumber()) {
            return value.asInt() != 0;
        }
        return null;
    }

    private OffsetDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private List<UUID> selectedRunUuids(List<ExportFilter> filters) {
        Set<UUID> result = new LinkedHashSet<>();
        if (filters == null) throw invalidSelection();
        for (ExportFilter filter : filters) {
            if ("runUuid".equals(filter.field()) || "runUuids".equals(filter.field())) {
                if (filter.value() == null) throw invalidSelection();
                if (filter.value().isArray()) {
                    filter.value().forEach(node -> result.add(parseSelectedUuid(node)));
                } else if (filter.value().isTextual()) {
                    result.add(parseSelectedUuid(filter.value()));
                } else {
                    throw invalidSelection();
                }
            }
        }
        if (result.isEmpty()) throw invalidSelection();
        return List.copyOf(result);
    }

    private UUID parseSelectedUuid(JsonNode value) {
        if (!value.isTextual()) throw invalidSelection();
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException exception) {
            throw invalidSelection();
        }
    }

    private ExportException invalidSelection() {
        return new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                "EXPORT_SELECTION_INVALID", "Seçilen çalıştırma kimlikleri geçersiz veya boş.");
    }

    private void writeRunDetails(JsonExportWriter.JsonRecordWriter record, UUID projectUuid, UUID runUuid,
            RunBatchDetails batchDetails) throws IOException {
        record.startObject("details");
        record.startArray("kmSteps");
        java.util.function.Consumer<KmStepJournal.Row> kmWriter = step -> {
            try { record.writeArrayValue(kmStepNode(step)); }
            catch (IOException e) { throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "EXPORT_DETAIL_STREAM_ERROR", "Çalıştırma detayları yazılamadı."); }
        };
        if (batchDetails == null) kmStepJournal.forEachRow(projectUuid, runUuid, kmWriter);
        else batchDetails.kmSteps().getOrDefault(runUuid, List.of()).forEach(kmWriter);
        record.endArray();
        record.startArray("workObjects");
        java.util.function.Consumer<WorkObjectStore.ObjectRow> workWriter = object -> {
            try { record.writeArrayValue(workObjectNode(object)); }
            catch (IOException e) { throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "EXPORT_DETAIL_STREAM_ERROR", "Çalıştırma detayları yazılamadı."); }
        };
        if (batchDetails == null) workObjectStore.forEachObjectRow(projectUuid, runUuid, workWriter);
        else batchDetails.workObjects().getOrDefault(runUuid, List.of()).forEach(workWriter);
        record.endArray();
        KmStepJournal.Reconciliation reconciliation = batchDetails == null
                ? kmStepJournal.reconciliation(projectUuid, runUuid)
                : batchDetails.reconciliations().get(runUuid);
        if (reconciliation != null) {
            record.startObject("reconciliation");
            record.put("outcome", reconciliation.outcome());
            record.put("rows", decimal(reconciliation.rows()));
            record.endObject();
        }
        record.endObject();
    }

    private JsonNode kmStepNode(KmStepJournal.Row step) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("generation", Long.toString(step.generation()));
        node.put("ordinal", step.ordinal());
        node.put("stepCode", step.stepCode());
        node.put("operation", step.operation());
        node.put("site", step.site());
        node.put("slot", step.slot());
        node.put("state", step.state());
        node.put("affectedRows", decimal(step.affectedRows()));
        node.put("errorCode", step.errorCode());
        node.put("startedAt", step.startedAt() == null ? null : step.startedAt().toString());
        node.put("completedAt", step.completedAt() == null ? null : step.completedAt().toString());
        node.set("executedSql", step.executedSql() == null ? objectMapper.nullNode() : step.executedSql());
        return node;
    }

    private JsonNode workObjectNode(WorkObjectStore.ObjectRow object) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("uuid", object.uuid().toString());
        node.put("slot", object.slot());
        node.put("owner", object.owner());
        node.put("name", object.name());
        node.put("state", object.state().name());
        node.put("rows", decimal(object.rows()));
        node.put("bytes", decimal(object.bytes()));
        return node;
    }

    private JsonNode stepNode(StepRef step) {
        return objectMapper.createObjectNode()
                .put("uuid", step.uuid().toString())
                .put("parentUuid", step.parentUuid() == null ? null : step.parentUuid().toString())
                .put("code", step.code())
                .put("type", step.type())
                .put("ordinal", step.ordinal())
                .put("name", step.name())
                .put("status", step.status())
                .put("connectionRole", step.connectionRole())
                .put("risk", step.risk())
                .put("startedAt", step.startedAt() == null ? null : step.startedAt().toString())
                .put("finishedAt", step.finishedAt() == null ? null : step.finishedAt().toString())
                .put("rowCount", decimal(step.rowCount()))
                .put("byteCount", decimal(step.byteCount()))
                .put("errorCode", step.errorCode())
                .put("logCounter", step.logCounter())
                .put("transactionState", step.transactionState())
                .set("executedSql", step.executedSql() == null ? objectMapper.nullNode() : step.executedSql())
                .put("childRunUuid", step.childRunUuid() == null ? null : step.childRunUuid().toString());
    }

    private JsonNode eventNode(EventRef event) {
        return objectMapper.createObjectNode()
                .put("uuid", event.uuid().toString())
                .put("eventNumber", Long.toString(event.eventNumber()))
                .put("type", event.type())
                .put("eventTime", event.eventTime() == null ? null : event.eventTime().toString())
                .set("data", event.data());
    }

    private String decimal(Long value) {
        return value == null ? null : Long.toString(value);
    }
}

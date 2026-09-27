package tr.com.innova.akis.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.ExportModels.ExportColumn;
import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportJobRow;
import tr.com.innova.akis.export.ExportModels.ExportJobView;
import tr.com.innova.akis.export.ExportModels.ExportOutputRow;
import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.ExportModels.ExportStatus;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;
import tr.com.innova.akis.security.AuthorizationService;

import static tr.com.innova.akis.security.PermissionCodes.RUN_CANCEL;
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;
import static tr.com.innova.akis.security.PermissionCodes.IDENTITY_USER_PROVISION;
import static tr.com.innova.akis.security.PermissionCodes.SCHEMA_METADATA_READ;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportConfiguration configuration;
    private final ExportProviderRegistry registry;
    private final ExportJobRepository repository;
    private final AuthorizationService authorization;
    private final ObjectMapper objectMapper;
    private final Optional<ExportCleanupTask> cleanupTask;
    private final java.util.concurrent.Executor taskExecutor;

    private ExportService self;

    public ExportService(
            ExportConfiguration configuration,
            ExportProviderRegistry registry,
            ExportJobRepository repository,
            AuthorizationService authorization,
            ObjectMapper objectMapper) {
        this(configuration, registry, repository, authorization, objectMapper, Optional.empty(), null);
    }

    public ExportService(
            ExportConfiguration configuration,
            ExportProviderRegistry registry,
            ExportJobRepository repository,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            ExportCleanupTask cleanupTask) {
        this(configuration, registry, repository, authorization, objectMapper,
                Optional.ofNullable(cleanupTask), null);
    }

    @Autowired
    public ExportService(
            ExportConfiguration configuration,
            ExportProviderRegistry registry,
            ExportJobRepository repository,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            Optional<ExportCleanupTask> cleanupTask,
            @Qualifier("exportTaskExecutor") java.util.concurrent.Executor taskExecutor) {
        this.configuration = configuration;
        this.registry = registry;
        this.repository = repository;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.cleanupTask = cleanupTask;
        this.taskExecutor = taskExecutor;
    }

    @Autowired
    void setSelf(@Lazy ExportService self) {
        this.self = self;
    }

    @Transactional
    public ExportJobView createJob(UUID projectUuid, ExportRequest request) {
        authorization.requireProjectPermission(projectUuid,
                ExportProviderRegistry.requiredProjectPermission(request.providerId(), request.resourceId()));
        registry.validate(request);
        validateColumns(request);

        PrincipalIdentity principal = authorization.currentPrincipalIdentity();
        long concurrent = repository.countNonTerminalByCreator(principal.userId());
        if (concurrent >= configuration.maxConcurrentJobs()) {
            throw new ExportException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "EXPORT_CONCURRENT_LIMIT",
                    "Aynı anda en fazla " + configuration.maxConcurrentJobs()
                            + " aktif veri aktarım işi çalıştırabilirsiniz.");
        }

        long projectId = resolveProjectId(projectUuid);
        JsonNode filterSnapshot = filterSnapshot(request.filters());
        OffsetDateTime expiryAt = OffsetDateTime.now(ZoneOffset.UTC).plus(configuration.retentionDuration());

        ExportJobRow job = repository.create(
                projectId, principal.userId(),
                request.providerId(), request.resourceId(),
                request.scope(), filterSnapshot,
                normalizeColumns(request.selectedColumns()),
                request.includeDetails(), expiryAt);

        self.runJobAsync(job.uuid());
        return toView(job);
    }

    /** Creates a system-scoped export without assigning a fake project owner. */
    @Transactional
    public ExportJobView createGlobalJob(ExportRequest request) {
        registry.validate(request);
        validateColumns(request);
        PrincipalIdentity principal = authorization.currentPrincipalIdentity();
        if (repository.countNonTerminalByCreator(principal.userId()) >= configuration.maxConcurrentJobs()) {
            throw new ExportException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_CONCURRENT_LIMIT",
                    "Aynı anda çok fazla veri aktarım işi çalışıyor.");
        }
        OffsetDateTime expiryAt = OffsetDateTime.now(ZoneOffset.UTC).plus(configuration.retentionDuration());
        ExportJobRow job = repository.create(
                null, principal.userId(), request.providerId(), request.resourceId(), request.scope(),
                filterSnapshot(request.filters()), normalizeColumns(request.selectedColumns()),
                request.includeDetails(), expiryAt);
        self.runJobAsync(job.uuid());
        return toView(job);
    }

    @Transactional(readOnly = true)
    public ExportJobView status(UUID projectUuid, UUID exportUuid) {
        ExportJobRow job = repository.findByUuid(exportUuid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXPORT_JOB_NOT_FOUND", "Veri aktarım işi bulunamadı."));
        ensureProjectMatch(projectUuid, job.uuid());
        authorization.requireProjectPermission(projectUuid,
                ExportProviderRegistry.requiredProjectPermission(job.providerId(), job.resourceId()));
        return toView(job);
    }

    @Transactional(readOnly = true)
    public ExportDownload download(UUID projectUuid, UUID exportUuid) {
        ExportJobRow job = repository.findByUuid(exportUuid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXPORT_JOB_NOT_FOUND", "Veri aktarım işi bulunamadı."));
        ensureProjectMatch(projectUuid, job.uuid());
        authorization.requireProjectPermission(projectUuid,
                ExportProviderRegistry.requiredProjectPermission(job.providerId(), job.resourceId()));

        if (job.status() == ExportStatus.EXPIRED
                || OffsetDateTime.now(ZoneOffset.UTC).isAfter(job.expiryAt())) {
            throw new ApiException(
                    HttpStatus.GONE, "EXPORT_EXPIRED", "Veri aktarım dosyasının süresi dolmuştur.");
        }
        if (job.status() != ExportStatus.COMPLETED) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "EXPORT_NOT_READY", "Veri aktarım işi henüz tamamlanmadı.");
        }

        ExportOutputRow output = repository.findOutputForDownload(job.id())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXPORT_OUTPUT_NOT_FOUND", "Aktarım çıktı dosyası bulunamadı."));
        Path file = configuration.safeSpoolPath().resolve(output.filePath());
        if (!file.normalize().startsWith(configuration.safeSpoolPath())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_PATH_INVALID", "Geçersiz dosya yolu.");
        }
        if (!Files.exists(file)) {
            throw new ApiException(
                    HttpStatus.GONE, "EXPORT_FILE_MISSING", "Aktarım dosyası sunucuda bulunamadı.");
        }

        String filename = safeFilename(job.providerId(), job.scope());
        return new ExportDownload(file, filename, output.checksum());
    }

    @Transactional
    public void cancel(UUID projectUuid, UUID exportUuid) {
        PrincipalIdentity principal = authorization.currentPrincipalIdentity();
        ExportJobRow job = repository.findByUuid(exportUuid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXPORT_JOB_NOT_FOUND", "Veri aktarım işi bulunamadı."));
        ensureProjectMatch(projectUuid, job.uuid());
        authorization.requireProjectPermission(projectUuid,
                ExportProviderRegistry.requiredProjectPermission(job.providerId(), job.resourceId()));
        if (!repository.cancel(exportUuid, principal.userId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "EXPORT_ALREADY_TERMINAL", "İş zaten sonlandırılmış.");
        }
    }

    @Transactional(readOnly = true)
    public ExportJobView globalStatus(UUID exportUuid) {
        return toView(globalJob(exportUuid));
    }

    public void requireGlobalPermission(UUID exportUuid) {
        ExportJobRow job = globalJob(exportUuid);
        if (ExportProviderRegistry.DATASET_IDENTITY.equals(job.providerId())) {
            authorization.requireSystemPermission(IDENTITY_USER_PROVISION);
        } else if (ExportProviderRegistry.DATASET_SCHEMA_METADATA.equals(job.providerId())) {
            authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        } else {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_GLOBAL_PROVIDER_NOT_ALLOWED", "Global export kaynağı desteklenmiyor.");
        }
    }

    @Transactional(readOnly = true)
    public ExportDownload globalDownload(UUID exportUuid) {
        return downloadJob(globalJob(exportUuid));
    }

    @Transactional
    public void globalCancel(UUID exportUuid) {
        PrincipalIdentity principal = authorization.currentPrincipalIdentity();
        globalJob(exportUuid);
        if (!repository.cancel(exportUuid, principal.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPORT_ALREADY_TERMINAL",
                    "İş zaten sonlandırılmış.");
        }
    }

    /**
     * Dispatches the worker. When an executor is configured (production) the
     * job is submitted asynchronously and runs under its own transaction via the
     * self-proxy; when no executor is configured (unit tests) the job is left
     * queued so the test can drive {@link #runJob(UUID)} synchronously.
     */
    public void runJobAsync(UUID exportUuid) {
        if (taskExecutor != null) {
            taskExecutor.execute(() -> self.runJob(exportUuid));
        }
    }

    /**
     * Synchronous worker entry point used by tests and the async path.
     */
    public void runJob(UUID exportUuid) {
        Optional<ExportJobRow> claimed = repository.claim(exportUuid);
        if (claimed.isEmpty()) {
            log.debug("Export job {} no longer claimable; skipping.", exportUuid);
            return;
        }
        ExportJobRow job = claimed.get();

        Path tempFile = null;
        Path finalFile = null;
        boolean finalFileMoved = false;
        long resultRows = 0;
        try {
            ExportProvider provider = registry.resolve(job.providerId(), job.resourceId());
            Files.createDirectories(configuration.safeSpoolPath());
            tempFile = Files.createTempFile(configuration.safeSpoolPath(), "export-" + exportUuid + "-", ".tmp");
            finalFile = configuration.safeSpoolPath().resolve(finalFilename(job));

            ExportContext context = new ExportContext(
                    exportUuid, job.projectId(), job.creatorId(),
                    job.resourceId(), job.scope(), filtersFromSnapshot(job.filterSnapshot()),
                    job.selectedColumns(), job.includeDetails(),
                    "tr-TR", "UTC", System.currentTimeMillis(),
                    configuration.maxRecords());

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream fileOut = Files.newOutputStream(tempFile);
                    DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest);
                    JsonExportWriter writer = JsonExportWriterImpl.builder(objectMapper)
                            .outputStream(digestOut)
                            .columns(resolveColumns(context))
                            .redactUnsafeSql(true)
                            .build()) {

                ProgressTrackingWriter tracking = new ProgressTrackingWriter(
                        writer, job, digestOut, configuration);
                provider.streamRecords(context, tracking);
                resultRows = tracking.recordCount();
                tracking.writeSummary(resultRows, null, null, null, null);
            }

            long byteSize = Files.size(tempFile);
            if (byteSize > configuration.maxByteSize()) {
                Files.deleteIfExists(tempFile);
                fail(job, "EXPORT_SIZE_LIMIT_EXCEEDED", "Aktarım dosyası boyut sınırını aştı.");
                return;
            }

            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
            finalFileMoved = true;
            String checksum = HexFormat.of().formatHex(digest.digest());
            repository.saveOutput(job.id(), finalFile.getFileName().toString(), checksum);
            if (!repository.updateProgress(exportUuid, resultRows, resultRows, byteSize)
                    || !repository.markTerminal(exportUuid, ExportStatus.COMPLETED, null, null)) {
                throw new ExportException(HttpStatus.CONFLICT, "EXPORT_JOB_NO_LONGER_ACTIVE",
                        "Aktarım işi artık çalışmıyor.");
            }
            cleanupTask.ifPresent(task -> {
                try {
                    task.schedule();
                } catch (RuntimeException exception) {
                    log.warn("Export cleanup scheduling failed: {}", exception.getClass().getSimpleName());
                }
            });
        } catch (ExportException e) {
            cleanup(tempFile);
            if (finalFileMoved) cleanup(finalFile);
            fail(job, e.code(), "Aktarım tamamlanamadı; hata kodunu inceleyin.");
            removeUnfinishedOutput(job, finalFileMoved, finalFile);
        } catch (IOException e) {
            cleanup(tempFile);
            if (finalFileMoved) cleanup(finalFile);
            fail(job, "EXPORT_IO_ERROR", "Aktarım dosyası yazılamadı.");
            removeUnfinishedOutput(job, finalFileMoved, finalFile);
        } catch (Exception e) {
            cleanup(tempFile);
            if (finalFileMoved) cleanup(finalFile);
            fail(job, "EXPORT_INTERNAL_ERROR", "Aktarım tamamlanamadı.");
            removeUnfinishedOutput(job, finalFileMoved, finalFile);
        }
    }

    private void removeUnfinishedOutput(ExportJobRow job, boolean finalFileMoved, Path finalFile) {
        if (!finalFileMoved || finalFile == null || !Files.notExists(finalFile)) return;
        try {
            repository.removeOutputUnlessCompleted(job.id());
        } catch (RuntimeException exception) {
            log.warn("Could not remove incomplete export output row: {}", exception.getClass().getSimpleName());
        }
    }

    private void fail(ExportJobRow job, String code, String message) {
        try {
            repository.markTerminal(job.uuid(), ExportStatus.FAILED, code, message);
        } catch (RuntimeException e) {
            log.warn("Failed to mark export job {} as failed (type={}).",
                    job.uuid(), e.getClass().getSimpleName());
        }
    }

    private void cleanup(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private long resolveProjectId(UUID projectUuid) {
        return repository.findProjectId(projectUuid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Proje bulunamadı."));
    }

    private ExportJobRow globalJob(UUID exportUuid) {
        ExportJobRow job = repository.findByUuid(exportUuid)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "EXPORT_JOB_NOT_FOUND", "Veri aktarım işi bulunamadı."));
        if (job.projectId() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_JOB_NOT_FOUND",
                    "Veri aktarım işi bulunamadı.");
        }
        return job;
    }

    private ExportDownload downloadJob(ExportJobRow job) {
        if (job.status() == ExportStatus.EXPIRED
                || OffsetDateTime.now(ZoneOffset.UTC).isAfter(job.expiryAt())) {
            throw new ApiException(HttpStatus.GONE, "EXPORT_EXPIRED",
                    "Veri aktarım dosyasının süresi dolmuştur.");
        }
        if (job.status() != ExportStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPORT_NOT_READY",
                    "Aktarım işi henüz tamamlanmadı.");
        }
        ExportOutputRow output = repository.findOutputForDownload(job.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "EXPORT_OUTPUT_NOT_FOUND", "Aktarım çıktı dosyası bulunamadı."));
        Path file = configuration.safeSpoolPath().resolve(output.filePath());
        if (!file.normalize().startsWith(configuration.safeSpoolPath())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_PATH_INVALID",
                    "Geçersiz dosya yolu.");
        }
        if (!Files.exists(file)) {
            throw new ApiException(HttpStatus.GONE, "EXPORT_FILE_MISSING",
                    "Aktarım dosyası sunucuda bulunamadı.");
        }
        return new ExportDownload(file, safeFilename(job.providerId(), job.scope()), output.checksum());
    }

    private void ensureProjectMatch(UUID projectUuid, UUID exportUuid) {
        long expected = resolveProjectId(projectUuid);
        ExportJobRow job = repository.findByUuid(exportUuid)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXPORT_JOB_NOT_FOUND", "Veri aktarım işi bulunamadı."));
        if (job.projectId() == null || job.projectId().longValue() != expected) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_JOB_NOT_FOUND", "Veri aktarım işi bulunamadı.");
        }
    }

    private JsonNode filterSnapshot(List<ExportFilter> filters) {
        ArrayNode array = objectMapper.createArrayNode();
        if (filters != null) {
            for (ExportFilter filter : filters) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("field", filter.field());
                node.put("operator", filter.operator());
                node.set("value", filter.value() == null ? objectMapper.nullNode() : filter.value());
                array.add(node);
            }
        }
        return array;
    }

    private List<ExportFilter> filtersFromSnapshot(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isArray()) {
            return List.of();
        }
        List<ExportFilter> filters = new java.util.ArrayList<>();
        for (JsonNode node : snapshot) {
            filters.add(new ExportFilter(
                    node.path("field").asText(),
                    node.path("operator").asText(),
                    node.path("value")));
        }
        return filters;
    }

    private List<String> normalizeColumns(List<String> columns) {
        if (columns == null) {
            return List.of();
        }
        return columns.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private void validateColumns(ExportRequest request) {
        if (request.selectedColumns() != null && request.selectedColumns().size() > 200) {
            throw new ExportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_TOO_MANY_COLUMNS",
                    "En fazla 200 kolon seçilebilir.");
        }
    }

    private List<ExportColumn> resolveColumns(ExportContext context) {
        return context.selectedColumns().stream()
                .map(key -> new ExportColumn(key, key))
                .toList();
    }

    private Path finalFilename(ExportJobRow job) {
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String base = "akis_" + job.providerId() + "_" + job.scope().name().toLowerCase()
                + "_" + ts + "_" + job.uuid() + "_v1.json";
        return Path.of(sanitizeFilename(base));
    }

    private String safeFilename(String dataset, ExportScope scope) {
        String base = "akis_" + dataset + "_" + scope.name().toLowerCase() + "_v1.json";
        return sanitizeFilename(base);
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_")
                .replaceAll("[\\r\\n]", "")
                .replace("..", ".");
    }

    private ExportJobView toView(ExportJobRow job) {
        return new ExportJobView(
                job.uuid(), job.providerId(), job.resourceId(), job.scope(), job.status(),
                job.processedRows(), job.resultRows(), job.byteSize(),
                job.errorCode(), job.errorMessage(),
                job.expiryAt(), job.createdAt(), job.startedAt(), job.finishedAt());
    }

    public record ExportDownload(Path file, String filename, String checksum) {
    }

    /**
     * Optional hook for scheduling spool/expiration cleanup.
     */
    public interface ExportCleanupTask {
        void schedule();
    }

    /**
     * Decorator that enforces time limits and checks cancellation between records.
     */
    private final class ProgressTrackingWriter implements JsonExportWriter {

        private final JsonExportWriter delegate;
        private final ExportJobRow job;
        private final Instant deadline;
        private final DigestOutputStream stream;
        private long nextStatusCheckNanos;
        private long written;

        ProgressTrackingWriter(JsonExportWriter delegate, ExportJobRow job,
                DigestOutputStream stream, ExportConfiguration configuration) {
            this.delegate = delegate;
            this.job = job;
            this.stream = stream;
            this.deadline = Instant.now().plus(configuration.maxDuration());
        }

        @Override
        public void writeHeader(String dataset, String resourceId, ExportScope scope,
                String locale, String timeZone, List<ExportFilter> filters,
                String providerDescription) throws IOException {
            delegate.writeHeader(dataset, resourceId, scope, locale, timeZone, filters, providerDescription);
        }

        @Override
        public void writeRecord(JsonNode record) throws IOException {
            checkLimits();
            delegate.writeRecord(record);
            written++;
            maybeUpdateProgress();
        }

        @Override
        public void writeRecord(java.util.function.Consumer<JsonExportWriter.JsonObjectBuilder> builder) throws IOException {
            checkLimits();
            delegate.writeRecord(builder);
            written++;
            maybeUpdateProgress();
        }

        @Override
        public void writeStreamingRecord(java.util.function.Consumer<JsonExportWriter.JsonRecordWriter> writer) throws IOException {
            checkLimits();
            delegate.writeStreamingRecord(writer);
            written++;
            maybeUpdateProgress();
        }

        @Override
        public void writeSummary(long processedRows, Long selected, Long inserted, Long updated, Long deleted) throws IOException {
            delegate.writeSummary(processedRows, selected, inserted, updated, deleted);
        }

        @Override
        public void writeDetails(JsonNode details) throws IOException {
            delegate.writeDetails(details);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public long recordCount() {
            return delegate.recordCount();
        }

        private void checkLimits() {
            if (Instant.now().isAfter(deadline)) {
                throw new ExportException(
                        HttpStatus.REQUEST_TIMEOUT,
                        "EXPORT_TIME_LIMIT_EXCEEDED",
                        "Aktarım süre sınırını aştı.");
            }
            long now = System.nanoTime();
            if (now < nextStatusCheckNanos) return;
            nextStatusCheckNanos = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            if (repository.currentStatus(job.uuid()).orElse(null) != ExportStatus.RUNNING) {
                throw new ExportException(
                        HttpStatus.CONFLICT,
                        "EXPORT_JOB_NO_LONGER_ACTIVE",
                        "Aktarım işi artık çalışmıyor.");
            }
        }

        private void maybeUpdateProgress() {
            if (written % 1000 == 0) {
                if (!repository.updateProgress(job.uuid(), written, delegate.recordCount(), 0)) {
                    throw new ExportException(HttpStatus.CONFLICT, "EXPORT_JOB_NO_LONGER_ACTIVE",
                            "Aktarım işi artık çalışmıyor.");
                }
            }
        }
    }
}

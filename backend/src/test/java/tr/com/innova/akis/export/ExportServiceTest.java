package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tr.com.innova.akis.security.PermissionCodes.CATALOG_READ;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

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

class ExportServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final PrincipalIdentity PRINCIPAL =
            new PrincipalIdentity(7, UUID.randomUUID(), "exporter", "Exporter");

    @TempDir
    Path tempDir;

    private FakeRepository repository;
    private FakeProvider provider;
    private ExportProviderRegistry registry;
    private ExportConfiguration configuration;
    private StubAuthorization authorization;
    private ExportService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        provider = new FakeProvider();
        registry = new ExportProviderRegistry(List.of(provider));
        configuration = new ExportConfiguration(tempDir, null, null, null, null, null, null);
        authorization = new StubAuthorization();
        service = new ExportService(configuration, registry, repository, authorization, new ObjectMapper());
        service.setSelf(service);
        repository.projectIds.put(PROJECT_UUID, 1L);
    }

    @Test
    void createsQueuedJobAndRunsToCompletion() {
        ExportRequest request = request(ExportScope.ALL);
        ExportJobView view = service.createJob(PROJECT_UUID, request);

        assertEquals(ExportStatus.QUEUED, view.status());
        assertEquals(1, repository.jobs.size());

        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.COMPLETED, job.status());
        assertEquals(2, job.processedRows());
        assertEquals(2, job.resultRows());
        assertTrue(job.byteSize() > 0);
        // The filename includes the job UUID, so same-second jobs cannot collide.
        try (var files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().matches(
                    "akis_runs_all_[0-9TZ]+_" + view.uuid() + "_v1\\.json")));
        } catch (IOException e) {
            throw new AssertionError("Could not list spool directory", e);
        }
    }

    @Test
    void twoSameScopeJobsReceiveDistinctFilesEvenWithinOneSecond() {
        ExportJobView first = service.createJob(PROJECT_UUID, request(ExportScope.ALL));
        ExportJobView second = service.createJob(PROJECT_UUID, request(ExportScope.ALL));

        service.runJob(first.uuid());
        service.runJob(second.uuid());

        assertEquals(ExportStatus.COMPLETED, repository.findByUuid(first.uuid()).orElseThrow().status());
        assertEquals(ExportStatus.COMPLETED, repository.findByUuid(second.uuid()).orElseThrow().status());
        String firstFile = repository.outputs.get(first.uuid()).filePath();
        String secondFile = repository.outputs.get(second.uuid()).filePath();
        assertFalse(firstFile.equals(secondFile));
        assertTrue(firstFile.contains(first.uuid().toString()));
        assertTrue(secondFile.contains(second.uuid().toString()));
        assertTrue(Files.exists(tempDir.resolve(firstFile)));
        assertTrue(Files.exists(tempDir.resolve(secondFile)));
    }

    @Test
    void enforcesPerUserConcurrentLimit() {
        ExportRequest request = request(ExportScope.ALL);
        service.createJob(PROJECT_UUID, request);
        service.createJob(PROJECT_UUID, request);

        ExportException error = assertThrows(ExportException.class,
                () -> service.createJob(PROJECT_UUID, request));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
        assertEquals("EXPORT_CONCURRENT_LIMIT", error.code());
    }

    @Test
    void cancellationPreventsWorkerFromClaiming() {
        ExportRequest request = request(ExportScope.ALL);
        ExportJobView view = service.createJob(PROJECT_UUID, request);

        service.cancel(PROJECT_UUID, view.uuid());
        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.CANCELLED, job.status());
    }

    @Test
    void catalogExportUsesCatalogPermissionThroughoutItsLifecycle() {
        ExportRequest request = new ExportRequest("models", "data-objects", ExportScope.ALL,
                List.of(), List.of(), false, "tr-TR", "UTC");
        ExportJobView view = service.createJob(PROJECT_UUID, request);
        assertEquals(CATALOG_READ, authorization.permission);

        authorization.permission = null;
        service.status(PROJECT_UUID, view.uuid());
        assertEquals(CATALOG_READ, authorization.permission);

        authorization.permission = null;
        assertThrows(ApiException.class, () -> service.download(PROJECT_UUID, view.uuid()));
        assertEquals(CATALOG_READ, authorization.permission);

        authorization.permission = null;
        service.cancel(PROJECT_UUID, view.uuid());
        assertEquals(CATALOG_READ, authorization.permission);
    }

    @Test
    void alreadyCancelledJobIsNotClaimedByWorker() {
        provider.batchCount = 5;
        ExportRequest request = request(ExportScope.ALL);
        ExportJobView view = service.createJob(PROJECT_UUID, request);

        service.runJob(view.uuid());
        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.COMPLETED, job.status());

        ExportJobView second = service.createJob(PROJECT_UUID, request);
        repository.markCancelled(second.uuid());
        service.runJob(second.uuid());
        ExportJobRow stopped = repository.findByUuid(second.uuid()).orElseThrow();
        assertEquals(ExportStatus.CANCELLED, stopped.status());
    }

    @Test
    void cancellationAfterFirstWrittenRecordLeavesNoDownloadableFile() throws IOException {
        provider.recordsPerBatch = 1_001;
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));
        provider.afterFirstRecord = () -> repository.markCancelled(view.uuid());

        service.runJob(view.uuid());

        assertEquals(ExportStatus.CANCELLED, repository.findByUuid(view.uuid()).orElseThrow().status());
        assertTrue(provider.attemptedWrites < 1_001);
        assertTrue(repository.outputs.isEmpty());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void deadlineDuringStreamFailsAndRemovesPartialFile() throws IOException {
        configuration = new ExportConfiguration(tempDir, null, null,
                Duration.ofSeconds(1), null, null, null);
        service = new ExportService(configuration, registry, repository, authorization, new ObjectMapper());
        service.setSelf(service);
        provider.afterFirstRecord = () -> java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1_100));
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));

        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.FAILED, job.status());
        assertEquals("EXPORT_TIME_LIMIT_EXCEEDED", job.errorCode());
        assertEquals(1, provider.attemptedWrites);
        assertTrue(repository.outputs.isEmpty());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void removedProviderAfterClaimBecomesTerminalFailure() throws IOException {
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));
        ExportService restartedWorker = new ExportService(configuration,
                new ExportProviderRegistry(List.of()), repository, authorization, new ObjectMapper());
        restartedWorker.setSelf(restartedWorker);

        restartedWorker.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.FAILED, job.status());
        assertEquals("EXPORT_PROVIDER_NOT_AVAILABLE", job.errorCode());
        assertTrue(repository.outputs.isEmpty());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void expiryBlocksDownload() {
        ExportRequest request = request(ExportScope.ALL);
        ExportJobView view = service.createJob(PROJECT_UUID, request);
        service.runJob(view.uuid());

        repository.expireAll(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        ApiException error = assertThrows(ApiException.class,
                () -> service.download(PROJECT_UUID, view.uuid()));
        assertEquals(HttpStatus.GONE, error.status());
        assertEquals("EXPORT_EXPIRED", error.code());
    }

    @Test
    void progressIsUpdatedDuringRun() {
        provider.recordsPerBatch = 500;
        provider.batchCount = 3;
        ExportRequest request = request(ExportScope.ALL);
        ExportJobView view = service.createJob(PROJECT_UUID, request);
        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.COMPLETED, job.status());
        assertTrue(job.processedRows() >= 0);
    }

    @Test
    void terminalFailureIsRecorded() {
        provider.fail = true;
        ExportRequest request = request(ExportScope.ALL);
        ExportJobView view = service.createJob(PROJECT_UUID, request);
        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.FAILED, job.status());
        assertEquals("PROVIDER_FAILURE", job.errorCode());
        assertNotNull(job.errorMessage());
    }

    @Test
    void unexpectedFailureDoesNotLeakDetailsOrPublishPartialFile() throws IOException {
        provider.runtimeFailure = true;
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));

        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.FAILED, job.status());
        assertEquals("EXPORT_INTERNAL_ERROR", job.errorCode());
        assertFalse(job.errorMessage().contains("sensitive database detail"));
        assertTrue(repository.outputs.isEmpty());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void providerFailureMessageIsNotStoredInJobStatus() {
        provider.sensitiveFailure = true;
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));

        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.FAILED, job.status());
        assertEquals("PROVIDER_FAILURE", job.errorCode());
        assertFalse(job.errorMessage().contains("sensitive database detail"));
        assertTrue(repository.outputs.isEmpty());
    }

    @Test
    void failedOutputRegistrationRemovesMovedFile() throws IOException {
        repository.failSaveOutput = true;
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));

        service.runJob(view.uuid());

        ExportJobRow job = repository.findByUuid(view.uuid()).orElseThrow();
        assertEquals(ExportStatus.FAILED, job.status());
        assertTrue(repository.outputs.isEmpty());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void expiryBetweenOutputRegistrationAndCompletionCannotResurrectJob() throws IOException {
        repository.expireOnSaveOutput = true;
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));

        service.runJob(view.uuid());

        assertEquals(ExportStatus.EXPIRED, repository.findByUuid(view.uuid()).orElseThrow().status());
        assertTrue(repository.outputs.isEmpty());
        assertThrows(ApiException.class, () -> service.download(PROJECT_UUID, view.uuid()));
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void cleanupSchedulingFailureCannotInvalidateCompletedExport() {
        service = new ExportService(configuration, registry, repository, authorization,
                new ObjectMapper(), () -> { throw new IllegalStateException("cleanup unavailable"); });
        service.setSelf(service);
        ExportJobView view = service.createJob(PROJECT_UUID, request(ExportScope.ALL));

        service.runJob(view.uuid());

        assertEquals(ExportStatus.COMPLETED, repository.findByUuid(view.uuid()).orElseThrow().status());
        assertTrue(Files.exists(tempDir.resolve(repository.outputs.get(view.uuid()).filePath())));
    }

    private ExportRequest request(ExportScope scope) {
        return new ExportRequest(
                ExportProviderRegistry.DATASET_RUNS,
                ExportProviderRegistry.RESOURCE_RUN_HISTORY,
                scope, List.of("status"), List.of(), false, "tr-TR", "UTC");
    }

    private static final class FakeRepository extends ExportJobRepository {

        private final Map<UUID, ExportJobRow> jobs = new HashMap<>();
        private final Map<UUID, ExportOutputRow> outputs = new HashMap<>();
        private final Map<UUID, Long> projectIds = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);
        private final ObjectMapper objectMapper = new ObjectMapper();
        boolean failSaveOutput;
        boolean expireOnSaveOutput;

        FakeRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public ExportJobRow create(long projectId, long creatorId, String providerId,
                String resourceId, ExportScope scope, JsonNode filterSnapshot,
                List<String> selectedColumns, boolean includeDetails, OffsetDateTime expiryAt) {
            UUID uuid = UUID.randomUUID();
            ExportJobRow job = new ExportJobRow(
                    ids.getAndIncrement(), uuid, projectId, creatorId,
                    providerId, resourceId, scope, filterSnapshot, selectedColumns, includeDetails,
                    ExportStatus.QUEUED, 0, 0, 0, null, null,
                    expiryAt, OffsetDateTime.now(ZoneOffset.UTC), null, null,
                    OffsetDateTime.now(ZoneOffset.UTC), 1);
            jobs.put(uuid, job);
            return job;
        }

        @Override
        public Optional<ExportJobRow> claim(UUID uuid) {
            ExportJobRow job = jobs.get(uuid);
            if (job == null || job.status() != ExportStatus.QUEUED) {
                return Optional.empty();
            }
            ExportJobRow claimed = copy(job, ExportStatus.RUNNING);
            jobs.put(uuid, claimed);
            return Optional.of(claimed);
        }

        @Override
        public boolean updateProgress(UUID uuid, long processedRows, long resultRows, long byteSize) {
            ExportJobRow job = jobs.get(uuid);
            if (job == null || job.status() != ExportStatus.RUNNING) return false;
            jobs.put(uuid, new ExportJobRow(job.id(), job.uuid(), job.projectId(), job.creatorId(),
                    job.providerId(), job.resourceId(), job.scope(), job.filterSnapshot(),
                    job.selectedColumns(), job.includeDetails(), job.status(), processedRows, resultRows,
                    byteSize, job.errorCode(), job.errorMessage(), job.expiryAt(),
                    job.createdAt(), job.startedAt(), job.finishedAt(),
                    OffsetDateTime.now(ZoneOffset.UTC), job.version() + 1));
            return true;
        }

        @Override
        public boolean markTerminal(UUID uuid, ExportStatus status, String errorCode, String errorMessage) {
            ExportJobRow job = jobs.get(uuid);
            if (job == null || job.status() != ExportStatus.RUNNING) return false;
            jobs.put(uuid, copy(job, status, errorCode, errorMessage));
            return true;
        }

        @Override
        public boolean markExpired(UUID uuid) {
            return markTerminal(uuid, ExportStatus.EXPIRED, null, null);
        }

        @Override
        public int expireOlderThan(OffsetDateTime cutoff) {
            int count = 0;
            for (Map.Entry<UUID, ExportJobRow> entry : jobs.entrySet()) {
                if (entry.getValue().expiryAt().isBefore(cutoff)
                        && !List.of(ExportStatus.FAILED, ExportStatus.CANCELLED, ExportStatus.EXPIRED).contains(entry.getValue().status())) {
                    jobs.put(entry.getKey(), copy(entry.getValue(), ExportStatus.EXPIRED));
                    count++;
                }
            }
            return count;
        }

        void expireAll(OffsetDateTime cutoff) {
            for (Map.Entry<UUID, ExportJobRow> entry : jobs.entrySet()) {
                if (entry.getValue().status() == ExportStatus.COMPLETED) {
                    jobs.put(entry.getKey(), copy(entry.getValue(), ExportStatus.EXPIRED));
                }
            }
        }

        @Override
        public Optional<ExportJobRow> findByUuid(UUID uuid) {
            return Optional.ofNullable(jobs.get(uuid));
        }

        @Override
        public Optional<ExportStatus> currentStatus(UUID uuid) {
            return findByUuid(uuid).map(ExportJobRow::status);
        }

        @Override
        public Optional<ExportJobView> findViewByUuid(UUID uuid) {
            return findByUuid(uuid).map(this::toView);
        }

        @Override
        public Optional<ExportOutputRow> findOutputForDownload(long exportJobId) {
            return Optional.ofNullable(outputs.get(idToUuid(exportJobId)));
        }

        @Override
        public void saveOutput(long exportJobId, String filePath, String checksum) {
            if (failSaveOutput) throw new IllegalStateException("sensitive database detail");
            UUID uuid = idToUuid(exportJobId);
            outputs.put(uuid,
                    new ExportOutputRow(0, exportJobId, filePath, checksum, OffsetDateTime.now(ZoneOffset.UTC)));
            if (expireOnSaveOutput) {
                jobs.put(uuid, copy(jobs.get(uuid), ExportStatus.EXPIRED));
            }
        }

        @Override
        public void removeOutputUnlessCompleted(long exportJobId) {
            UUID uuid = idToUuid(exportJobId);
            if (jobs.get(uuid).status() != ExportStatus.COMPLETED) outputs.remove(uuid);
        }

        @Override
        public boolean cancel(UUID uuid, long creatorId) {
            ExportJobRow job = jobs.get(uuid);
            if (job == null || (job.status() != ExportStatus.QUEUED && job.status() != ExportStatus.RUNNING)) {
                return false;
            }
            jobs.put(uuid, copy(job, ExportStatus.CANCELLED));
            return true;
        }

        void markCancelled(UUID uuid) {
            ExportJobRow job = jobs.get(uuid);
            if (job != null) {
                jobs.put(uuid, copy(job, ExportStatus.CANCELLED));
            }
        }

        @Override
        public long countNonTerminalByCreator(long creatorId) {
            return jobs.values().stream()
                    .filter(j -> j.creatorId() == creatorId)
                    .filter(j -> j.status() == ExportStatus.QUEUED || j.status() == ExportStatus.RUNNING)
                    .count();
        }

        @Override
        public List<ExportJobView> listRecentByProject(UUID projectUuid, int limit) {
            return List.of();
        }

        @Override
        public Optional<Long> findProjectId(UUID projectUuid) {
            return Optional.ofNullable(projectIds.get(projectUuid));
        }

        private ExportJobRow copy(ExportJobRow job, ExportStatus status) {
            return copy(job, status, job.errorCode(), job.errorMessage());
        }

        private ExportJobRow copy(ExportJobRow job, ExportStatus status, String errorCode, String errorMessage) {
            return new ExportJobRow(job.id(), job.uuid(), job.projectId(), job.creatorId(),
                    job.providerId(), job.resourceId(), job.scope(), job.filterSnapshot(),
                    job.selectedColumns(), job.includeDetails(), status,
                    job.processedRows(), job.resultRows(), job.byteSize(),
                    errorCode, errorMessage, job.expiryAt(),
                    job.createdAt(), job.startedAt(),
                    status == ExportStatus.QUEUED ? null : OffsetDateTime.now(ZoneOffset.UTC),
                    OffsetDateTime.now(ZoneOffset.UTC), job.version() + 1);
        }

        private ExportJobView toView(ExportJobRow job) {
            return new ExportJobView(job.uuid(), job.providerId(), job.resourceId(),
                    job.scope(), job.status(), job.processedRows(), job.resultRows(),
                    job.byteSize(), job.errorCode(), job.errorMessage(),
                    job.expiryAt(), job.createdAt(), job.startedAt(), job.finishedAt());
        }

        private UUID idToUuid(long id) {
            return jobs.values().stream()
                    .filter(j -> j.id() == id)
                    .findFirst()
                    .map(ExportJobRow::uuid)
                    .orElse(null);
        }
    }

    private static final class FakeProvider implements ExportProvider {

        int batchCount = 1;
        int recordsPerBatch = 2;
        boolean fail = false;
        boolean runtimeFailure = false;
        boolean sensitiveFailure = false;
        Runnable afterFirstRecord;
        int attemptedWrites;

        @Override
        public boolean supports(String providerId, String resourceId) {
            return ExportProviderRegistry.DATASET_RUNS.equals(providerId)
                    && ExportProviderRegistry.RESOURCE_RUN_HISTORY.equals(resourceId)
                    || ExportProviderRegistry.DATASET_MODELS.equals(providerId)
                    && ExportProviderRegistry.RESOURCE_DATA_OBJECTS.equals(resourceId);
        }

        @Override
        public void validate(ExportRequest request) {
            if (request.scope() == null) {
                throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_SCOPE_REQUIRED", "Scope required");
            }
        }

        @Override
        public void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException {
            if (fail) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR, "PROVIDER_FAILURE", "Provider failed");
            }
            if (sensitiveFailure) {
                throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR, "PROVIDER_FAILURE",
                        "sensitive database detail");
            }
            writer.writeHeader(ExportProviderRegistry.DATASET_RUNS,
                    ExportProviderRegistry.RESOURCE_RUN_HISTORY,
                    context.scope(), context.locale(), context.timeZone(),
                    context.filters(), "test provider");
            if (runtimeFailure) {
                writer.writeRecord(x -> x.put("id", "partial"));
                throw new IllegalStateException("sensitive database detail");
            }
            for (int batch = 0; batch < batchCount; batch++) {
                final int b = batch;
                for (int i = 0; i < recordsPerBatch; i++) {
                    final int r = i;
                    writer.writeRecord(x -> x.put("id", String.valueOf(b * recordsPerBatch + r)));
                    attemptedWrites++;
                    if (attemptedWrites == 1 && afterFirstRecord != null) afterFirstRecord.run();
                }
            }
        }
    }

    private static final class StubAuthorization extends AuthorizationService {

        String permission;

        StubAuthorization() {
            super(new tr.com.innova.akis.security.AuthorizationRepository(null));
        }

        @Override
        public void requireProjectPermission(UUID projectUuid, String permissionCode) {
            permission = permissionCode;
        }

        @Override
        public PrincipalIdentity currentPrincipalIdentity() {
            return PRINCIPAL;
        }
    }
}

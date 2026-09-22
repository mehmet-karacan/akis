package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.IdempotencyReservation;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;
import tr.com.innova.akis.execution.ExecutionModels.RunEventRow;
import tr.com.innova.akis.execution.ExecutionModels.RunEventPage;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.RunSearch;
import tr.com.innova.akis.execution.ExecutionModels.RunSummaryPage;
import tr.com.innova.akis.execution.ExecutionModels.RunStepRow;
import tr.com.innova.akis.execution.ExecutionModels.StartResult;
import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;
import tr.com.innova.akis.execution.ExecutionRecoveryStore.RecoveryApplication;
import tr.com.innova.akis.metadata.ApiException;

@Service
public class ExecutionService {

    private static final String IDEMPOTENCY_SCOPE = "MANUAL_RUN";
    private static final String PRODUCTION_RISK = "URETIM";
    private static final int MINIMUM_KEY_LENGTH = 8;
    private static final int MAXIMUM_KEY_LENGTH = 200;

    private final ExecutionStore store;
    private final ExecutionPermissionGate permissionGate;
    private final ExecutionFeatureFlags flags;
    private final RunStateMachine stateMachine;
    private final ExecutionRecoveryStore recoveryStore;
    private ExecutionChunkStore chunkStore;
    private final RecoveryPlanner recoveryPlanner = new RecoveryPlanner();

    ExecutionService(
            ExecutionStore store,
            ExecutionPermissionGate permissionGate,
            ExecutionFeatureFlags flags,
            RunStateMachine stateMachine) {
        this(store, permissionGate, flags, stateMachine, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ExecutionService(
            ExecutionStore store,
            ExecutionPermissionGate permissionGate,
            ExecutionFeatureFlags flags,
            RunStateMachine stateMachine,
            ExecutionRecoveryStore recoveryStore) {
        this.store = store;
        this.permissionGate = permissionGate;
        this.flags = flags;
        this.stateMachine = stateMachine;
        this.recoveryStore = recoveryStore;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setChunkStore(ExecutionChunkStore chunkStore) { this.chunkStore = chunkStore; }

    @Transactional
    StartResult start(
            UUID projectUuid,
            UUID publicationUuid,
            String idempotencyKey,
            Actor actor) {
        return start(projectUuid, publicationUuid, idempotencyKey, actor, null);
    }

    @Transactional
    StartResult start(
            UUID projectUuid,
            UUID publicationUuid,
            String idempotencyKey,
            Actor actor,
            Integer batchRows) {
        requireManualRequestsEnabled();
        String safeKey = idempotencyKey(idempotencyKey);
        if (publicationUuid == null) {
            throw validation("Yayın UUID değeri gereklidir.");
        }
        if (batchRows != null && (batchRows < 1 || batchRows > 5000)) {
            throw validation("Batch boyutu 1 ile 5000 arasında olmalıdır.");
        }
        long projectId = store.findProjectId(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
        String requestHash = sha256("{\"publicationUuid\":\"" + publicationUuid + "\""
                + (batchRows == null ? "" : ",\"batchRows\":" + batchRows) + "}");
        String keyHash = sha256(safeKey);
        boolean reserved = store.reserveIdempotency(
                projectId, actor.id(), IDEMPOTENCY_SCOPE,
                keyHash, requestHash, UUID.randomUUID());
        IdempotencyReservation reservation = store.lockIdempotency(
                        projectId, actor.id(), IDEMPOTENCY_SCOPE, keyHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency reservation could not be locked."));
        if (!requestHash.equals(reservation.requestHash())) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key farklı bir çalıştırma isteğinde daha önce kullanılmış.");
        }
        PublicationContext publication = store.lockPublication(projectUuid, publicationUuid)
                .orElseThrow(() -> notFound("Projeye ait yayın bulunamadı."));
        if (PRODUCTION_RISK.equals(publication.environmentRisk())) {
            permissionGate.requireProductionRun(projectUuid);
        }
        if (!reserved) {
            if (reservation.jobRequestId() == null) {
                throw conflict(
                        "RUN_REQUEST_IN_PROGRESS",
                        "Aynı çalıştırma isteği halen işleniyor.");
            }
            RunRow existing = store.findByJobRequestId(
                            projectId, reservation.jobRequestId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Completed idempotency record has no run."));
            return new StartResult(existing, false);
        }
        if (!"AKTIF".equals(publication.publicationStatus())) {
            throw conflict(
                    "PUBLICATION_NOT_ACTIVE",
                    "Yalnız aktif yayın için çalıştırma talebi oluşturulabilir.");
        }

        RunRow created = store.createQueuedRun(
                publication, actor, requestHash, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), batchRows);
        store.completeIdempotency(reservation.id(), created.jobRequestId(), created);
        return new StartResult(created, true);
    }

    @Transactional(readOnly = true)
    List<RunRow> list(UUID projectUuid) {
        requireProject(projectUuid);
        return store.list(projectUuid);
    }

    @Transactional(readOnly = true)
    RunSummaryPage search(UUID projectUuid, RunSearch search) {
        requireProject(projectUuid);
        if (search.page() < 0 || search.size() < 1 || search.size() > 200) {
            throw validation("Sayfa numarası ve sayfa boyutu geçersiz.");
        }
        if (!List.of("RECENT", "ACTIVE", "FAILED", "HISTORY").contains(search.view())) {
            throw validation("Çalıştırma görünümü geçersiz.");
        }
        return store.search(projectUuid, search);
    }

    @Transactional(readOnly = true)
    RunRow get(UUID projectUuid, UUID runUuid) {
        return store.find(projectUuid, runUuid)
                .orElseThrow(() -> notFound("Çalıştırma bulunamadı."));
    }

    @Transactional(readOnly = true)
    List<RunEventRow> events(UUID projectUuid, UUID runUuid) {
        get(projectUuid, runUuid);
        return store.listEvents(projectUuid, runUuid);
    }

    @Transactional(readOnly = true)
    RunEventPage events(UUID projectUuid, UUID runUuid, long after, int size) {
        get(projectUuid, runUuid);
        if (after < 0 || size < 1 || size > 500) {
            throw validation("Olay cursor veya sayfa boyutu geçersiz.");
        }
        return store.listEvents(projectUuid, runUuid, after, size);
    }

    @Transactional(readOnly = true)
    List<RunStepRow> steps(UUID projectUuid, UUID runUuid) {
        get(projectUuid, runUuid);
        return store.listSteps(projectUuid, runUuid);
    }

    @Transactional(readOnly = true)
    ExecutionChunkStore.ChunkPage chunks(
            UUID projectUuid, UUID runUuid, UUID stepUuid,
            long after, int size, String status) {
        get(projectUuid, runUuid);
        if (chunkStore == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_DISABLED",
                    "Parçalı aktarım kanıtları bu dağıtımda kullanılamıyor.");
        }
        try { return chunkStore.list(projectUuid, runUuid, stepUuid, after, size, status); }
        catch (IllegalArgumentException exception) {
            throw validation("Chunk cursor veya durum filtresi geçersiz.");
        }
    }

    @Transactional(readOnly = true)
    RecoveryPlan recoveryPlan(UUID projectUuid, UUID runUuid) {
        RunRow run = get(projectUuid, runUuid);
        boolean snapshotComplete = store.hasCompleteInputSnapshot(projectUuid, runUuid);
        return recoveryPlanner.plan(
                run, store.listSteps(projectUuid, runUuid), snapshotComplete,
                flags.recoveryRuntimeReady());
    }

    @Transactional
    RecoveryApplication recover(
            UUID projectUuid,
            UUID runUuid,
            String idempotencyKey,
            String actionValue,
            String expectedStateVersionValue,
            String expectedPlanHash,
            Actor actor) {
        requireManualRequestsEnabled();
        if (!flags.recoveryRuntimeReady() || recoveryStore == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_DISABLED",
                    "Güvenli yeniden çalıştırma bu dağıtımda kapalıdır.");
        }
        RecoveryAction action;
        try { action = RecoveryAction.valueOf(actionValue == null ? "" : actionValue); }
        catch (IllegalArgumentException exception) {
            throw validation("Recovery eylemi geçersiz.");
        }
        if (!List.of(RecoveryAction.RETRY_FAILED_UNIT, RecoveryAction.RESUME,
                RecoveryAction.RESTART).contains(action)) {
            throw validation("Bu eylem mevcut özel operasyon uç noktasından yürütülmelidir.");
        }
        String safeKey = idempotencyKey(idempotencyKey);
        long expectedStateVersion;
        try { expectedStateVersion = Long.parseLong(expectedStateVersionValue); }
        catch (RuntimeException exception) {
            throw validation("Recovery state sürümü geçersiz.");
        }
        if (expectedStateVersion < 0) throw validation("Recovery state sürümü geçersiz.");
        String keyHash = sha256(safeKey);
        String requestHash = sha256(projectUuid + "|" + runUuid + "|" + action.name()
                + "|" + expectedStateVersion + "|" + expectedPlanHash);
        RunRow locked = store.lock(projectUuid, runUuid)
                .orElseThrow(() -> notFound("Çalıştırma bulunamadı."));
        PublicationContext publication = store.lockPublication(
                        projectUuid, locked.publicationUuid())
                .orElseThrow(() -> conflict(
                        "PUBLICATION_NOT_AVAILABLE",
                        "Çalıştırmanın sabitlenmiş yayını artık erişilebilir değil."));
        if (PRODUCTION_RISK.equals(publication.environmentRisk())) {
            permissionGate.requireProductionRun(projectUuid);
        }
        if (!"AKTIF".equals(publication.publicationStatus())) {
            throw conflict(
                    "PUBLICATION_NOT_ACTIVE",
                    "Geri kazanım yalnız aktif ve geri çekilmemiş yayınla uygulanabilir.");
        }
        Optional<RecoveryApplication> existing = recoveryStore.find(
                projectUuid, runUuid, actor.id(), keyHash);
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().requestHash())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key farklı bir recovery isteğinde kullanılmış.");
            }
            return existing.get();
        }
        RecoveryPlan current = recoveryPlanner.plan(
                locked, store.listSteps(projectUuid, runUuid),
                store.hasCompleteInputSnapshot(projectUuid, runUuid), true);
        if (expectedStateVersion != current.expectedStateVersion()
                || expectedPlanHash == null || !expectedPlanHash.equals(current.planHash())) {
            throw conflict("RECOVERY_PLAN_STALE",
                    "Çalıştırma kanıtı değişti; recovery planını yeniden yükleyin.");
        }
        if (!current.allowedActions().contains(action)) {
            throw conflict(current.reasonCodes().isEmpty()
                            ? "RECOVERY_NOT_ALLOWED" : current.reasonCodes().getFirst(),
                    "İstenen recovery eylemi güncel kanıtla güvenli değildir.");
        }
        return recoveryStore.create(
                locked, actor, action, keyHash, requestHash, current);
    }

    @Transactional
    RunRow cancel(UUID projectUuid, UUID runUuid, Actor actor) {
        requireManualRequestsEnabled();
        RunRow run = store.lock(projectUuid, runUuid)
                .orElseThrow(() -> notFound("Çalıştırma bulunamadı."));
        if (stateMachine.queuedCancellation(run.status())
                == RunStateMachine.CancellationDecision.ALREADY_CANCELLED) {
            return run;
        }
        return store.cancelQueued(run, actor, UUID.randomUUID());
    }

    /** Human decision after a reconciliation conflict: release the target so runs can fence it again. Reason is mandatory. */
    @Transactional
    RunRow releaseTarget(UUID projectUuid, UUID runUuid, String reason, Actor actor) {
        requireManualRequestsEnabled();
        if (reason == null || reason.isBlank() || reason.length() > 500) throw validation("Serbest bırakma gerekçesi (1-500 karakter) zorunludur.");
        RunRow run = store.lock(projectUuid, runUuid).orElseThrow(() -> notFound("Çalıştırma bulunamadı."));
        if (!store.releaseQuarantinedTarget(run, actor, reason.trim())) {
            throw conflict("TARGET_NOT_QUARANTINED", "Bu çalıştırmanın karantinada bir hedefi yok.");
        }
        return get(projectUuid, runUuid);
    }

    private void requireProject(UUID projectUuid) {
        if (!store.projectExists(projectUuid)) {
            throw notFound("Proje bulunamadı.");
        }
    }

    private void requireManualRequestsEnabled() {
        if (!flags.acceptManualRequests()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EXECUTION_REQUESTS_DISABLED",
                    "Manuel çalıştırma talepleri bu dağıtımda kapalıdır.");
        }
    }

    private String idempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key başlığı gereklidir.");
        }
        String normalized = value.trim();
        if (normalized.length() < MINIMUM_KEY_LENGTH || normalized.length() > MAXIMUM_KEY_LENGTH) {
            throw validation("Idempotency-Key 8-200 karakter aralığında olmalıdır.");
        }
        return normalized;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "RUN_VALIDATION_FAILED", message);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}

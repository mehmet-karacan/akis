package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.IdempotencyReservation;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;
import tr.com.innova.akis.execution.ExecutionModels.RunEventRow;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.StartResult;
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

    ExecutionService(
            ExecutionStore store,
            ExecutionPermissionGate permissionGate,
            ExecutionFeatureFlags flags,
            RunStateMachine stateMachine) {
        this.store = store;
        this.permissionGate = permissionGate;
        this.flags = flags;
        this.stateMachine = stateMachine;
    }

    @Transactional
    StartResult start(
            UUID projectUuid,
            UUID publicationUuid,
            String idempotencyKey,
            Actor actor) {
        requireManualRequestsEnabled();
        String safeKey = idempotencyKey(idempotencyKey);
        if (publicationUuid == null) {
            throw validation("Yayın UUID değeri gereklidir.");
        }
        long projectId = store.findProjectId(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
        String requestHash = sha256("{\"publicationUuid\":\"" + publicationUuid + "\"}");
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
                UUID.randomUUID(), UUID.randomUUID());
        store.completeIdempotency(reservation.id(), created.jobRequestId(), created);
        return new StartResult(created, true);
    }

    @Transactional(readOnly = true)
    List<RunRow> list(UUID projectUuid) {
        requireProject(projectUuid);
        return store.list(projectUuid);
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

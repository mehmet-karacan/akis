package tr.com.innova.akis.execution;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;
import tr.com.innova.akis.execution.JdbcPinnedSchemaSnapshotStore.PinnedProcedureTarget;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDatabaseIdentityFingerprintV1;
import tr.com.innova.akis.oracle.OracleDatabaseIdentityFingerprintV1.CanonicalDatabaseIdentity;

/** Read-only proof of the immutable Procedure target, schema and privileges. */
@Service
final class ProcedureTargetPreflightService {

    private final ProcedurePreflightContextPort contexts;
    private final ProcedureRuntimePlanResolver plans;
    private final JdbcPinnedSchemaSnapshotStore snapshots;
    private final RuntimeOracleConnectionProvider connections;
    private final ObjectMapper objectMapper;

    ProcedureTargetPreflightService(
            ProcedurePreflightContextPort contexts,
            ProcedureRuntimePlanResolver plans,
            JdbcPinnedSchemaSnapshotStore snapshots,
            RuntimeOracleConnectionProvider connections,
            ObjectMapper objectMapper) {
        this.contexts = contexts;
        this.plans = plans;
        this.snapshots = snapshots;
        this.connections = connections;
        this.objectMapper = objectMapper;
    }

    Result preflight(UUID projectUuid, UUID publicationUuid) {
        ProcedurePreflightContextPort.Context context;
        try {
            context = contexts.find(projectUuid, publicationUuid)
                    .orElseThrow(() -> failure(HttpStatus.NOT_FOUND,
                            "PROCEDURE_PUBLICATION_NOT_FOUND",
                            "Ön doğrulamaya uygun Procedure yayını bulunamadı."));
        }
        catch (ApiException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROCEDURE_PREFLIGHT_METADATA_UNAVAILABLE",
                    "Procedure yayın metadatası okunamadı.");
        }

        ProcedureRuntimePlan plan;
        try {
            plan = plans.resolve(context.releaseHash(), context.scenarioPlanHash(),
                    context.scenarioPlan(), context.physicalManifest());
        }
        catch (RuntimeException exception) {
            throw failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_PLAN_INTEGRITY_FAILED",
                    "Yayınlanmış Procedure planının bütünlüğü doğrulanamadı.");
        }

        List<ProcedureRuntimePlan.Task> targetWrites = plan.tasks().stream()
                .filter(task -> task.connectionRole() == ProcedureRuntimePlan.ConnectionRole.TARGET)
                .filter(task -> task.riskClass() == ProcedureRuntimePlan.RiskClass.DML)
                .filter(task -> task.input() != null)
                .toList();
        if (targetWrites.size() != 1) {
            throw failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_TARGET_CONTRACT_INVALID",
                    "Procedure tam olarak bir hedef yükleme adımı içermelidir.");
        }
        ProcedureRuntimePlan.Task targetTask = targetWrites.getFirst();
        ProcedureRuntimePlan.TaskBinding binding = plan.bindings().get(targetTask.id());

        PinnedProcedureTarget pinned;
        ProcedurePreflightContextPort.ConnectionEvidence evidence;
        try {
            pinned = snapshots.loadProcedureTarget(plan, targetTask, binding, context.status());
            evidence = contexts.findConnectionEvidence(projectUuid, binding.connectionVersionUuid())
                    .orElseThrow(() -> new IllegalStateException("Connection evidence is missing."));
        }
        catch (RuntimeException exception) {
            throw failure(HttpStatus.CONFLICT,
                    "PROCEDURE_TARGET_SNAPSHOT_UNTRUSTED",
                    "Hedef şema veya bağlantı kanıtı yayın bağlamında doğrulanamadı.");
        }
        if (!Objects.equals(projectUuid, pinned.projectUuid())
                || !Objects.equals(publicationUuid, pinned.publicationUuid())) {
            throw failure(HttpStatus.CONFLICT,
                    "PROCEDURE_TARGET_BINDING_MISMATCH",
                    "Hedef bağı farklı bir proje veya yayına ait.");
        }

        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        RuntimeOracleSession session = null;
        CanonicalTargetIdentity identity;
        JdbcOracleTargetPrivilegeReader.Observation privileges;
        try {
            PilotRuntimePlan.DatasetBinding adapted = ProcedureOracleBindingAdapter.target(binding);
            session = connections.openTargetIdentityRead(adapted);
            if (session.purpose()
                    != RuntimeOracleConnectionProvider.SessionPurpose.TARGET_IDENTITY_READ
                    || !session.connection().isReadOnly()
                    || !session.connection().getAutoCommit()) {
                throw new IllegalStateException("Target identity session is not read-only.");
            }
            PinnedSnapshot snapshot = pinned.snapshot();
            new JdbcOracleSchemaPreflight(objectMapper).verifyTarget(
                    schemaPlan(plan, adapted), session.connection(),
                    new ExpectedSnapshot(snapshot.schemaSnapshotUuid(), snapshot.body()));
            identity = new JdbcOracleTargetIdentityReader().read(
                    session.connection(), binding.owner(), "TABLE", binding.objectName());
            CanonicalDatabaseIdentity databaseIdentity =
                    new OracleDatabaseIdentityFingerprintV1().canonicalize(
                            identity.databaseUniqueName(), identity.containerName());
            if (evidence.identityVersion() != databaseIdentity.identityVersion()
                    || !constantTimeEquals(evidence.targetFingerprint(),
                            databaseIdentity.fingerprint())) {
                throw failure(HttpStatus.CONFLICT,
                        "PROCEDURE_TARGET_IDENTITY_DRIFT",
                        "Canlı Oracle hedef kimliği etkin bağlantı kanıtıyla eşleşmiyor.");
            }
            privileges = new JdbcOracleTargetPrivilegeReader().read(
                    session.connection(), binding.owner());
            if (!privileges.allRequired()) {
                throw failure(HttpStatus.UNPROCESSABLE_CONTENT,
                        "PROCEDURE_TARGET_PRIVILEGES_MISSING",
                        "Hedef kullanıcı gerekli TRUNCATE, INSERT veya DBMS_STATS yetkisine sahip değil.");
            }
        }
        catch (ApiException exception) {
            throw exception;
        }
        catch (RuntimeOracleConnectionException exception) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROCEDURE_TARGET_CONNECTION_UNAVAILABLE",
                    "Hedef Oracle bağlantısı açılamadı.");
        }
        catch (OracleSchemaPreflightException exception) {
            throw schemaFailure(exception);
        }
        catch (OracleTargetIdentityException exception) {
            throw failure(HttpStatus.CONFLICT,
                    "PROCEDURE_TARGET_IDENTITY_UNVERIFIED",
                    "Hedef Oracle nesne kimliği doğrulanamadı.");
        }
        catch (OracleTargetPrivilegeException exception) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROCEDURE_TARGET_PRIVILEGES_UNAVAILABLE",
                    "Hedef Oracle yetki metadatası okunamadı.");
        }
        catch (Exception exception) {
            throw failure(HttpStatus.BAD_GATEWAY,
                    "PROCEDURE_TARGET_PREFLIGHT_FAILED",
                    "Hedef salt-okunur ön doğrulaması tamamlanamadı.");
        }
        finally {
            if (session != null) {
                try {
                    session.close();
                }
                catch (RuntimeException exception) {
                    throw failure(HttpStatus.BAD_GATEWAY,
                            "PROCEDURE_TARGET_SESSION_CLOSE_UNCONFIRMED",
                            "Hedef Oracle oturumunun kapandığı doğrulanamadı.");
                }
            }
        }
        OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return new Result(publicationUuid, context.status(), plan.releaseHash(),
                plan.runtimePlanHash(), targetTask.id(), binding.physicalIdentity(),
                binding.schemaSnapshotUuid(), binding.schemaSnapshotFingerprint(),
                identity.databaseUniqueName(), identity.containerName(),
                identity.canonicalTargetHash(), privileges.currentUser(),
                privileges.ownsTarget(), privileges.canTruncate(), privileges.canInsert(),
                privileges.canExecuteDbmsStats(), startedAt, completedAt,
                Duration.between(startedAt, completedAt).toMillis(), true, false);
    }

    private PilotRuntimePlan schemaPlan(
            ProcedureRuntimePlan plan, PilotRuntimePlan.DatasetBinding target) {
        return new PilotRuntimePlan(PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(), plan.releaseHash(), plan.scenarioPlanHash(),
                plan.definitionUuid(), plan.definitionVersionUuid(), 1, null, target,
                List.of(), PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                plan.canonicalPlan());
    }

    private ApiException schemaFailure(OracleSchemaPreflightException exception) {
        return switch (exception.failure()) {
            case LIVE_SCHEMA_DRIFT -> failure(HttpStatus.CONFLICT,
                    "PROCEDURE_TARGET_SCHEMA_DRIFT",
                    "Canlı hedef şeması yayınlanmış görüntüyle eşleşmiyor.");
            case UNSUPPORTED_SCHEMA -> failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_TARGET_SCHEMA_UNSUPPORTED",
                    "Hedef şeması güvenli Oracle Procedure kapsamı dışında metadata içeriyor.");
            case METADATA_UNAVAILABLE -> failure(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROCEDURE_TARGET_METADATA_UNAVAILABLE",
                    "Canlı hedef şema metadatası okunamadı.");
            case INVALID_CONTRACT, SNAPSHOT_FINGERPRINT_MISMATCH -> failure(HttpStatus.CONFLICT,
                    "PROCEDURE_TARGET_SNAPSHOT_UNTRUSTED",
                    "Hedef şema görüntüsünün bütünlüğü doğrulanamadı.");
        };
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < expected.length(); index++) {
            difference |= expected.charAt(index) ^ actual.charAt(index);
        }
        return difference == 0;
    }

    private ApiException failure(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }

    record Result(
            UUID publicationUuid,
            String publicationStatus,
            String releaseHash,
            String runtimePlanHash,
            String targetTaskId,
            String physicalIdentity,
            UUID schemaSnapshotUuid,
            String schemaSnapshotFingerprint,
            String databaseUniqueName,
            String containerName,
            String targetIdentityHash,
            String currentUser,
            boolean ownsTarget,
            boolean canTruncate,
            boolean canInsert,
            boolean canExecuteDbmsStats,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            long durationMs,
            boolean targetReadOnly,
            boolean sourceSessionOpened) {
    }
}

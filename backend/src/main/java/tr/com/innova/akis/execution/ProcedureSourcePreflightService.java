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
import tr.com.innova.akis.execution.JdbcPinnedSchemaSnapshotStore.PinnedProcedureSource;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.metadata.ApiException;

/**
 * Proves the published Procedure source path without opening a target session.
 * The bounded row payload is retained only in this method and is never returned.
 */
@Service
final class ProcedureSourcePreflightService {

    private final ProcedurePreflightContextPort contexts;
    private final ProcedureRuntimePlanResolver plans;
    private final JdbcPinnedSchemaSnapshotStore snapshots;
    private final RuntimeOracleConnectionProvider connections;
    private final JdbcOracleProcedureSourceReader reader;
    private final ObjectMapper objectMapper;

    ProcedureSourcePreflightService(
            ProcedurePreflightContextPort contexts,
            ProcedureRuntimePlanResolver plans,
            JdbcPinnedSchemaSnapshotStore snapshots,
            RuntimeOracleConnectionProvider connections,
            JdbcOracleProcedureSourceReader reader,
            ObjectMapper objectMapper) {
        this.contexts = contexts;
        this.plans = plans;
        this.snapshots = snapshots;
        this.connections = connections;
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    Result preflight(UUID projectUuid, UUID publicationUuid) {
        ProcedurePreflightContextPort.Context context;
        try {
            context = contexts.find(projectUuid, publicationUuid)
                    .orElseThrow(() -> failure(
                            HttpStatus.NOT_FOUND,
                            "PROCEDURE_PUBLICATION_NOT_FOUND",
                            "Ön doğrulamaya uygun Procedure yayını bulunamadı."));
        }
        catch (ApiException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PROCEDURE_PREFLIGHT_METADATA_UNAVAILABLE",
                    "Procedure yayın metadatası okunamadı.");
        }
        ProcedureRuntimePlan plan;
        try {
            plan = plans.resolve(
                    context.releaseHash(), context.scenarioPlanHash(),
                    context.scenarioPlan(), context.physicalManifest());
        }
        catch (RuntimeException exception) {
            throw failure(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_PLAN_INTEGRITY_FAILED",
                    "Yayınlanmış Procedure planının bütünlüğü doğrulanamadı.");
        }

        List<ProcedureRuntimePlan.Task> sourceTasks = plan.tasks().stream()
                .filter(task -> task.connectionRole()
                        == ProcedureRuntimePlan.ConnectionRole.SOURCE)
                .toList();
        if (sourceTasks.size() != 1) {
            throw failure(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_SOURCE_CONTRACT_INVALID",
                    "Procedure tam olarak bir kaynak okuma adımı içermelidir.");
        }
        ProcedureRuntimePlan.Task sourceTask = sourceTasks.getFirst();
        ProcedureRuntimePlan.TaskBinding binding = plan.bindings().get(sourceTask.id());
        try {
            ProcedureOracleSourceSqlContract.validate(plan, sourceTask, binding);
        }
        catch (RuntimeException exception) {
            throw failure(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_SOURCE_CONTRACT_INVALID",
                    "Procedure kaynak SELECT sözleşmesi doğrulanamadı.");
        }

        PinnedProcedureSource pinned;
        try {
            pinned = snapshots.loadProcedureSource(
                    plan, sourceTask, binding, context.status());
        }
        catch (RuntimeException exception) {
            throw failure(
                    HttpStatus.CONFLICT,
                    "PROCEDURE_SOURCE_SNAPSHOT_UNTRUSTED",
                    "Kaynak şema görüntüsü yayın bağlamında doğrulanamadı.");
        }
        if (!Objects.equals(projectUuid, pinned.projectUuid())
                || !Objects.equals(publicationUuid, pinned.publicationUuid())) {
            throw failure(
                    HttpStatus.CONFLICT,
                    "PROCEDURE_SOURCE_BINDING_MISMATCH",
                    "Kaynak bağı farklı bir proje veya yayına ait.");
        }

        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        OraclePilotBatch batch;
        RuntimeOracleSession session = null;
        try {
            PilotRuntimePlan.DatasetBinding adapted =
                    ProcedureOracleBindingAdapter.source(binding);
            session = connections.openSource(adapted);
            if (session.purpose()
                    != RuntimeOracleConnectionProvider.SessionPurpose.SOURCE_READ
                    || !session.connection().isReadOnly()
                    || !session.connection().getAutoCommit()) {
                throw new IllegalStateException("Source session is not read-only.");
            }
            PinnedSnapshot snapshot = pinned.snapshot();
            PilotRuntimePlan schemaPlan = schemaPlan(plan, sourceTask, adapted);
            new JdbcOracleSchemaPreflight(objectMapper).verifySource(
                    schemaPlan,
                    session.connection(),
                    new ExpectedSnapshot(snapshot.schemaSnapshotUuid(), snapshot.body()));
            batch = reader.read(session, plan, sourceTask, binding);
        }
        catch (RuntimeOracleConnectionException exception) {
            throw failure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PROCEDURE_SOURCE_CONNECTION_UNAVAILABLE",
                    "Kaynak Oracle bağlantısı açılamadı.");
        }
        catch (OracleSchemaPreflightException exception) {
            throw schemaFailure(exception);
        }
        catch (OraclePilotDataException exception) {
            throw dataFailure(exception);
        }
        catch (Exception exception) {
            throw failure(
                    HttpStatus.BAD_GATEWAY,
                    "PROCEDURE_SOURCE_PREFLIGHT_FAILED",
                    "Kaynak salt-okunur ön doğrulaması tamamlanamadı.");
        }
        finally {
            if (session != null) {
                try {
                    session.close();
                }
                catch (RuntimeException exception) {
                    throw failure(
                            HttpStatus.BAD_GATEWAY,
                            "PROCEDURE_SOURCE_SESSION_CLOSE_UNCONFIRMED",
                            "Kaynak Oracle oturumunun kapandığı doğrulanamadı.");
                }
            }
        }
        OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return new Result(
                publicationUuid,
                context.status(),
                plan.releaseHash(),
                plan.runtimePlanHash(),
                sourceTask.id(),
                binding.physicalIdentity(),
                binding.schemaSnapshotUuid(),
                binding.schemaSnapshotFingerprint(),
                sourceTask.output().maximumRows(),
                batch.rows().size(),
                batch.byteCount(),
                batch.payloadHash(),
                batch.columns().stream()
                        .map(column -> new Column(column.sourceColumn(),
                                valueType(column.type())))
                        .toList(),
                startedAt,
                completedAt,
                Duration.between(startedAt, completedAt).toMillis(),
                true,
                false);
    }

    private PilotRuntimePlan schemaPlan(
            ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task sourceTask,
            PilotRuntimePlan.DatasetBinding source) {
        return new PilotRuntimePlan(
                PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(),
                plan.releaseHash(),
                plan.scenarioPlanHash(),
                plan.definitionUuid(),
                plan.definitionVersionUuid(),
                sourceTask.output().maximumRows(),
                source,
                null,
                List.of(),
                PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                plan.canonicalPlan());
    }

    private String valueType(OraclePilotColumnType type) {
        return switch (type) {
            case NUMBER -> "NUMBER";
            case VARCHAR2 -> "STRING";
            case TIMESTAMP -> "TIMESTAMP";
        };
    }

    private ApiException dataFailure(OraclePilotDataException exception) {
        return switch (exception.failure()) {
            case SOURCE_ROW_LIMIT_EXCEEDED -> failure(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_SOURCE_ROW_LIMIT_EXCEEDED",
                    "Kaynak sonucu yayınlanmış satır sınırını aşıyor.");
            case SOURCE_CELL_LIMIT_EXCEEDED, SOURCE_BATCH_LIMIT_EXCEEDED -> failure(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_SOURCE_PAYLOAD_LIMIT_EXCEEDED",
                    "Kaynak sonucu güvenli payload sınırını aşıyor.");
            case UNSUPPORTED_SOURCE_TYPE -> failure(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_SOURCE_TYPE_UNSUPPORTED",
                    "Kaynak sonucu desteklenmeyen bir Oracle veri tipi içeriyor.");
            default -> failure(
                    HttpStatus.BAD_GATEWAY,
                    "PROCEDURE_SOURCE_READ_FAILED",
                    "Kaynak SELECT güvenli biçimde tamamlanamadı.");
        };
    }

    private ApiException schemaFailure(OracleSchemaPreflightException exception) {
        return switch (exception.failure()) {
            case LIVE_SCHEMA_DRIFT -> failure(
                    HttpStatus.CONFLICT,
                    "PROCEDURE_SOURCE_SCHEMA_DRIFT",
                    "Canlı kaynak şeması yayınlanmış görüntüyle eşleşmiyor.");
            case UNSUPPORTED_SCHEMA -> failure(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROCEDURE_SOURCE_SCHEMA_UNSUPPORTED",
                    "Kaynak şeması güvenli Oracle Procedure kapsamı dışında metadata içeriyor.");
            case METADATA_UNAVAILABLE -> failure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PROCEDURE_SOURCE_METADATA_UNAVAILABLE",
                    "Canlı kaynak şema metadatası okunamadı.");
            case INVALID_CONTRACT, SNAPSHOT_FINGERPRINT_MISMATCH -> failure(
                    HttpStatus.CONFLICT,
                    "PROCEDURE_SOURCE_SNAPSHOT_UNTRUSTED",
                    "Kaynak şema görüntüsünün bütünlüğü doğrulanamadı.");
        };
    }

    private ApiException failure(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }

    record Result(
            UUID publicationUuid,
            String publicationStatus,
            String releaseHash,
            String runtimePlanHash,
            String sourceTaskId,
            String physicalIdentity,
            UUID schemaSnapshotUuid,
            String schemaSnapshotFingerprint,
            int maximumRows,
            int observedRowCount,
            long payloadByteCount,
            String payloadHash,
            List<Column> columns,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            long durationMs,
            boolean sourceReadOnly,
            boolean targetSessionOpened) {
    }

    record Column(String name, String type) {
    }
}

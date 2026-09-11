package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.metadata.ApiException;

/** Canonically compares the published source and target without exposing rows. */
@Service
final class ProcedurePilotVerificationService {

    private final ProcedurePreflightContextPort contexts;
    private final ProcedureRuntimePlanResolver plans;
    private final JdbcPinnedSchemaSnapshotStore snapshots;
    private final RuntimeOracleConnectionProvider connections;
    private final JdbcOracleProcedureSourceReader reader;
    private final ObjectMapper objectMapper;

    ProcedurePilotVerificationService(
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

    Result verify(UUID projectUuid, UUID publicationUuid) {
        var context = contexts.find(projectUuid, publicationUuid)
                .orElseThrow(() -> failure("PROCEDURE_PUBLICATION_NOT_FOUND",
                        "Doğrulanacak aktif Procedure yayını bulunamadı."));
        if (!"AKTIF".equals(context.status())) {
            throw failure("PROCEDURE_PUBLICATION_NOT_ACTIVE",
                    "Pilot kabul doğrulaması yalnız aktif yayın için yapılabilir.");
        }
        ProcedureRuntimePlan plan = plans.resolve(context.releaseHash(),
                context.scenarioPlanHash(), context.scenarioPlan(), context.physicalManifest());
        var sourceTask = plan.tasks().stream().filter(task ->
                task.connectionRole() == ProcedureRuntimePlan.ConnectionRole.SOURCE)
                .findFirst().orElseThrow();
        var targetTask = plan.tasks().stream().filter(task ->
                task.connectionRole() == ProcedureRuntimePlan.ConnectionRole.TARGET
                && task.riskClass() == ProcedureRuntimePlan.RiskClass.DML
                && task.input() != null).findFirst().orElseThrow();
        var sourceBinding = plan.bindings().get(sourceTask.id());
        var targetBinding = plan.bindings().get(targetTask.id());
        var sourcePinned = snapshots.loadProcedureSource(
                plan, sourceTask, sourceBinding, context.status());
        var targetPinned = snapshots.loadProcedureTarget(
                plan, targetTask, targetBinding, context.status());
        var validated = ProcedureOracleSourceSqlContract.validate(
                plan, sourceTask, sourceBinding);
        var targetRead = targetReadTask(plan, targetBinding, validated.columns(),
                sourceTask.output().maximumRows());

        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        RuntimeOracleSession sourceSession = null;
        RuntimeOracleSession targetSession = null;
        try {
            var sourceAdapted = ProcedureOracleBindingAdapter.source(sourceBinding);
            var targetAdapted = ProcedureOracleBindingAdapter.target(targetBinding);
            sourceSession = connections.openSource(sourceAdapted);
            targetSession = connections.openTargetIdentityRead(targetAdapted);
            var schema = new JdbcOracleSchemaPreflight(objectMapper);
            schema.verifySource(sourcePlan(plan, sourceTask, sourceAdapted),
                    sourceSession.connection(), new ExpectedSnapshot(
                            sourcePinned.snapshot().schemaSnapshotUuid(),
                            sourcePinned.snapshot().body()));
            schema.verifyTarget(targetPlan(plan, targetAdapted),
                    targetSession.connection(), new ExpectedSnapshot(
                            targetPinned.snapshot().schemaSnapshotUuid(),
                            targetPinned.snapshot().body()));
            OraclePilotBatch source = reader.read(
                    sourceSession, plan, sourceTask, sourceBinding);
            OraclePilotBatch target = reader.read(
                    targetSession, plan, targetRead.task(), targetRead.binding());
            new OraclePilotPayloadCodec().verify(source);
            new OraclePilotPayloadCodec().verify(target);
            boolean matches = source.rows().size() == target.rows().size()
                    && Objects.equals(source.payloadHash(), target.payloadHash());
            OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            return new Result(publicationUuid, source.rows().size(), target.rows().size(),
                    source.payloadHash(), target.payloadHash(), matches, true, true,
                    startedAt, completedAt,
                    Duration.between(startedAt, completedAt).toMillis());
        }
        catch (RuntimeException exception) {
            throw failure("PROCEDURE_PILOT_VERIFICATION_FAILED",
                    "Kaynak ve hedef pilot kabul kanıtı üretilemedi.");
        }
        finally {
            close(targetSession);
            close(sourceSession);
        }
    }

    private TargetRead targetReadTask(
            ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.TaskBinding target,
            List<String> columns,
            int maximumRows) {
        String taskId = "VERIFY_TARGET";
        String sql = "SELECT " + String.join(", ", columns)
                + " FROM " + target.physicalIdentity();
        var task = new ProcedureRuntimePlan.Task(taskId, taskId,
                ProcedureRuntimePlan.TaskType.SQL,
                ProcedureRuntimePlan.ConnectionRole.SOURCE,
                ProcedureRuntimePlan.RiskClass.READ_ONLY,
                sql, sha256(sql), false, ProcedureRuntimePlan.ErrorPolicy.STOP,
                ProcedureRuntimePlan.MAXIMUM_TIMEOUT_SECONDS,
                new ProcedureRuntimePlan.RowsetOutput(maximumRows), null, List.of());
        var binding = new ProcedureRuntimePlan.TaskBinding(taskId,
                ProcedureRuntimePlan.ConnectionRole.SOURCE,
                target.definitionDataObjectUuid(), target.dataObjectUuid(),
                target.environmentSchemaBindingUuid(), target.physicalSchemaUuid(),
                target.connectionVersionUuid(), target.schemaSnapshotUuid(),
                target.bindingVersion(), target.schemaSnapshotFingerprint(),
                target.physicalIdentity(), target.owner(), target.objectName(),
                target.dataObjectType());
        return new TargetRead(task, binding);
    }

    private PilotRuntimePlan sourcePlan(ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task task, PilotRuntimePlan.DatasetBinding source) {
        return new PilotRuntimePlan(PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(), plan.releaseHash(), plan.scenarioPlanHash(),
                plan.definitionUuid(), plan.definitionVersionUuid(),
                task.output().maximumRows(), source, null, List.of(),
                PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT, plan.canonicalPlan());
    }

    private PilotRuntimePlan targetPlan(
            ProcedureRuntimePlan plan, PilotRuntimePlan.DatasetBinding target) {
        return new PilotRuntimePlan(PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(), plan.releaseHash(), plan.scenarioPlanHash(),
                plan.definitionUuid(), plan.definitionVersionUuid(), 1, null, target,
                List.of(), PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                plan.canonicalPlan());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void close(RuntimeOracleSession session) {
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
    }

    private ApiException failure(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    record Result(UUID publicationUuid, int sourceRowCount, int targetRowCount,
            String sourcePayloadHash, String targetPayloadHash, boolean matches,
            boolean sourceReadOnly, boolean targetReadOnly, OffsetDateTime startedAt,
            OffsetDateTime completedAt, long durationMs) { }

    private record TargetRead(
            ProcedureRuntimePlan.Task task,
            ProcedureRuntimePlan.TaskBinding binding) { }
}

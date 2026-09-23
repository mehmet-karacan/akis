package tr.com.innova.akis.execution;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.execution.ExecutionModels.RunEventRow;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.RunStepRow;
import tr.com.innova.akis.execution.ExecutionModels.RunSearch;
import tr.com.innova.akis.execution.ExecutionModels.RunSummaryRow;
import tr.com.innova.akis.execution.ExecutionModels.StartResult;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.RUN_CANCEL;
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;
import static tr.com.innova.akis.security.PermissionCodes.RUN_START;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/runs")
final class ExecutionController {

    private final ExecutionService service;
    private final RunActorResolver actorResolver;
    private final AuthorizationService authorization;
    private final ExecutionFeatureFlags featureFlags;

    ExecutionController(
            ExecutionService service,
            RunActorResolver actorResolver,
            AuthorizationService authorization,
            ExecutionFeatureFlags featureFlags) {
        this.service = service;
        this.actorResolver = actorResolver;
        this.authorization = authorization;
        this.featureFlags = featureFlags;
    }

    @PostMapping
    ResponseEntity<RunView> start(
            @PathVariable UUID projectUuid,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StartRunRequest request) {
        authorization.requireProjectPermission(projectUuid, RUN_START);
        StartResult result = service.start(
                projectUuid, request.publicationUuid(), idempotencyKey,
                actorResolver.currentActor(), request.batchRows());
        RunView view = RunView.from(result.run(), featureFlags);
        if (!result.created()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/runs/" + view.runUuid()))
                .body(view);
    }

    @GetMapping
    List<RunView> list(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        return service.list(projectUuid).stream().map(row -> RunView.from(row, featureFlags)).toList();
    }

    @GetMapping("/search")
    RunPageView search(
            @PathVariable UUID projectUuid,
            @RequestParam(defaultValue = "RECENT") String view,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String statuses,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String definitionType,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Boolean scheduled) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        var result = service.search(projectUuid, new RunSearch(
                view, query, statuses, environment, definitionType, from, to, page, size, scheduled));
        return new RunPageView(
                result.items().stream().map(row -> RunSummaryView.from(row, featureFlags)).toList(),
                result.total(), result.page(), result.size());
    }

    @GetMapping("/{runUuid}")
    RunView get(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        return RunView.from(service.get(projectUuid, runUuid), featureFlags);
    }

    @GetMapping("/{runUuid}/events")
    List<RunEventView> events(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        return service.events(projectUuid, runUuid).stream()
                .map(RunEventView::from)
                .toList();
    }

    @GetMapping("/{runUuid}/events/search")
    RunEventPageView eventPage(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int size) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        var result = service.events(projectUuid, runUuid, after, size);
        return new RunEventPageView(
                result.items().stream().map(RunEventView::from).toList(),
                result.nextCursor(), result.hasMore());
    }

    @GetMapping("/{runUuid}/steps")
    List<RunStepView> steps(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        return service.steps(projectUuid, runUuid).stream()
                .map(RunStepView::from)
                .toList();
    }

    @GetMapping("/{runUuid}/recovery-plan")
    RecoveryPlanView recoveryPlan(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        return RecoveryPlanView.from(service.recoveryPlan(projectUuid, runUuid));
    }

    @GetMapping("/{runUuid}/steps/{stepUuid}/chunks")
    ChunkPageView chunks(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid,
            @PathVariable UUID stepUuid,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String status) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        var page = service.chunks(projectUuid, runUuid, stepUuid, after, size, status);
        return new ChunkPageView(
                page.items().stream().map(ChunkView::from).toList(),
                page.nextCursor() == null ? null : page.nextCursor().toString(),
                page.hasMore());
    }

    @PostMapping("/{runUuid}/recovery")
    ResponseEntity<RunView> recover(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RecoveryRequest request) {
        authorization.requireProjectPermission(projectUuid, RUN_START);
        var result = service.recover(
                projectUuid, runUuid, idempotencyKey, request.action(),
                request.expectedStateVersion(), request.planHash(), actorResolver.currentActor());
        RunView view = RunView.from(result.run(), featureFlags);
        return result.created()
                ? ResponseEntity.created(URI.create(
                        "/api/v1/projects/" + projectUuid + "/runs/" + view.runUuid())).body(view)
                : ResponseEntity.ok(view);
    }

    record ReleaseTargetRequest(@NotNull String reason) { }

    /** Operator action: lift a target quarantine left by a reconciliation conflict. */
    @PostMapping("/{runUuid}/target/release")
    RunView releaseTarget(@PathVariable UUID projectUuid, @PathVariable UUID runUuid, @Valid @RequestBody ReleaseTargetRequest request) {
        authorization.requireProjectPermission(projectUuid, RUN_START);
        return RunView.from(service.releaseTarget(projectUuid, runUuid, request.reason(), actorResolver.currentActor()), featureFlags);
    }

    @PostMapping("/{runUuid}/cancel")
    RunView cancel(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_CANCEL);
        return RunView.from(service.cancel(
                projectUuid, runUuid, actorResolver.currentActor()), featureFlags);
    }

    /** batchRows: optional per-run override of the pinned batch size (1..5000); the published default applies when absent. */
    record StartRunRequest(@NotNull UUID publicationUuid, @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(5000) Integer batchRows) {
        StartRunRequest(UUID publicationUuid) { this(publicationUuid, null); }
    }

    record RecoveryRequest(
            @NotNull String action,
            @NotNull String expectedStateVersion,
            @NotNull String planHash) {
    }

    record ChunkPageView(List<ChunkView> items, String nextCursor, boolean hasMore) { }

    record ChunkView(
            UUID uuid,
            String sequence,
            String partitionCode,
            String lowerExclusive,
            String upperInclusive,
            String lastKey,
            String payloadHash,
            String rowCountExact,
            String byteCountExact,
            String status,
            String targetReceiptReference,
            OffsetDateTime createdAt) {
        static ChunkView from(ExecutionChunkStore.ChunkRow row) {
            return new ChunkView(row.uuid(), Long.toString(row.sequence()),
                    row.partitionCode(), decimal(row.lowerExclusive()),
                    decimal(row.upperInclusive()), decimal(row.lastKey()),
                    row.payloadHash(), Long.toString(row.rowCount()),
                    Long.toString(row.byteCount()), row.status(),
                    row.targetReceiptReference(), row.createdAt());
        }
        private static String decimal(java.math.BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }
    }

    record RunView(
            UUID jobRequestUuid,
            UUID runUuid,
            UUID publicationUuid,
            int attemptNumber,
            String startType,
            String status,
            String releaseHash,
            String planHash,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime cancellationRequestedAt,
            List<ActionAvailability> allowedActions) {

        static RunView from(RunRow row, ExecutionFeatureFlags flags) {
            boolean canCancel = "BEKLIYOR".equals(row.status()) && flags.acceptManualRequests();
            String cancelReason = canCancel ? null
                    : !flags.acceptManualRequests() ? "RUNTIME_DISABLED" : "RUN_NOT_QUEUED";
            return new RunView(
                    row.jobRequestUuid(), row.runUuid(), row.publicationUuid(),
                    row.attemptNumber(), row.startType(), row.status(), row.releaseHash(),
                    row.planHash(),
                    row.createdAt(), row.startedAt(), row.finishedAt(),
                    row.cancellationRequestedAt(),
                    List.of(
                            new ActionAvailability("CANCEL", canCancel, cancelReason),
                            new ActionAvailability("START_NEW_ATTEMPT", false, "NOT_SUPPORTED"),
                            new ActionAvailability("RESUME", false, "NOT_SUPPORTED")));
        }
    }

    record ActionAvailability(String action, boolean allowed, String reasonCode) {
    }

    record RecoveryPlanView(
            int evidenceVersion,
            UUID runUuid,
            String expectedStateVersion,
            String planHash,
            List<String> allowedActions,
            List<String> reasonCodes,
            List<RecoveryUnitView> units,
            boolean reconciliationRequired,
            boolean preservesWorkspace,
            boolean resetsTarget) {

        static RecoveryPlanView from(RecoveryPlan plan) {
            return new RecoveryPlanView(
                    plan.evidenceVersion(), plan.runUuid(),
                    Long.toString(plan.expectedStateVersion()),
                    plan.planHash(), plan.allowedActions().stream().map(Enum::name).sorted().toList(),
                    plan.reasonCodes(), plan.units().stream().map(RecoveryUnitView::from).toList(),
                    plan.reconciliationRequired(), plan.preservesWorkspace(), plan.resetsTarget());
        }
    }

    record RecoveryUnitView(
            String workUnitKey,
            UUID stepUuid,
            String stepCode,
            String kind,
            String decision,
            String transactionOutcome,
            String evidenceReference,
            String reasonCode) {
        static RecoveryUnitView from(RecoveryUnit unit) {
            return new RecoveryUnitView(
                    unit.workUnitKey(), unit.stepUuid(), unit.stepCode(), unit.kind().name(),
                    unit.decision().name(), unit.transactionOutcome().name(),
                    unit.evidenceReference(), unit.reasonCode());
        }
    }

    record RunPageView(List<RunSummaryView> items, long total, int page, int size) {
    }

    record RunSummaryView(
            RunView run,
            UUID definitionUuid,
            String definitionCode,
            String definitionName,
            String definitionType,
            UUID environmentUuid,
            String environmentCode,
            String environmentName,
            String environmentRisk,
            String initiatorName,
            String scheduleCode,
            Long selectedRows,
            Long insertedRows,
            String selectedRowsExact,
            String insertedRowsExact) {
        static RunSummaryView from(RunSummaryRow row, ExecutionFeatureFlags flags) {
            return new RunSummaryView(
                    RunView.from(row.run(), flags), row.definitionUuid(), row.definitionCode(),
                    row.definitionName(), row.definitionType(), row.environmentUuid(),
                    row.environmentCode(), row.environmentName(), row.environmentRisk(),
                    row.initiatorName(), row.scheduleCode(), row.selectedRows(), row.insertedRows(),
                    exact(row.selectedRows()), exact(row.insertedRows()));
        }

        private static String exact(Long value) { return value == null ? null : value.toString(); }
    }

    record RunEventView(
            UUID uuid,
            long eventNumber,
            String type,
            OffsetDateTime eventTime,
            JsonNode data) {

        static RunEventView from(RunEventRow row) {
            return new RunEventView(
                    row.uuid(), row.eventNumber(), row.type(), row.eventTime(), row.data());
        }
    }

    record RunEventPageView(List<RunEventView> items, Long nextCursor, boolean hasMore) {
    }

    record RunStepView(
            UUID uuid,
            UUID parentUuid,
            String code,
            String type,
            int ordinal,
            String name,
            String status,
            String connectionRole,
            String risk,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long rowCount,
            Long byteCount,
            String rowCountExact,
            String byteCountExact,
            String errorCode,
            String logCounter,
            String transactionState,
            UUID childRunUuid) {

        static RunStepView from(RunStepRow row) {
            return new RunStepView(
                    row.uuid(), row.parentUuid(), row.code(), row.type(), row.ordinal(), row.name(),
                    row.status(), row.connectionRole(), row.risk(), row.startedAt(),
                    row.finishedAt(), row.rowCount(), row.byteCount(),
                    row.rowCount() == null ? null : row.rowCount().toString(),
                    row.byteCount() == null ? null : row.byteCount().toString(),
                    row.errorCode(), row.logCounter(), row.transactionState(), row.childRunUuid());
        }
    }
}

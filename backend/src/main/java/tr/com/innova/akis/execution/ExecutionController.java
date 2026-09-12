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
                actorResolver.currentActor());
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
            @RequestParam(defaultValue = "50") int size) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        var result = service.search(projectUuid, new RunSearch(
                view, query, statuses, environment, definitionType, from, to, page, size));
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

    @PostMapping("/{runUuid}/cancel")
    RunView cancel(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_CANCEL);
        return RunView.from(service.cancel(
                projectUuid, runUuid, actorResolver.currentActor()), featureFlags);
    }

    record StartRunRequest(@NotNull UUID publicationUuid) {
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
            String initiatorName) {
        static RunSummaryView from(RunSummaryRow row, ExecutionFeatureFlags flags) {
            return new RunSummaryView(
                    RunView.from(row.run(), flags), row.definitionUuid(), row.definitionCode(),
                    row.definitionName(), row.definitionType(), row.environmentUuid(),
                    row.environmentCode(), row.environmentName(), row.environmentRisk(),
                    row.initiatorName());
        }
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
            String errorCode) {

        static RunStepView from(RunStepRow row) {
            return new RunStepView(
                    row.uuid(), row.parentUuid(), row.code(), row.type(), row.ordinal(), row.name(),
                    row.status(), row.connectionRole(), row.risk(), row.startedAt(),
                    row.finishedAt(), row.rowCount(), row.byteCount(), row.errorCode());
        }
    }
}

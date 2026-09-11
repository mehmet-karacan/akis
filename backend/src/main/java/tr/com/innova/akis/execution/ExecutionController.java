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
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.execution.ExecutionModels.RunEventRow;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
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

    ExecutionController(
            ExecutionService service,
            RunActorResolver actorResolver,
            AuthorizationService authorization) {
        this.service = service;
        this.actorResolver = actorResolver;
        this.authorization = authorization;
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
        RunView view = RunView.from(result.run());
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
        return service.list(projectUuid).stream().map(RunView::from).toList();
    }

    @GetMapping("/{runUuid}")
    RunView get(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_READ);
        return RunView.from(service.get(projectUuid, runUuid));
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

    @PostMapping("/{runUuid}/cancel")
    RunView cancel(
            @PathVariable UUID projectUuid,
            @PathVariable UUID runUuid) {
        authorization.requireProjectPermission(projectUuid, RUN_CANCEL);
        return RunView.from(service.cancel(
                projectUuid, runUuid, actorResolver.currentActor()));
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
            OffsetDateTime cancellationRequestedAt) {

        static RunView from(RunRow row) {
            return new RunView(
                    row.jobRequestUuid(), row.runUuid(), row.publicationUuid(),
                    row.attemptNumber(), row.startType(), row.status(), row.releaseHash(),
                    row.planHash(),
                    row.createdAt(), row.startedAt(), row.finishedAt(),
                    row.cancellationRequestedAt());
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
}

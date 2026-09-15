package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

/** Read-only UI contract; it exposes deployment truth without database schema changes. */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/capabilities")
final class ProjectCapabilityController {

    private final AuthorizationService authorization;
    private final ExecutionFeatureFlags executionFlags;

    ProjectCapabilityController(
            AuthorizationService authorization,
            ExecutionFeatureFlags executionFlags) {
        this.authorization = authorization;
        this.executionFlags = executionFlags;
    }

    @GetMapping
    ProjectCapabilities get(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        boolean acceptsRequests = executionFlags.acceptManualRequests();
        boolean workerAvailable = executionFlags.workerEnabled();
        return new ProjectCapabilities(
                1,
                new ProcedureLimits(
                        ProcedureRuntimePlan.MAXIMUM_TASKS,
                        ProcedureRuntimePlan.MAXIMUM_ROWSET_ROWS,
                        ProcedureRuntimePlan.MAXIMUM_TIMEOUT_SECONDS),
                new RuntimeCapability(
                        acceptsRequests,
                        workerAvailable,
                        acceptsRequests && workerAvailable,
                        !acceptsRequests ? "EXECUTION_REQUESTS_DISABLED"
                                : !workerAvailable ? "EXECUTION_WORKER_DISABLED" : null),
                executionFlags.procedureRuntimeEnabled()
                        ? List.of("ORACLE_TABLE_COPY_V1", "ORACLE_PROCEDURE_V1") : List.of());
    }

    record ProjectCapabilities(
            int contractVersion,
            ProcedureLimits procedure,
            RuntimeCapability runtime,
            List<String> supportedRuntimeCapabilities) {
    }

    record ProcedureLimits(int maximumTasks, int maximumRowsetRows, int maximumTimeoutSeconds) {
    }

    record RuntimeCapability(
            boolean acceptsManualRequests,
            boolean workerAvailable,
            boolean runnable,
            String unavailableReason) {
    }
}

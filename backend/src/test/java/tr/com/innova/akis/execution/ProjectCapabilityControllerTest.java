package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.security.AuthorizationService;

class ProjectCapabilityControllerTest {

    @Test
    void exposesDeploymentTruthAndRequiresProjectReadPermission() {
        UUID projectUuid = UUID.randomUUID();
        CapturingAuthorization authorization = new CapturingAuthorization();
        var controller = new ProjectCapabilityController(
                authorization, new ExecutionFeatureFlags(true, false, false));

        var capabilities = controller.get(projectUuid);

        assertEquals(projectUuid, authorization.projectUuid);
        assertEquals(PROJECT_READ, authorization.permission);
        assertEquals(1, capabilities.contractVersion());
        assertEquals(ProcedureRuntimePlan.MAXIMUM_TASKS,
                capabilities.procedure().maximumTasks());
        assertTrue(capabilities.runtime().acceptsManualRequests());
        assertFalse(capabilities.runtime().workerAvailable());
        assertFalse(capabilities.runtime().runnable());
        assertEquals("EXECUTION_WORKER_DISABLED", capabilities.runtime().unavailableReason());
        assertTrue(capabilities.supportedRuntimeCapabilities().isEmpty());
    }

    @Test
    void reportsRunnableOnlyWhenRequestsAndWorkerAreEnabled() {
        var controller = new ProjectCapabilityController(
                new CapturingAuthorization(), new ExecutionFeatureFlags(true, true, true));

        assertTrue(controller.get(UUID.randomUUID()).runtime().runnable());
    }

    @Test
    void configuredRecoveryFlagsAreNotAdvertisedWithoutInstalledHandlers() {
        var controller = new ProjectCapabilityController(new CapturingAuthorization(),
                new ExecutionFeatureFlags(true, false, true, false, true, true));

        var capabilities = controller.get(UUID.randomUUID()).supportedRuntimeCapabilities();

        assertFalse(capabilities.contains("ORACLE_PROCEDURE_RECOVERY_V1"));
        assertFalse(capabilities.contains("ORACLE_TRANSFER_RECOVERY_V1"));
    }

    private static final class CapturingAuthorization extends AuthorizationService {
        private UUID projectUuid;
        private String permission;

        private CapturingAuthorization() {
            super(null, "fail-closed");
        }

        @Override
        public void requireProjectPermission(UUID projectUuid, String permissionCode) {
            this.projectUuid = projectUuid;
            this.permission = permissionCode;
        }
    }
}

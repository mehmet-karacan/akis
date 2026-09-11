package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class ExecutionApiContractTest {

    @Test
    void apiViewsDoNotExposeInternalNumericIdentifiers() {
        for (Class<?> view : List.of(
                ExecutionController.RunView.class,
                ExecutionController.RunEventView.class,
                ProcedureSourcePreflightService.Result.class,
                ProcedureTargetPreflightService.Result.class,
                ProcedurePilotVerificationService.Result.class)) {
            assertFalse(Arrays.stream(view.getRecordComponents())
                    .anyMatch(component -> component.getName().equalsIgnoreCase("id")
                            || component.getName().endsWith("Id")
                            && (component.getType() == long.class
                                || component.getType() == Long.class)));
        }
    }

    @Test
    void pilotVerificationExposesHashesButNotRows() {
        assertFalse(Arrays.stream(
                        ProcedurePilotVerificationService.Result.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("rows")
                        || component.getName().equals("values")
                        || component.getName().equals("credentials")));
    }

    @Test
    void procedureTargetPreflightReturnsOnlyIdentityAndPrivilegeSummary() {
        assertFalse(Arrays.stream(
                        ProcedureTargetPreflightService.Result.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("rows")
                        || component.getName().equals("values")
                        || component.getName().equals("credentials")
                        || component.getName().equals("password")));
    }

    @Test
    void procedurePreflightReturnsOnlyPayloadSummaryNotSourceRows() {
        assertFalse(Arrays.stream(
                        ProcedureSourcePreflightService.Result.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("rows")
                        || component.getName().equals("values")
                        || component.getName().equals("credentials")));
    }

    @Test
    void jdbcRepositoryRemainsProxyableForExceptionTranslation() {
        assertTrue(Modifier.isPublic(JdbcExecutionStore.class.getModifiers()));
        assertFalse(Modifier.isFinal(JdbcExecutionStore.class.getModifiers()));
    }

    @Test
    void workerFlagRequiresProcedureRuntime() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new ExecutionFeatureFlags(false, true, false));
        assertTrue(error.getMessage().contains("Procedure runtime"));
    }

    @Test
    void runViewNamesReleaseAndScenarioPlanHashesSeparately() {
        assertNotNull(Arrays.stream(ExecutionController.RunView.class.getRecordComponents())
                .filter(component -> component.getName().equals("releaseHash"))
                .findFirst()
                .orElse(null));
        assertNotNull(Arrays.stream(ExecutionController.RunView.class.getRecordComponents())
                .filter(component -> component.getName().equals("planHash"))
                .findFirst()
                .orElse(null));
    }

    @Test
    void controlledWorkerPollerTypeExists() throws Exception {
        assertNotNull(Class.forName("tr.com.innova.akis.execution.WorkerPoller"));
    }
}

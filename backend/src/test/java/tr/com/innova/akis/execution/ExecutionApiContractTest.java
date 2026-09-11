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
                ExecutionController.RunEventView.class)) {
            assertFalse(Arrays.stream(view.getRecordComponents())
                    .anyMatch(component -> component.getName().equalsIgnoreCase("id")
                            || component.getName().endsWith("Id")));
        }
    }

    @Test
    void jdbcRepositoryRemainsProxyableForExceptionTranslation() {
        assertTrue(Modifier.isPublic(JdbcExecutionStore.class.getModifiers()));
        assertFalse(Modifier.isFinal(JdbcExecutionStore.class.getModifiers()));
    }

    @Test
    void workerFlagFailsClosedUntilTargetFencingExists() {
        assertThrows(IllegalStateException.class, () -> new ExecutionFeatureFlags(false, true));
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
    void phaseThreeARegistersNoWorkerPollerType() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("tr.com.innova.akis.execution.WorkerPoller"));
    }
}

package tr.com.innova.akis.scenario;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ScenarioApiContractTest {

    @Test
    void apiViewDoesNotExposeInternalNumericIdentifiers() {
        assertTrue(Arrays.stream(ScenarioController.ScenarioView.class.getRecordComponents())
                .noneMatch(component -> component.getName().equalsIgnoreCase("id")));
    }

    @Test
    void jdbcRepositoryRemainsProxyableForExceptionTranslation() {
        assertTrue(Modifier.isPublic(JdbcScenarioStore.class.getModifiers()));
        assertTrue(!Modifier.isFinal(JdbcScenarioStore.class.getModifiers()));
    }
}

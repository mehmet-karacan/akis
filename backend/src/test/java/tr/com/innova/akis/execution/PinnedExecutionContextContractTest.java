package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;

class PinnedExecutionContextContractTest {

    @Test
    void pinsBothHashesWithoutRawEndpointOrSecretFields() {
        Set<String> names = Arrays.stream(PinnedExecutionContext.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(names.contains("releaseHash"));
        assertTrue(names.contains("planHash"));
        assertTrue(names.contains("scenarioPlan"));
        assertFalse(names.stream().anyMatch(name -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("host") || lower.contains("password")
                    || lower.contains("secret") || lower.contains("targethash");
        }));
    }
}

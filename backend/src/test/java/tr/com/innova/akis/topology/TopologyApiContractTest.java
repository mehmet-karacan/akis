package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TopologyApiContractTest {

    @Test
    void environmentCreationAcceptsOnlyUserFacingIdentityFields() {
        Set<String> fields = Arrays.stream(TopologyController.CreateEnvironmentRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("code", "name"), fields);
    }

    @Test
    void physicalSchemaCreationAcceptsOnlyConnectionAndSchema() {
        Set<String> fields = Arrays.stream(TopologyController.CreatePhysicalSchemaRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("connectionUuid", "schema"), fields);
    }
}

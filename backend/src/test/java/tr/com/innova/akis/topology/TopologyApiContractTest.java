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

    @Test
    void logicalSchemaCreationAcceptsPhysicalMappingWithoutTechnicalConnectionRevision() {
        Set<String> fields = Arrays.stream(TopologyController.CreateLogicalSchemaRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("code", "name", "description", "environmentUuid", "physicalSchemaUuid"), fields);
    }

    @Test
    void schemaMappingAcceptsOnlyTheUserFacingContext() {
        Set<String> createFields = Arrays.stream(TopologyController.CreateSchemaBindingRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        Set<String> updateFields = Arrays.stream(TopologyController.UpdateSchemaBindingRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("logicalSchemaUuid", "environmentUuid", "physicalSchemaUuid"), createFields);
        assertEquals(Set.of("logicalSchemaUuid", "environmentUuid", "physicalSchemaUuid", "expectedVersion"), updateFields);
    }
}

package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TopologyApiContractTest {

    private static Set<String> fields(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void environmentCreationAcceptsOnlyUserFacingIdentityFields() {
        assertEquals(Set.of("code", "name", "description", "risk", "defaultEnvironment", "policy"), fields(TopologyController.CreateEnvironmentRequest.class));
        assertEquals(Set.of("name", "description", "risk", "defaultEnvironment", "status"), fields(TopologyController.UpdateEnvironmentRequest.class));
    }

    @Test
    void connectionRequestCarriesEndpointAndCredentialsWithoutVersioning() {
        Set<String> fields = fields(TopologyController.ConnectionRequest.class);
        assertTrue(fields.containsAll(Set.of("code", "name", "databaseType", "mode", "host", "port", "username", "password")));
        assertFalse(fields.contains("expectedVersion"));
        assertFalse(fields.contains("initialVersion"));
    }

    @Test
    void connectionViewNeverExposesTheStoredPassword() {
        Set<String> fields = fields(TopologyController.ConnectionView.class);
        assertTrue(fields.contains("hasPassword"));
        assertFalse(fields.contains("password"));
    }

    @Test
    void physicalSchemaRequestCarriesWorkSchemaAndPrefixes() {
        Set<String> fields = fields(TopologyController.PhysicalSchemaRequest.class);
        assertTrue(fields.containsAll(Set.of("connectionUuid", "schemaName", "workSchemaName", "defaultSchema",
                "loadingPrefix", "integrationPrefix", "errorPrefix", "tempPrefix")));
    }

    @Test
    void logicalSchemaCreationAcceptsPhysicalMappingWithoutTechnicalConnectionRevision() {
        assertEquals(Set.of("code", "name", "description", "databaseType", "environmentUuid", "physicalSchemaUuid"),
                fields(TopologyController.CreateLogicalSchemaRequest.class));
    }

    @Test
    void schemaMappingAcceptsOnlyTheUserFacingContext() {
        assertEquals(Set.of("logicalSchemaUuid", "environmentUuid", "physicalSchemaUuid"), fields(TopologyController.SchemaBindingRequest.class));
    }
}

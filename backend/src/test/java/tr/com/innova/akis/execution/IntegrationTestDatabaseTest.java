package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IntegrationTestDatabaseTest {

    @Test
    void acceptsOnlyExplicitlyIsolatedDatabaseNames() {
        String integration = "jdbc:postgresql://localhost:5432/akis_metadata_it";
        String testWithOptions = "jdbc:postgresql://localhost:5432/akis_test?sslmode=disable";

        assertEquals(integration, IntegrationTestDatabase.requireIsolatedUrl(integration));
        assertEquals(testWithOptions, IntegrationTestDatabase.requireIsolatedUrl(testWithOptions));
        assertThrows(IllegalStateException.class, () ->
                IntegrationTestDatabase.requireIsolatedUrl(
                        "jdbc:postgresql://localhost:5432/akis_metadata"));
    }
}

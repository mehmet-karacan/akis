package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tr.com.innova.akis.security.PermissionCodes.CATALOG_READ;
import static tr.com.innova.akis.security.PermissionCodes.DEFINITION_READ;
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportModels.ExportScope;

class ExportProviderRegistryTest {

    private final ExportProviderRegistry registry = new ExportProviderRegistry(
            List.of(new DummyProvider(ExportProviderRegistry.DATASET_RUNS,
                    ExportProviderRegistry.RESOURCE_RUN_HISTORY)));

    @Test
    void allowlistedCombinationIsSupported() {
        assertTrue(registry.allowlisted("runs", "run-history"));
        assertTrue(registry.allowlisted("models", "data-objects"));
        assertTrue(registry.allowlisted("definitions", "definitions"));
        assertTrue(registry.allowlisted("topology", "connections"));
        assertTrue(registry.allowlisted("topology", "environments"));
        assertTrue(registry.allowlisted("topology", "logical-schemas"));
        assertTrue(registry.allowlisted("topology", "physical-schemas"));
        assertTrue(registry.allowlisted("execution", "schedules"));
        assertTrue(registry.allowlisted("operations", "publications"));
        assertTrue(registry.allowlisted("identity", "users"));
        assertTrue(registry.allowlisted("schema-metadata", "schema-metadata"));
    }

    @Test
    void unknownProviderOrResourceReturnsFalse() {
        assertFalse(registry.allowlisted("runs", "unknown"));
        assertFalse(registry.allowlisted("unknown", "run-history"));
        assertFalse(registry.allowlisted("models", "run-history"));
        assertFalse(registry.allowlisted("topology", "data-objects"));
        assertFalse(registry.allowlisted("execution", "connections"));
        assertFalse(registry.allowlisted("operations", "schedules"));
    }

    @Test
    void resolveUnknownComboThrows422() {
        ExportException error = assertThrows(ExportException.class,
                () -> registry.resolve("runs", "data-objects"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status());
        assertEquals("EXPORT_PROVIDER_NOT_ALLOWED", error.code());
    }

    @Test
    void validatePassesForAllowlistedProvider() {
        ExportRequest request = new ExportRequest(
                ExportProviderRegistry.DATASET_RUNS,
                ExportProviderRegistry.RESOURCE_RUN_HISTORY,
                ExportScope.ALL, List.of(), List.of(), false, "tr-TR", "UTC");
        registry.validate(request);
    }

    @Test
    void mapsEachProjectExportToThePermissionOfItsSourceScreen() {
        assertEquals(RUN_READ, ExportProviderRegistry.requiredProjectPermission("runs", "run-history"));
        assertEquals(CATALOG_READ, ExportProviderRegistry.requiredProjectPermission("models", "data-objects"));
        assertEquals(DEFINITION_READ, ExportProviderRegistry.requiredProjectPermission("definitions", "definitions"));
    }

    private static final class DummyProvider implements ExportProvider {
        private final String providerId;
        private final String resourceId;

        DummyProvider(String providerId, String resourceId) {
            this.providerId = providerId;
            this.resourceId = resourceId;
        }

        @Override
        public boolean supports(String providerId, String resourceId) {
            return this.providerId.equals(providerId) && this.resourceId.equals(resourceId);
        }

        @Override
        public void validate(ExportRequest request) {
            if (request.scope() == null) {
                throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "EXPORT_SCOPE_REQUIRED", "Scope required");
            }
        }

        @Override
        public void streamRecords(tr.com.innova.akis.export.ExportModels.ExportContext context,
                JsonExportWriter writer) {
        }
    }
}

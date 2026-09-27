package tr.com.innova.akis.export;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.security.PermissionCodes;

/**
 * Resolves provider/resource combinations against an explicit allowlist.
 */
@Component
public class ExportProviderRegistry {

    public static final String DATASET_RUNS = "runs";
    public static final String DATASET_MODELS = "models";
    public static final String DATASET_DEFINITIONS = "definitions";
    public static final String DATASET_TOPOLOGY = "topology";
    public static final String DATASET_EXECUTION = "execution";
    public static final String DATASET_OPERATIONS = "operations";
    public static final String DATASET_IDENTITY = "identity";
    public static final String DATASET_SCHEMA_METADATA = "schema-metadata";

    public static final String RESOURCE_RUN_HISTORY = "run-history";
    public static final String RESOURCE_DATA_OBJECTS = "data-objects";
    public static final String RESOURCE_DEFINITIONS = "definitions";
    public static final String RESOURCE_CONNECTIONS = "connections";
    public static final String RESOURCE_ENVIRONMENTS = "environments";
    public static final String RESOURCE_LOGICAL_SCHEMAS = "logical-schemas";
    public static final String RESOURCE_PHYSICAL_SCHEMAS = "physical-schemas";
    public static final String RESOURCE_SCHEDULES = "schedules";
    public static final String RESOURCE_PUBLICATIONS = "publications";
    public static final String RESOURCE_USERS = "users";
    public static final String RESOURCE_SCHEMA_METADATA = "schema-metadata";

    private static final Map<String, Set<String>> ALLOWLIST = Map.of(
            DATASET_RUNS, Set.of(RESOURCE_RUN_HISTORY),
            DATASET_MODELS, Set.of(RESOURCE_DATA_OBJECTS),
            DATASET_DEFINITIONS, Set.of(RESOURCE_DEFINITIONS),
            DATASET_TOPOLOGY, Set.of(RESOURCE_CONNECTIONS, RESOURCE_ENVIRONMENTS,
                    RESOURCE_LOGICAL_SCHEMAS, RESOURCE_PHYSICAL_SCHEMAS),
            DATASET_EXECUTION, Set.of(RESOURCE_SCHEDULES),
            DATASET_OPERATIONS, Set.of(RESOURCE_PUBLICATIONS),
            DATASET_IDENTITY, Set.of(RESOURCE_USERS),
            DATASET_SCHEMA_METADATA, Set.of(RESOURCE_SCHEMA_METADATA));

    private final List<ExportProvider> providers;

    public ExportProviderRegistry(List<ExportProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public boolean allowlisted(String providerId, String resourceId) {
        return ALLOWLIST.getOrDefault(providerId, Set.of()).contains(resourceId);
    }

    /**
     * Maps an export to the permission of the data being exported. Exporting
     * a catalog or topology record must not accidentally require the run
     * history permission; doing so made otherwise authorized screens fail
     * with a misleading 403.
     */
    public static String requiredProjectPermission(String providerId, String resourceId) {
        if (!ALLOWLIST.getOrDefault(providerId, Set.of()).contains(resourceId)) {
            throw new ExportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_PROVIDER_NOT_ALLOWED",
                    "Bilinmeyen veri aktarım kaynağı: " + providerId + "/" + resourceId);
        }
        return switch (providerId) {
            case DATASET_RUNS -> PermissionCodes.RUN_READ;
            case DATASET_MODELS -> PermissionCodes.CATALOG_READ;
            case DATASET_DEFINITIONS -> PermissionCodes.DEFINITION_READ;
            case DATASET_TOPOLOGY -> PermissionCodes.TOPOLOGY_READ;
            case DATASET_EXECUTION -> PermissionCodes.SCHEDULE_READ;
            case DATASET_OPERATIONS -> PermissionCodes.PUBLICATION_READ;
            default -> throw new ExportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_PROJECT_PROVIDER_NOT_ALLOWED",
                    "Global kapsam dışındaki aktarım kaynağı desteklenmiyor: "
                            + providerId + "/" + resourceId);
        };
    }

    public ExportProvider resolve(String providerId, String resourceId) {
        if (!allowlisted(providerId, resourceId)) {
            throw new ExportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_PROVIDER_NOT_ALLOWED",
                    "Bilinmeyen veri aktarım kaynağı: " + providerId + "/" + resourceId);
        }
        return providers.stream()
                .filter(provider -> provider.supports(providerId, resourceId))
                .findFirst()
                .orElseThrow(() -> new ExportException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "EXPORT_PROVIDER_NOT_AVAILABLE",
                        "Aktarım sağlayıcısı yapılandırılmamış: " + providerId + "/" + resourceId));
    }

    public void validate(ExportRequest request) {
        if (request.providerId() == null || request.providerId().isBlank()
                || request.resourceId() == null || request.resourceId().isBlank()) {
            throw new ExportException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_PROVIDER_REQUIRED",
                    "Sağlayıcı ve kaynak kodu zorunludur.");
        }
        ExportProvider provider = resolve(request.providerId(), request.resourceId());
        provider.validate(request);
    }
}

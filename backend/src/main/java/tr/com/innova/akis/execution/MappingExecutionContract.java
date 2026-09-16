package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;

/** Shared physical-schema proof, not a shared limit/strategy or executable capability. */
public interface MappingExecutionContract {
    UUID definitionUuid();
    UUID definitionVersionUuid();
    String releaseHash();
    String runtimePlanHash();
    String scenarioPlanHash();
    PilotRuntimePlan.DatasetBinding source();
    PilotRuntimePlan.DatasetBinding target();
    List<PilotRuntimePlan.DirectColumnMapping> columnMappings();
}

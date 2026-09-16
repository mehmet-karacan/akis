package tr.com.innova.akis.execution;

import java.util.*;
import tr.com.innova.akis.knowledge.*;
import tools.jackson.databind.JsonNode;

record StagedRuntimePlan(UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid, String releaseHash,
        String runtimePlanHash, String scenarioPlanHash, PilotRuntimePlan.DatasetBinding source,
        PilotRuntimePlan.DatasetBinding target, List<PilotRuntimePlan.DirectColumnMapping> columnMappings,
        StagedMappingDefinition definition, AkisKmInterpreter.Modules modules, AkisKmInterpreter.Plan program,
        JsonNode staging) implements MappingExecutionContract {
    StagedRuntimePlan { columnMappings=List.copyOf(columnMappings); staging=staging.deepCopy(); }
    @Override public JsonNode staging() { return staging.deepCopy(); }
}

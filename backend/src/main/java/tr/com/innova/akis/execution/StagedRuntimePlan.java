package tr.com.innova.akis.execution;

import java.util.*;
import tr.com.innova.akis.knowledge.*;
import tools.jackson.databind.JsonNode;

record StagedRuntimePlan(UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid, String releaseHash,
        String runtimePlanHash, String scenarioPlanHash, PilotRuntimePlan.DatasetBinding source,
        List<PilotRuntimePlan.DatasetBinding> sources, PilotRuntimePlan.DatasetBinding target, List<? extends MappingExecutionContract.ColumnProjection> columnMappings,
        List<String> columnSourceObjects,
        StagedMappingDefinition definition, AkisKmInterpreter.Modules modules, AkisKmInterpreter.Plan program,
        JsonNode staging) implements MappingExecutionContract {
    StagedRuntimePlan { sources=List.copyOf(sources); columnMappings=List.copyOf(columnMappings); columnSourceObjects=List.copyOf(columnSourceObjects); staging=staging.deepCopy(); }
    @Override public JsonNode staging() { return staging.deepCopy(); }
}

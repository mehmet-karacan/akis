package tr.com.innova.akis.scenario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.scenario.ScenarioModels.CompiledPlan;
import tr.com.innova.akis.scenario.ScenarioModels.ScenarioRow;
import tr.com.innova.akis.scenario.ScenarioModels.SourceVersion;

interface ScenarioStore {

    Optional<SourceVersion> findSource(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid);

    void lockSource(long sourceVersionId);

    Optional<ScenarioRow> findByPlanHash(long sourceVersionId, String planHash);

    ScenarioRow create(SourceVersion source, CompiledPlan compiledPlan, UUID scenarioUuid);

    Optional<ScenarioRow> find(
            UUID projectUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            UUID scenarioUuid);

    List<ScenarioRow> list(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid);
}

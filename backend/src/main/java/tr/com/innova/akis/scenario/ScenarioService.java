package tr.com.innova.akis.scenario;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.scenario.ScenarioModels.CompileResult;
import tr.com.innova.akis.scenario.ScenarioModels.CompiledPlan;
import tr.com.innova.akis.scenario.ScenarioModels.ScenarioRow;
import tr.com.innova.akis.scenario.ScenarioModels.SourceVersion;

@Service
public class ScenarioService {

    private final ScenarioStore store;
    private final ScenarioPlanCompiler compiler;

    ScenarioService(ScenarioStore store, ScenarioPlanCompiler compiler) {
        this.store = store;
        this.compiler = compiler;
    }

    @Transactional
    CompileResult compile(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
        SourceVersion source = source(projectUuid, definitionUuid, definitionVersionUuid);
        store.lockSource(source.id());
        CompiledPlan compiledPlan = compiler.compile(source);
        return store.findByPlanHash(source.id(), compiledPlan.planHash())
                .map(existing -> new CompileResult(existing, false))
                .orElseGet(() -> new CompileResult(
                        store.create(source, compiledPlan, UUID.randomUUID()), true));
    }

    List<ScenarioRow> list(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
        source(projectUuid, definitionUuid, definitionVersionUuid);
        return store.list(projectUuid, definitionUuid, definitionVersionUuid);
    }

    ScenarioRow get(
            UUID projectUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            UUID scenarioUuid) {
        source(projectUuid, definitionUuid, definitionVersionUuid);
        return store.find(projectUuid, definitionUuid, definitionVersionUuid, scenarioUuid)
                .orElseThrow(() -> notFound("Scenario bulunamadı."));
    }

    private SourceVersion source(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
        return store.findSource(projectUuid, definitionUuid, definitionVersionUuid)
                .orElseThrow(() -> notFound("Projeye ait tanım sürümü bulunamadı."));
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}

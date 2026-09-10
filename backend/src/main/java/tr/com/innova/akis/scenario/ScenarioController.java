package tr.com.innova.akis.scenario;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.scenario.ScenarioModels.CompileResult;
import tr.com.innova.akis.scenario.ScenarioModels.ScenarioRow;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/definitions/{definitionUuid}/versions/{definitionVersionUuid}/scenarios")
final class ScenarioController {

    private final ScenarioService service;
    private final AuthorizationService authorization;

    ScenarioController(ScenarioService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/compile")
    ResponseEntity<ScenarioView> compile(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @PathVariable UUID definitionVersionUuid) {
        authorization.requireProjectPermission(projectUuid, SCENARIO_COMPILE);
        CompileResult result = service.compile(projectUuid, definitionUuid, definitionVersionUuid);
        ScenarioView view = ScenarioView.from(result.scenario());
        if (!result.created()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid
                        + "/definitions/" + definitionUuid
                        + "/versions/" + definitionVersionUuid
                        + "/scenarios/" + view.uuid()))
                .body(view);
    }

    @GetMapping
    List<ScenarioView> list(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @PathVariable UUID definitionVersionUuid) {
        authorization.requireProjectPermission(projectUuid, SCENARIO_READ);
        return service.list(projectUuid, definitionUuid, definitionVersionUuid).stream()
                .map(ScenarioView::from)
                .toList();
    }

    @GetMapping("/{scenarioUuid}")
    ScenarioView get(
            @PathVariable UUID projectUuid,
            @PathVariable UUID definitionUuid,
            @PathVariable UUID definitionVersionUuid,
            @PathVariable UUID scenarioUuid) {
        authorization.requireProjectPermission(projectUuid, SCENARIO_READ);
        return ScenarioView.from(service.get(
                projectUuid, definitionUuid, definitionVersionUuid, scenarioUuid));
    }

    record ScenarioView(
            UUID uuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            DefinitionType definitionType,
            int scenarioVersion,
            int planVersion,
            String planHash,
            JsonNode plan,
            JsonNode parameterSchema,
            OffsetDateTime createdAt) {

        static ScenarioView from(ScenarioRow row) {
            return new ScenarioView(
                    row.uuid(), row.definitionUuid(), row.definitionVersionUuid(),
                    row.definitionType(), row.scenarioVersion(), row.planVersion(),
                    row.planHash(), row.plan(), row.parameterSchema(), row.createdAt());
        }
    }
}

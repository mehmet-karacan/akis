package tr.com.innova.akis.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.scenario.ScenarioModels.CompileResult;
import tr.com.innova.akis.scenario.ScenarioModels.CompiledPlan;
import tr.com.innova.akis.scenario.ScenarioModels.ScenarioRow;
import tr.com.innova.akis.scenario.ScenarioModels.SourceVersion;

class ScenarioServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID DEFINITION_UUID = UUID.randomUUID();
    private static final UUID VERSION_UUID = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compileIsIdempotentForTheSameCanonicalPlan() throws Exception {
        FakeStore store = new FakeStore(source());
        ScenarioService service = new ScenarioService(
                store, compiler());

        CompileResult first = service.compile(PROJECT_UUID, DEFINITION_UUID, VERSION_UUID);
        CompileResult second = service.compile(PROJECT_UUID, DEFINITION_UUID, VERSION_UUID);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.scenario().uuid(), second.scenario().uuid());
        assertEquals(first.scenario().planHash(), second.scenario().planHash());
        assertEquals(1, store.createCount);
        assertEquals(2, store.lockCount);
    }

    @Test
    void hidesADefinitionVersionOutsideTheRequestedOwnershipPath() throws Exception {
        FakeStore store = new FakeStore(source());
        store.sourceVisible = false;
        ScenarioService service = new ScenarioService(
                store, compiler());

        ApiException error = assertThrows(ApiException.class, () -> service.compile(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals(0, store.lockCount);
    }

    @Test
    void detectsImmutableVersionContentHashMismatchBeforePersistence() throws Exception {
        SourceVersion source = source();
        SourceVersion corrupted = new SourceVersion(
                source.id(), source.projectId(), source.projectUuid(), source.definitionUuid(),
                source.versionUuid(), source.definitionType(), source.definitionVersion(),
                source.schemaVersion(), "0".repeat(64), source.content());
        FakeStore store = new FakeStore(corrupted);
        ScenarioService service = new ScenarioService(
                store, compiler());

        ApiException error = assertThrows(ApiException.class, () -> service.compile(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID));

        assertEquals("DEFINITION_VERSION_INTEGRITY_FAILED", error.code());
        assertEquals(0, store.createCount);
    }

    private SourceVersion source() throws Exception {
        JsonNode content = objectMapper.readTree("""
                {"tasks":[{"id":"read","type":"SQL","connectionRole":"SOURCE",
                 "riskClass":"READ_ONLY","command":"select 1 from dual"}]}
                """);
        ScenarioPlanCompiler compiler = compiler();
        String contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(compiler.canonicalize(content).toString()
                        .getBytes(StandardCharsets.UTF_8)));
        return new SourceVersion(
                11, 12, PROJECT_UUID, DEFINITION_UUID, VERSION_UUID,
                DefinitionType.PROCEDURE, 3, 1, contentHash, content);
    }

    private ScenarioPlanCompiler compiler() {
        return new ScenarioPlanCompiler(
                objectMapper, new DefinitionContentValidator(), new SecretValueSanitizer());
    }

    private static final class FakeStore implements ScenarioStore {

        private final SourceVersion source;
        private boolean sourceVisible = true;
        private int lockCount;
        private int createCount;
        private ScenarioRow scenario;

        private FakeStore(SourceVersion source) {
            this.source = source;
        }

        @Override
        public Optional<SourceVersion> findSource(
                UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
            if (!sourceVisible
                    || !source.projectUuid().equals(projectUuid)
                    || !source.definitionUuid().equals(definitionUuid)
                    || !source.versionUuid().equals(definitionVersionUuid)) {
                return Optional.empty();
            }
            return Optional.of(source);
        }

        @Override
        public void lockSource(long sourceVersionId) {
            lockCount++;
        }

        @Override
        public Optional<ScenarioRow> findByPlanHash(long sourceVersionId, String planHash) {
            return scenario == null || !scenario.planHash().equals(planHash)
                    ? Optional.empty()
                    : Optional.of(scenario);
        }

        @Override
        public ScenarioRow create(
                SourceVersion source, CompiledPlan plan, UUID scenarioUuid) {
            createCount++;
            scenario = new ScenarioRow(
                    91, scenarioUuid, source.definitionUuid(), source.versionUuid(),
                    source.definitionType(), 1, plan.planVersion(), plan.planHash(),
                    plan.plan(), plan.parameterSchema(), OffsetDateTime.now());
            return scenario;
        }

        @Override
        public Optional<ScenarioRow> find(
                UUID projectUuid,
                UUID definitionUuid,
                UUID definitionVersionUuid,
                UUID scenarioUuid) {
            return scenario == null || !scenario.uuid().equals(scenarioUuid)
                    ? Optional.empty()
                    : Optional.of(scenario);
        }

        @Override
        public List<ScenarioRow> list(
                UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
            return scenario == null ? List.of() : List.of(scenario);
        }
    }
}

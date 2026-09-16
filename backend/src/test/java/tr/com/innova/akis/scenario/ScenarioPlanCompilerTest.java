package tr.com.innova.akis.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.scenario.ScenarioModels.CompiledPlan;
import tr.com.innova.akis.scenario.ScenarioModels.SourceVersion;

class ScenarioPlanCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScenarioPlanCompiler compiler = new ScenarioPlanCompiler(
            objectMapper, new DefinitionContentValidator(), new SecretValueSanitizer());

    @Test
    void savedAuthoringMetadataSurvivesCompilationAndMatchesPilotContract() throws Exception {
        JsonNode mapping = json("""
                {"datasets":[{"id":"s","role":"SOURCE","ui":{"name":"Orders","dataObjectUuid":"selection","schemaSnapshotUuid":"hint"}},
                             {"id":"t","role":"TARGET"}],
                 "columnMappings":[{"source":{"dataset":"s","column":"ID"},"target":{"dataset":"t","column":"ID"}}],
                 "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"}}
                """);
        CompiledPlan plan = compiler.compile(source(mapping, DefinitionType.MAPPING, 2));
        var resolver = new tr.com.innova.akis.execution.PilotRuntimePlanResolver(objectMapper, new SecretValueSanitizer());
        org.junit.jupiter.api.Assertions.assertTrue(resolver.isPilotCandidate(plan.plan()));
        assertEquals("selection", plan.plan().path("executable").path("definition").path("datasets").get(0).path("ui").path("dataObjectUuid").asText());
        // Candidate is not publication approval: physical bindings remain mandatory.
    }

    @Test
    void compilesEquivalentObjectsToTheSameCanonicalSha256Plan() throws Exception {
        JsonNode first = json("""
                {"firstStepId":"START","steps":[{"type":"MAPPING","id":"START"}],
                 "transitions":[],"parameterSchema":{"type":"object","z":2,"a":1}}
                """);
        JsonNode reordered = json("""
                {"parameterSchema":{"a":1,"z":2,"type":"object"},"transitions":[],
                 "steps":[{"id":"START","type":"MAPPING"}],"firstStepId":"START"}
                """);

        CompiledPlan firstPlan = compiler.compile(source(first, DefinitionType.PACKAGE));
        CompiledPlan secondPlan = compiler.compile(source(reordered, DefinitionType.PACKAGE));

        assertEquals(firstPlan.planHash(), secondPlan.planHash());
        assertEquals(64, firstPlan.planHash().length());
        assertEquals(firstPlan.parameterSchema(), secondPlan.parameterSchema());
    }

    @Test
    void preservesExecutionArrayOrderInPlanHash() throws Exception {
        JsonNode first = json("""
                {"tasks":[
                  {"id":"one","type":"SQL","connectionRole":"SOURCE","riskClass":"READ_ONLY","command":"select 1 from dual"},
                  {"id":"two","type":"SQL","connectionRole":"SOURCE","riskClass":"READ_ONLY","command":"select 2 from dual"}]}
                """);
        JsonNode reversed = json("""
                {"tasks":[
                  {"id":"two","type":"SQL","connectionRole":"SOURCE","riskClass":"READ_ONLY","command":"select 2 from dual"},
                  {"id":"one","type":"SQL","connectionRole":"SOURCE","riskClass":"READ_ONLY","command":"select 1 from dual"}]}
                """);

        assertNotEquals(
                compiler.compile(source(first, DefinitionType.PROCEDURE)).planHash(),
                compiler.compile(source(reversed, DefinitionType.PROCEDURE)).planHash());
    }

    @Test
    void rejectsSupportDefinitionsThatAreNotStandaloneExecutables() throws Exception {
        JsonNode variable = json("""
                {"dataType":"STRING","scope":"PROJECT","historyMode":"NONE",
                 "valueSource":"INPUT"}
                """);

        ApiException error = assertThrows(
                ApiException.class,
                () -> compiler.compile(source(variable, DefinitionType.VARIABLE)));

        assertEquals("SCENARIO_COMPILE_FAILED", error.code());
    }

    @Test
    void acceptsAtomicDeleteInsertOnlyForMappingSchemaVersionTwo() throws Exception {
        JsonNode mapping = json("""
                {"datasets":[{"id":"s","role":"SOURCE"},{"id":"t","role":"TARGET"}],
                 "columnMappings":[{"source":{"dataset":"s","column":"ID"},
                                    "target":{"dataset":"t","column":"ID"}}],
                 "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"}}
                """);

        CompiledPlan compiled = compiler.compile(source(mapping, DefinitionType.MAPPING, 2));
        ApiException versionOneError = assertThrows(
                ApiException.class,
                () -> compiler.compile(source(mapping, DefinitionType.MAPPING, 1)));

        assertEquals(2, compiled.plan().path("source").path("schemaVersion").intValue());
        assertEquals("VALIDATION_FAILED", versionOneError.code());
    }

    @Test
    void rejectsSecretBearingLegacyContentBeforePlanCompilation() throws Exception {
        JsonNode content = json("""
                {"firstStepId":"START","steps":[{"type":"MAPPING","id":"START"}],
                 "transitions":[],"clientSecret":"should-not-compile"}
                """);

        ApiException error = assertThrows(
                ApiException.class,
                () -> compiler.compile(source(content, DefinitionType.PACKAGE)));

        assertEquals("SCENARIO_COMPILE_FAILED", error.code());
    }

    private SourceVersion source(JsonNode content, DefinitionType type) throws Exception {
        return source(content, type, 1);
    }

    private SourceVersion source(
            JsonNode content, DefinitionType type, int schemaVersion) throws Exception {
        JsonNode canonical = compiler.canonicalize(content);
        String contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        return new SourceVersion(
                1, 2, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                type, 1, schemaVersion, contentHash, content);
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }
}

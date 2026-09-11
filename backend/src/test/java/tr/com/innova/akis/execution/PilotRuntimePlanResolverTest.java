package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

class PilotRuntimePlanResolverTest {

    private static final UUID DEFINITION_UUID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DEFINITION_VERSION_UUID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PilotRuntimePlanResolver resolver = new PilotRuntimePlanResolver(
            objectMapper, new SecretValueSanitizer());

    @Test
    void verifiesFullHashChainAndResolvesBoundedTypedPlan() {
        Inputs inputs = inputs(mapping(false));
        PilotRuntimePlan plan = resolve(inputs);

        assertEquals(64, plan.runtimePlanHash().length());
        assertEquals(inputs.releaseHash(), plan.releaseHash());
        assertEquals(inputs.scenarioPlanHash(), plan.scenarioPlanHash());
        assertEquals(PilotRuntimePlan.MAXIMUM_SOURCE_ROWS, plan.maximumSourceRows());
        assertEquals("TTBP.HAKEDIS_TIPI", plan.source().physicalIdentity());
        assertEquals("INNOVA_ODI.STG_HAKEDIS_TIPI", plan.target().physicalIdentity());
        assertEquals(List.of("ID", "AD"), plan.columnMappings().stream()
                .map(PilotRuntimePlan.DirectColumnMapping::targetColumn).toList());
        assertEquals(PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT, plan.writeStrategy());
        assertFalse(plan.canonicalPlan().toString().contains("releaseHash"));
        assertFalse(plan.canonicalPlan().toString().contains("password"));
        assertFalse(plan.canonicalPlan().toString().contains("jdbc:"));
    }

    @Test
    void jsonObjectFieldOrderDoesNotChangeAnyHash() {
        Inputs first = inputs(mapping(false));
        Inputs reordered = inputs(mapping(true));

        assertEquals(first.scenarioPlanHash(), reordered.scenarioPlanHash());
        assertEquals(first.releaseHash(), reordered.releaseHash());
        assertEquals(resolve(first).runtimePlanHash(), resolve(reordered).runtimePlanHash());
    }

    @Test
    void preservesExplicitColumnOrderAsExecutionSemantics() {
        Inputs original = inputs(mapping(false));
        ObjectNode reversedDefinition = mapping(false).deepCopy();
        ArrayNode mappings = (ArrayNode) reversedDefinition.get("columnMappings");
        JsonNode first = mappings.get(0).deepCopy();
        JsonNode second = mappings.get(1).deepCopy();
        mappings.removeAll();
        mappings.add(second);
        mappings.add(first);
        Inputs reversed = inputs(reversedDefinition);

        assertNotEquals(original.scenarioPlanHash(), reversed.scenarioPlanHash());
        assertNotEquals(resolve(original).runtimePlanHash(), resolve(reversed).runtimePlanHash());
    }

    @Test
    void rejectsTamperedScenarioPlanBeforeParsingIt() {
        Inputs inputs = inputs(mapping(false));
        ObjectNode tampered = inputs.scenarioPlan().deepCopy();
        ((ObjectNode) tampered.path("executable").path("definition")
                .path("writeStrategy")).put("kind", "APPEND");

        PilotRuntimePlanException error = assertThrows(
                PilotRuntimePlanException.class,
                () -> resolver.resolve(inputs.releaseHash(), inputs.scenarioPlanHash(),
                        tampered, inputs.manifest()));
        assertEquals(PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED, error.failure());
    }

    @Test
    void rejectsDefinitionWhoseContentHashWasForgedInsideRehashedPlan() {
        Inputs inputs = inputs(mapping(false));
        ObjectNode forgedPlan = inputs.scenarioPlan().deepCopy();
        ((ObjectNode) forgedPlan.get("source")).put("contentHash", "f".repeat(64));
        String forgedPlanHash = sha256(canonicalize(forgedPlan).toString());
        ObjectNode forgedManifest = inputs.manifest().deepCopy();
        ((ObjectNode) forgedManifest.get("scenario")).put("planHash", forgedPlanHash);
        ((ObjectNode) forgedManifest.get("definition")).put("contentHash", "f".repeat(64));
        resign(forgedManifest);

        PilotRuntimePlanException error = assertThrows(
                PilotRuntimePlanException.class,
                () -> resolver.resolve(forgedManifest.path("releaseHash").stringValue(),
                        forgedPlanHash, forgedPlan, forgedManifest));
        assertEquals(PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED, error.failure());
    }

    @Test
    void rejectsUnknownCompilerPlanFieldsAndUnsupportedExecutableType() {
        Inputs inputs = inputs(mapping(false));
        ObjectNode unknown = inputs.scenarioPlan().deepCopy();
        unknown.put("runtimeOverride", "unsafe");
        Rebound unknownRebound = rebound(unknown, inputs.manifest());
        assertEquals(PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(unknownRebound.releaseHash(),
                                unknownRebound.planHash(), unknown,
                                unknownRebound.manifest())).failure());

        ObjectNode procedure = inputs.scenarioPlan().deepCopy();
        ((ObjectNode) procedure.get("executable")).put("kind", "PROCEDURE");
        ((ObjectNode) procedure.get("source")).put("definitionType", "PROCEDURE");
        Rebound procedureRebound = rebound(procedure, inputs.manifest());
        assertEquals(PilotPlanFailure.UNSUPPORTED_DEFINITION_TYPE,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(procedureRebound.releaseHash(),
                                procedureRebound.planHash(), procedure,
                                procedureRebound.manifest())).failure());
    }

    @Test
    void rejectsCompilerVersionAndMappingSchemaVersionOne() {
        Inputs inputs = inputs(mapping(false));
        ObjectNode futureCompiler = inputs.scenarioPlan().deepCopy();
        futureCompiler.put("compilerVersion", 3);
        Rebound compilerRebound = rebound(futureCompiler, inputs.manifest());
        assertEquals(PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(compilerRebound.releaseHash(),
                                compilerRebound.planHash(), futureCompiler,
                                compilerRebound.manifest())).failure());

        ObjectNode schemaOne = inputs.scenarioPlan().deepCopy();
        ((ObjectNode) schemaOne.get("source")).put("schemaVersion", 1);
        Rebound schemaRebound = rebound(schemaOne, inputs.manifest());
        assertEquals(PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(schemaRebound.releaseHash(),
                                schemaRebound.planHash(), schemaOne,
                                schemaRebound.manifest())).failure());
    }

    @Test
    void rejectsEveryNonAtomicWriteStrategy() {
        for (String strategy : List.of("APPEND", "STAGED_REPLACE", "MERGE", "TRUNCATE_LOAD")) {
            ObjectNode definition = mapping(false).deepCopy();
            ((ObjectNode) definition.get("writeStrategy")).put("kind", strategy);
            assertEquals(PilotPlanFailure.UNSUPPORTED_WRITE_STRATEGY,
                    assertThrows(PilotRuntimePlanException.class,
                            () -> resolve(inputs(definition))).failure());
        }
    }

    @Test
    void rejectsExpressionsAdditionalDatasetsAndUnknownSemantics() {
        ObjectNode expression = mapping(false).deepCopy();
        ObjectNode firstMapping = (ObjectNode) expression.get("columnMappings").get(0);
        firstMapping.remove("source");
        firstMapping.set("expression", objectMapper.createObjectNode().put("kind", "LITERAL"));
        assertShapeFailure(expression);

        ObjectNode extraDataset = mapping(false).deepCopy();
        ((ArrayNode) extraDataset.get("datasets")).add(objectMapper.createObjectNode()
                .put("id", "SOURCE_2").put("role", "SOURCE"));
        assertShapeFailure(extraDataset);

        ObjectNode unknown = mapping(false).deepCopy();
        unknown.set("filter", objectMapper.createObjectNode().put("sql", "1=1"));
        assertShapeFailure(unknown);
    }

    @Test
    void rejectsManifestDefinitionMismatchAndNonOracleTableBindings() {
        Inputs inputs = inputs(mapping(false));
        ObjectNode oldManifest = inputs.manifest().deepCopy();
        oldManifest.put("manifestVersion", 1);
        oldManifest.remove("runtimePlanHash");
        resign(oldManifest);
        assertEquals(PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(oldManifest.path("releaseHash").stringValue(),
                                inputs.scenarioPlanHash(), inputs.scenarioPlan(),
                                oldManifest)).failure());

        ObjectNode unknownManifest = inputs.manifest().deepCopy();
        unknownManifest.put("runtimeOverride", "unsafe");
        resign(unknownManifest);
        assertEquals(PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(
                                unknownManifest.path("releaseHash").stringValue(),
                                inputs.scenarioPlanHash(), inputs.scenarioPlan(),
                                unknownManifest)).failure());

        ObjectNode secretManifest = inputs.manifest().deepCopy();
        ((ObjectNode) secretManifest.path("environment").path("policy"))
                .put("password", "legacy-value");
        resign(secretManifest);
        assertEquals(PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(
                                secretManifest.path("releaseHash").stringValue(),
                                inputs.scenarioPlanHash(), inputs.scenarioPlan(),
                                secretManifest)).failure());

        ObjectNode mismatched = inputs.manifest().deepCopy();
        ((ObjectNode) mismatched.get("definition")).put("contentHash", "e".repeat(64));
        resign(mismatched);
        assertEquals(PilotPlanFailure.DEFINITION_BINDING_MISMATCH,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(mismatched.path("releaseHash").stringValue(),
                                inputs.scenarioPlanHash(), inputs.scenarioPlan(),
                                mismatched)).failure());

        ObjectNode republishedTamper = inputs.manifest().deepCopy();
        ((ObjectNode) republishedTamper.get("bindings").get(0))
                .put("schemaSnapshotFingerprint", "d".repeat(64));
        resign(republishedTamper);
        assertEquals(PilotPlanFailure.RUNTIME_PLAN_INTEGRITY_FAILED,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(
                                republishedTamper.path("releaseHash").stringValue(),
                                inputs.scenarioPlanHash(), inputs.scenarioPlan(),
                                republishedTamper)).failure());

        ObjectNode wrongDatabase = inputs.manifest().deepCopy();
        ((ObjectNode) wrongDatabase.get("bindings").get(0)).put("databaseType", "POSTGRESQL");
        resign(wrongDatabase);
        assertManifestShapeFailure(inputs, wrongDatabase);

        ObjectNode view = inputs.manifest().deepCopy();
        ((ObjectNode) view.get("bindings").get(1)).put("dataObjectType", "VIEW");
        resign(view);
        assertManifestShapeFailure(inputs, view);

        for (int invalidVersion : List.of(0, -1)) {
            ObjectNode invalidBindingVersion = inputs.manifest().deepCopy();
            ((ObjectNode) invalidBindingVersion.get("bindings").get(0))
                    .put("bindingVersion", invalidVersion);
            resign(invalidBindingVersion);
            assertEquals(PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                    assertThrows(PilotRuntimePlanException.class,
                            () -> resolver.resolve(
                                    invalidBindingVersion.path("releaseHash").stringValue(),
                                    inputs.scenarioPlanHash(), inputs.scenarioPlan(),
                                    invalidBindingVersion)).failure());
        }
    }

    private void assertShapeFailure(ObjectNode definition) {
        assertEquals(PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolve(inputs(definition))).failure());
    }

    private void assertManifestShapeFailure(Inputs inputs, ObjectNode manifest) {
        assertEquals(PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE,
                assertThrows(PilotRuntimePlanException.class,
                        () -> resolver.resolve(manifest.path("releaseHash").stringValue(),
                                inputs.scenarioPlanHash(), inputs.scenarioPlan(),
                                manifest)).failure());
    }

    private PilotRuntimePlan resolve(Inputs inputs) {
        return resolver.resolve(inputs.releaseHash(), inputs.scenarioPlanHash(),
                inputs.scenarioPlan(), inputs.manifest());
    }

    private Inputs inputs(ObjectNode definition) {
        String contentHash = sha256(canonicalize(definition).toString());
        ObjectNode plan = scenarioPlan(definition, contentHash);
        String planHash = sha256(canonicalize(plan).toString());
        ObjectNode manifest = signedManifest(planHash, contentHash);
        String runtimePlanHash = resolver.compileHashForPublication(
                planHash, plan, manifest);
        manifest.put("runtimePlanHash", runtimePlanHash);
        resign(manifest);
        return new Inputs(plan, planHash, manifest,
                manifest.path("releaseHash").stringValue());
    }

    private ObjectNode scenarioPlan(ObjectNode definition, String contentHash) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("contentHash", contentHash);
        source.put("definitionType", "MAPPING");
        source.put("definitionUuid", DEFINITION_UUID.toString());
        source.put("definitionVersion", 1);
        source.put("definitionVersionUuid", DEFINITION_VERSION_UUID.toString());
        source.put("schemaVersion", 2);
        ObjectNode executable = objectMapper.createObjectNode();
        executable.set("definition", definition.deepCopy());
        executable.put("kind", "MAPPING");
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("compiler", "AKIS");
        plan.put("compilerVersion", 2);
        plan.set("executable", executable);
        plan.set("source", source);
        return plan;
    }

    private ObjectNode mapping(boolean reordered) {
        String json = reordered
                ? """
                  {"writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"},
                   "columnMappings":[
                     {"target":{"column":"ID","dataset":"TARGET_1"},"source":{"column":"ID","dataset":"SOURCE_1"}},
                     {"target":{"column":"AD","dataset":"TARGET_1"},"source":{"column":"AD","dataset":"SOURCE_1"}}],
                   "datasets":[{"role":"SOURCE","id":"SOURCE_1"},{"role":"TARGET","id":"TARGET_1"}]}
                  """
                : """
                  {"datasets":[{"id":"SOURCE_1","role":"SOURCE"},{"id":"TARGET_1","role":"TARGET"}],
                   "columnMappings":[
                     {"source":{"dataset":"SOURCE_1","column":"ID"},"target":{"dataset":"TARGET_1","column":"ID"}},
                     {"source":{"dataset":"SOURCE_1","column":"AD"},"target":{"dataset":"TARGET_1","column":"AD"}}],
                   "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"}}
                  """;
        return (ObjectNode) objectMapper.readTree(json);
    }

    private ObjectNode signedManifest(String scenarioPlanHash, String contentHash) {
        ObjectNode manifest = objectMapper.createObjectNode();
        ArrayNode bindings = objectMapper.createArrayNode();
        bindings.add(binding("SOURCE_1", "KAYNAK", "TTBP", "HAKEDIS_TIPI",
                "20000000-0000-0000-0000-000000000001", "b".repeat(64)));
        bindings.add(binding("TARGET_1", "HEDEF", "INNOVA_ODI", "STG_HAKEDIS_TIPI",
                "20000000-0000-0000-0000-000000000002", "c".repeat(64)));
        manifest.set("bindings", bindings);
        manifest.set("definition", objectMapper.createObjectNode()
                .put("contentHash", contentHash)
                .put("definitionUuid", DEFINITION_UUID.toString())
                .put("definitionVersionUuid", DEFINITION_VERSION_UUID.toString())
                .put("schemaVersion", 2));
        manifest.set("environment", objectMapper.createObjectNode()
                .put("environmentUuid", "30000000-0000-0000-0000-000000000001")
                .put("code", "TEST").put("risk", "DUSUK").put("policyVersion", 1)
                .set("policy", objectMapper.createObjectNode()
                        .put("approvalCount", 1)));
        manifest.put("manifestVersion", 2);
        manifest.put("runtimeCapability", PilotRuntimePlanResolver.PILOT_CAPABILITY);
        manifest.set("scenario", objectMapper.createObjectNode()
                .put("scenarioUuid", "40000000-0000-0000-0000-000000000001")
                .put("planHash", scenarioPlanHash));
        return manifest;
    }

    private Rebound rebound(ObjectNode plan, ObjectNode originalManifest) {
        String planHash = sha256(canonicalize(plan).toString());
        ObjectNode manifest = originalManifest.deepCopy();
        ((ObjectNode) manifest.get("scenario")).put("planHash", planHash);
        resign(manifest);
        return new Rebound(planHash, manifest.path("releaseHash").stringValue(), manifest);
    }

    private ObjectNode binding(String nodeCode, String role, String owner,
            String objectName, String suffixSeed, String fingerprint) {
        return objectMapper.createObjectNode()
                .put("bindingVersion", 2)
                .put("connectionVersionUuid", suffixSeed)
                .put("databaseType", "ORACLE")
                .put("dataObjectReference", objectName)
                .put("dataObjectType", "TABLO")
                .put("dataObjectUuid", named(suffixSeed + "data"))
                .put("definitionDataObjectUuid", named(suffixSeed + "definition"))
                .put("environmentSchemaBindingUuid", named(suffixSeed + "environment"))
                .put("nodeCode", nodeCode)
                .put("physicalIdentity", owner + "." + objectName)
                .put("physicalSchemaReference", owner)
                .put("physicalSchemaUuid", named(suffixSeed + "schema"))
                .put("role", role)
                .put("schemaSnapshotFingerprint", fingerprint)
                .put("schemaSnapshotUuid", named(suffixSeed + "snapshot"));
    }

    private String named(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void resign(ObjectNode manifest) {
        manifest.remove("releaseHash");
        manifest.put("releaseHash", sha256(canonicalize(manifest).toString()));
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        return node.deepCopy();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record Inputs(ObjectNode scenarioPlan, String scenarioPlanHash,
            ObjectNode manifest, String releaseHash) {
    }

    private record Rebound(String planHash, String releaseHash, ObjectNode manifest) {
    }
}

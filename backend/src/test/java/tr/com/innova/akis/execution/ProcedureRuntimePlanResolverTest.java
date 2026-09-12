package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

class ProcedureRuntimePlanResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProcedureRuntimePlanResolver resolver = new ProcedureRuntimePlanResolver(
            objectMapper, new SecretValueSanitizer(), new DefinitionContentValidator());

    @Test
    void resolvesPinnedProcedureWithoutChangingTaskOrder() {
        Fixture fixture = fixture();

        ProcedureRuntimePlan plan = resolver.resolve(
                fixture.releaseHash(), fixture.scenarioHash(),
                fixture.scenarioPlan(), fixture.signedManifest());

        assertEquals(
                List.of("TRUNCATE_TARGET", "READ_SOURCE", "INSERT_TARGET", "GATHER_STATS"),
                plan.tasks().stream().map(ProcedureRuntimePlan.Task::id).toList());
        assertEquals(List.of("ID", "ACIKLAMA"), plan.tasks().get(2).namedBinds());
        assertEquals(ProcedureRuntimePlan.LogCounter.INSERT, plan.tasks().get(2).logCounter());
        assertEquals(ProcedureRuntimePlan.TransactionMode.TRANSACTION,
                plan.tasks().get(2).transactionMode());
        assertEquals(0, plan.tasks().get(2).transactionChannel());
        assertEquals(ProcedureRuntimePlan.TransactionIsolation.READ_COMMITTED,
                plan.tasks().get(2).transactionIsolation());
        assertFalse(plan.canonicalPlan().get("tasks").get(3).has("logCounter"));
        assertEquals(4, plan.bindings().size());
        assertEquals(fixture.runtimeHash(), plan.runtimePlanHash());
        assertFalse(plan.canonicalPlan().toString().contains("TRUNCATE TABLE"));
        assertFalse(plan.canonicalPlan().toString().contains("password"));
    }

    @Test
    void rejectsMissingExtraDuplicateAndWrongRoleBindings() {
        Fixture missing = fixture();
        ((ArrayNode) missing.unsignedManifest().get("bindings")).remove(3);
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.compileHashForPublication(
                missing.scenarioHash(), missing.scenarioPlan(), missing.unsignedManifest()));

        Fixture duplicate = fixture();
        ArrayNode duplicateBindings = (ArrayNode) duplicate.unsignedManifest().get("bindings");
        duplicateBindings.set(3, duplicateBindings.get(0).deepCopy());
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.compileHashForPublication(
                duplicate.scenarioHash(), duplicate.scenarioPlan(), duplicate.unsignedManifest()));

        Fixture wrongRole = fixture();
        ((ObjectNode) wrongRole.unsignedManifest().get("bindings").get(1)).put("role", "HEDEF");
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.compileHashForPublication(
                wrongRole.scenarioHash(), wrongRole.scenarioPlan(), wrongRole.unsignedManifest()));
    }

    @Test
    void rejectsMultipleTargetConnectionsOrObjects() {
        Fixture connections = fixture();
        ((ObjectNode) connections.unsignedManifest().get("bindings").get(2))
                .put("connectionVersionUuid", UUID.randomUUID().toString());
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.compileHashForPublication(
                connections.scenarioHash(), connections.scenarioPlan(), connections.unsignedManifest()));

        Fixture objects = fixture();
        ObjectNode binding = (ObjectNode) objects.unsignedManifest().get("bindings").get(2);
        binding.put("dataObjectReference", "OTHER_TABLE");
        binding.put("physicalIdentity", "INNOVA_ODI.OTHER_TABLE");
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.compileHashForPublication(
                objects.scenarioHash(), objects.scenarioPlan(), objects.unsignedManifest()));

        Fixture snapshot = fixture();
        ((ObjectNode) snapshot.unsignedManifest().get("bindings").get(2))
                .put("schemaSnapshotUuid", UUID.randomUUID().toString());
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.compileHashForPublication(
                snapshot.scenarioHash(), snapshot.scenarioPlan(), snapshot.unsignedManifest()));
    }

    @Test
    void keepsOversizedProcedureDefinitionOnly() {
        ObjectNode definition = definition();
        ((ObjectNode) definition.get("tasks").get(1).get("output")).put("maxRows", 1001);
        ObjectNode scenario = scenario(definition);

        assertFalse(resolver.isProcedureCandidate(scenario));
    }

    @Test
    void rejectsExecutableSourcePlsqlAndRiskMismatches() {
        ObjectNode sourcePlsql = definition();
        ObjectNode source = (ObjectNode) sourcePlsql.get("tasks").get(1);
        source.put("type", "PLSQL");
        source.put("riskClass", "DESTRUCTIVE");
        source.put("requiresApproval", true);
        source.put("command", "BEGIN DELETE FROM TTBP.HAKEDIS_TIPI; END;");
        assertFalse(resolver.isProcedureCandidate(scenario(sourcePlsql)));

        ObjectNode disguisedTruncate = definition();
        ObjectNode target = (ObjectNode) disguisedTruncate.get("tasks").get(0);
        target.put("riskClass", "READ_ONLY");
        target.put("requiresApproval", false);
        assertFalse(resolver.isProcedureCandidate(scenario(disguisedTruncate)));

        ObjectNode multipleStatements = definition();
        ((ObjectNode) multipleStatements.get("tasks").get(1))
                .put("command", "SELECT ID FROM TTBP.HAKEDIS_TIPI; DELETE FROM TTBP.HAKEDIS_TIPI");
        assertFalse(resolver.isProcedureCandidate(scenario(multipleStatements)));
    }

    @Test
    void rejectsCommandsThatEscapeTheirBoundObjectsOrNeedUnresolvedValues() {
        ObjectNode wrongSource = definition();
        ((ObjectNode) wrongSource.get("tasks").get(1))
                .put("command", "SELECT ID, ACIKLAMA FROM TTBP.OTHER_TABLE");
        assertRejectedAtPublication(wrongSource);

        ObjectNode sourceBind = definition();
        ((ObjectNode) sourceBind.get("tasks").get(1))
                .put("command", "SELECT ID, ACIKLAMA FROM TTBP.HAKEDIS_TIPI WHERE ID = :ID");
        assertRejectedAtPublication(sourceBind);

        ObjectNode wrongTarget = definition();
        ((ObjectNode) wrongTarget.get("tasks").get(2)).put(
                "command", "INSERT INTO INNOVA_ODI.OTHER_TABLE (ID, ACIKLAMA) VALUES (:ID, :ACIKLAMA)");
        assertRejectedAtPublication(wrongTarget);

        ObjectNode wrongStatsTarget = definition();
        ((ObjectNode) wrongStatsTarget.get("tasks").get(3)).put(
                "command", "BEGIN DBMS_STATS.GATHER_TABLE_STATS(ownname => 'INNOVA_ODI', tabname => 'OTHER_TABLE'); END;");
        assertRejectedAtPublication(wrongStatsTarget);
    }

    @Test
    void rejectsSecretBearingManifestBeforeHashing() {
        Fixture fixture = fixture();
        ((ObjectNode) fixture.unsignedManifest().get("environment").get("policy"))
                .put("password", "must-not-leak");

        ProcedureRuntimePlanException error = assertThrows(
                ProcedureRuntimePlanException.class,
                () -> resolver.compileHashForPublication(
                        fixture.scenarioHash(), fixture.scenarioPlan(),
                        fixture.unsignedManifest()));

        assertFalse(error.getMessage().contains("must-not-leak"));
    }

    @Test
    void rejectsTamperedScenarioAndReleaseHashes() {
        Fixture fixture = fixture();
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.resolve(
                "0".repeat(64), fixture.scenarioHash(),
                fixture.scenarioPlan(), fixture.signedManifest()));
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.resolve(
                fixture.releaseHash(), "0".repeat(64),
                fixture.scenarioPlan(), fixture.signedManifest()));
    }

    private Fixture fixture() {
        return fixture(definition());
    }

    private Fixture fixture(ObjectNode definition) {
        ObjectNode scenario = scenario(definition);
        String scenarioHash = sha256(canonicalize(scenario).toString());
        ObjectNode unsigned = manifest(definition, scenario, scenarioHash);
        String runtimeHash = resolver.compileHashForPublication(
                scenarioHash, scenario, unsigned);
        ObjectNode releaseCore = unsigned.deepCopy();
        releaseCore.put("runtimePlanHash", runtimeHash);
        String releaseHash = sha256(canonicalize(releaseCore).toString());
        ObjectNode signed = releaseCore.deepCopy();
        signed.put("releaseHash", releaseHash);
        return new Fixture(scenario, scenarioHash, unsigned, signed, runtimeHash, releaseHash);
    }

    private void assertRejectedAtPublication(ObjectNode definition) {
        ObjectNode scenario = scenario(definition);
        String scenarioHash = sha256(canonicalize(scenario).toString());
        ObjectNode manifest = manifest(definition, scenario, scenarioHash);
        assertThrows(ProcedureRuntimePlanException.class, () -> resolver.compileHashForPublication(
                scenarioHash, scenario, manifest));
    }

    private ObjectNode definition() {
        return (ObjectNode) objectMapper.readTree("""
                {"tasks":[
                  {"id":"TRUNCATE_TARGET","name":"Clear","type":"SQL",
                   "connectionRole":"TARGET","riskClass":"DESTRUCTIVE",
                   "logCounter":"NONE",
                   "requiresApproval":true,"onError":"STOP","timeoutSeconds":60,
                   "command":"TRUNCATE TABLE INNOVA_ODI.STG_HAKEDIS_TIPI"},
                  {"id":"READ_SOURCE","name":"Read","type":"SQL",
                   "connectionRole":"SOURCE","riskClass":"READ_ONLY",
                   "logCounter":"ANALYSIS",
                   "onError":"STOP","timeoutSeconds":300,
                   "command":"SELECT ID, ACIKLAMA FROM TTBP.HAKEDIS_TIPI",
                   "output":{"kind":"ROWSET","maxRows":1000}},
                  {"id":"INSERT_TARGET","name":"Write","type":"SQL",
                   "connectionRole":"TARGET","riskClass":"DML",
                   "logCounter":"INSERT","transactionMode":"TRANSACTION",
                   "transactionChannel":0,"transactionIsolation":"READ_COMMITTED",
                   "commitMode":"COMMIT",
                   "onError":"STOP","timeoutSeconds":300,
                   "command":"INSERT INTO INNOVA_ODI.STG_HAKEDIS_TIPI (ID, ACIKLAMA) VALUES (:ID, :ACIKLAMA)",
                   "input":{"fromTask":"READ_SOURCE","mode":"BATCH","batchSize":250}},
                   {"id":"GATHER_STATS","name":"Stats","type":"PLSQL",
                    "connectionRole":"TARGET","riskClass":"DESTRUCTIVE",
                   "logCounter":"STATISTICS",
                   "requiresApproval":true,"onError":"STOP","timeoutSeconds":300,
                   "command":"BEGIN DBMS_STATS.GATHER_TABLE_STATS('INNOVA_ODI','STG_HAKEDIS_TIPI'); END;"}
                ]}
                """);
    }

    private ObjectNode scenario(ObjectNode definition) {
        String contentHash = sha256(canonicalize(definition).toString());
        ObjectNode source = objectMapper.createObjectNode();
        source.put("contentHash", contentHash);
        source.put("definitionType", "PROCEDURE");
        source.put("definitionUuid", UUID.randomUUID().toString());
        source.put("definitionVersion", 1);
        source.put("definitionVersionUuid", UUID.randomUUID().toString());
        source.put("schemaVersion", 2);
        ObjectNode executable = objectMapper.createObjectNode();
        executable.put("kind", "PROCEDURE");
        executable.set("definition", definition.deepCopy());
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("compiler", "AKIS");
        plan.put("compilerVersion", 2);
        plan.set("executable", executable);
        plan.set("source", source);
        return plan;
    }

    private ObjectNode manifest(
            ObjectNode definition, ObjectNode scenario, String scenarioHash) {
        ObjectNode source = (ObjectNode) scenario.get("source");
        ObjectNode manifestDefinition = objectMapper.createObjectNode();
        manifestDefinition.put("contentHash", source.get("contentHash").stringValue());
        manifestDefinition.put("definitionUuid", source.get("definitionUuid").stringValue());
        manifestDefinition.put(
                "definitionVersionUuid", source.get("definitionVersionUuid").stringValue());
        manifestDefinition.put("schemaVersion", 2);
        ObjectNode manifestScenario = objectMapper.createObjectNode();
        manifestScenario.put("planHash", scenarioHash);
        manifestScenario.put("scenarioUuid", UUID.randomUUID().toString());
        ObjectNode environment = objectMapper.createObjectNode();
        environment.put("code", "DEV");
        environment.put("environmentUuid", UUID.randomUUID().toString());
        environment.set("policy", objectMapper.createObjectNode());
        environment.put("policyVersion", 1);
        environment.put("risk", "DUSUK");

        UUID sourceConnection = UUID.randomUUID();
        UUID targetConnection = UUID.randomUUID();
        ArrayNode bindings = objectMapper.createArrayNode();
        bindings.add(binding("TRUNCATE_TARGET", "HEDEF", targetConnection,
                "INNOVA_ODI", "STG_HAKEDIS_TIPI", "TABLO"));
        bindings.add(binding("READ_SOURCE", "KAYNAK", sourceConnection,
                "TTBP", "HAKEDIS_TIPI", "TABLO"));
        ObjectNode target = (ObjectNode) bindings.get(0);
        bindings.add(copyBinding(target, "INSERT_TARGET"));
        bindings.add(copyBinding(target, "GATHER_STATS"));

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.set("bindings", bindings);
        manifest.put("approvalRequired", true);
        manifest.set("definition", manifestDefinition);
        manifest.set("environment", environment);
        manifest.put("manifestVersion", 2);
        manifest.put("runtimeCapability", ProcedureRuntimePlanResolver.CAPABILITY);
        manifest.set("scenario", manifestScenario);
        return manifest;
    }

    private ObjectNode binding(
            String taskId, String role, UUID connectionUuid,
            String owner, String objectName, String objectType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("bindingVersion", 1);
        node.put("connectionVersionUuid", connectionUuid.toString());
        node.put("databaseType", "ORACLE");
        node.put("dataObjectReference", objectName);
        node.put("dataObjectType", objectType);
        node.put("dataObjectUuid", UUID.randomUUID().toString());
        node.put("definitionDataObjectUuid", UUID.randomUUID().toString());
        node.put("environmentSchemaBindingUuid", UUID.randomUUID().toString());
        node.put("nodeCode", taskId);
        node.put("physicalIdentity", owner + "." + objectName);
        node.put("physicalSchemaReference", owner);
        node.put("physicalSchemaUuid", UUID.randomUUID().toString());
        node.put("role", role);
        node.put("schemaSnapshotFingerprint", "b".repeat(64));
        node.put("schemaSnapshotUuid", UUID.randomUUID().toString());
        return node;
    }

    private ObjectNode copyBinding(ObjectNode source, String taskId) {
        ObjectNode copy = source.deepCopy();
        copy.put("definitionDataObjectUuid", UUID.randomUUID().toString());
        copy.put("nodeCode", taskId);
        return copy;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record Fixture(
            ObjectNode scenarioPlan,
            String scenarioHash,
            ObjectNode unsignedManifest,
            ObjectNode signedManifest,
            String runtimeHash,
            String releaseHash) {
    }
}

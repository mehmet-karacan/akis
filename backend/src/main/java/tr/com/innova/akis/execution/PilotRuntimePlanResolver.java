package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

/**
 * Resolves only the first bounded Oracle table-copy pilot. Every unsupported
 * semantic is rejected before a worker can open a data transaction.
 */
@Component
public final class PilotRuntimePlanResolver {

    public static final String PILOT_CAPABILITY = "ORACLE_TABLE_COPY_V1";
    public static final String DEFINITION_ONLY_CAPABILITY = "DEFINITION_ONLY";

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ORACLE_IDENTIFIER =
            Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

    private final ObjectMapper objectMapper;
    private final SecretValueSanitizer secretSanitizer;

    public PilotRuntimePlanResolver(
            ObjectMapper objectMapper, SecretValueSanitizer secretSanitizer) {
        this.objectMapper = objectMapper;
        this.secretSanitizer = secretSanitizer;
    }

    public boolean isPilotCandidate(JsonNode scenarioPlan) {
        try {
            ObjectNode plan = requireObject(
                    scenarioPlan,
                    PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "Scenario plan");
            ScenarioSource scenario = scenarioSource(plan);
            mappingShape(scenario.definition());
            return true;
        }
        catch (PilotRuntimePlanException exception) {
            return false;
        }
    }

    public PilotRuntimePlan resolve(
            String expectedReleaseHash,
            String expectedScenarioPlanHash,
            JsonNode scenarioPlan,
            JsonNode physicalManifest) {
        requireHash(expectedReleaseHash, "Expected release hash");
        requireHash(expectedScenarioPlanHash, "Expected scenario plan hash");
        ObjectNode verifiedScenarioPlan = requireObject(
                scenarioPlan, PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "Scenario plan");
        if (!expectedScenarioPlanHash.equals(
                sha256(canonicalize(verifiedScenarioPlan).toString()))) {
            throw failure(
                    PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "The scenario plan content does not match its pinned hash.");
        }
        ScenarioSource scenario = scenarioSource(verifiedScenarioPlan);
        ObjectNode manifest = requireObject(
                physicalManifest, PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                "Physical manifest");
        rejectSensitiveManifest(manifest);
        requireManifestIntegrity(manifest, expectedReleaseHash, expectedScenarioPlanHash);

        PilotRuntimePlan compiled = compileVerified(
                scenario, expectedScenarioPlanHash, manifest, expectedReleaseHash);
        String publishedRuntimePlanHash = requireText(manifest, "runtimePlanHash");
        requireHash(publishedRuntimePlanHash, "Runtime plan hash");
        if (!publishedRuntimePlanHash.equals(compiled.runtimePlanHash())) {
            throw failure(
                    PilotPlanFailure.RUNTIME_PLAN_INTEGRITY_FAILED,
                    "The runtime plan does not match the hash pinned by the release.");
        }
        return compiled;
    }

    public String compileHashForPublication(
            String expectedScenarioPlanHash,
            JsonNode scenarioPlan,
            JsonNode unsignedManifestCore) {
        requireHash(expectedScenarioPlanHash, "Expected scenario plan hash");
        ObjectNode verifiedScenarioPlan = requireObject(
                scenarioPlan, PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "Scenario plan");
        if (!expectedScenarioPlanHash.equals(
                sha256(canonicalize(verifiedScenarioPlan).toString()))) {
            throw failure(
                    PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "The scenario plan content does not match its pinned hash.");
        }
        ScenarioSource scenario = scenarioSource(verifiedScenarioPlan);
        ObjectNode manifest = requireObject(
                unsignedManifestCore, PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                "Unsigned physical manifest core");
        rejectSensitiveManifest(manifest);
        requireManifestCore(manifest, expectedScenarioPlanHash, false);
        return compileVerified(
                scenario, expectedScenarioPlanHash, manifest, null).runtimePlanHash();
    }

    private PilotRuntimePlan compileVerified(
            ScenarioSource scenario,
            String expectedScenarioPlanHash,
            ObjectNode manifest,
            String releaseHash) {

        ObjectNode manifestDefinition = requireObject(
                manifest.get("definition"), PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                "manifest.definition");
        requireOnlyFields(
                manifestDefinition,
                Set.of("contentHash", "definitionUuid", "definitionVersionUuid", "schemaVersion"),
                "manifest definition", PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
        UUID definitionUuid = requireUuid(manifestDefinition, "definitionUuid");
        UUID definitionVersionUuid = requireUuid(manifestDefinition, "definitionVersionUuid");
        int manifestSchemaVersion = requireInteger(manifestDefinition, "schemaVersion");
        String manifestContentHash = requireText(manifestDefinition, "contentHash");
        requireHash(manifestContentHash, "Definition content hash");
        if (!definitionUuid.equals(scenario.definitionUuid())
                || !definitionVersionUuid.equals(scenario.definitionVersionUuid())
                || manifestSchemaVersion != scenario.schemaVersion()
                || !manifestContentHash.equals(scenario.contentHash())) {
            throw failure(
                    PilotPlanFailure.DEFINITION_BINDING_MISMATCH,
                    "The scenario definition does not match the published manifest.");
        }

        ObjectNode definition = scenario.definition();
        MappingShape shape = mappingShape(definition);
        Map<String, DatasetBinding> bindings = manifestBindings(manifest, shape.rolesByDataset());
        DatasetBinding source = bindings.get(shape.sourceDatasetId());
        DatasetBinding target = bindings.get(shape.targetDatasetId());

        ObjectNode canonicalPlan = objectMapper.createObjectNode();
        canonicalPlan.set("columnMappings", columnMappingsNode(shape.columnMappings()));
        canonicalPlan.set("definition", definitionNode(scenario));
        canonicalPlan.set("limits", limitsNode());
        canonicalPlan.put("planVersion", PilotRuntimePlan.CURRENT_VERSION);
        canonicalPlan.set("release", releaseNode(manifest, expectedScenarioPlanHash));
        canonicalPlan.set("source", bindingNode(source));
        canonicalPlan.set("target", bindingNode(target));
        canonicalPlan.set("writeStrategy", writeStrategyNode());
        JsonNode canonical = canonicalize(canonicalPlan);
        String runtimePlanHash = sha256(canonical.toString());

        return new PilotRuntimePlan(
                PilotRuntimePlan.CURRENT_VERSION,
                runtimePlanHash,
                releaseHash,
                expectedScenarioPlanHash,
                definitionUuid,
                definitionVersionUuid,
                PilotRuntimePlan.MAXIMUM_SOURCE_ROWS,
                source,
                target,
                shape.columnMappings(),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                canonical);
    }

    private ScenarioSource scenarioSource(ObjectNode plan) {
        requireOnlyFields(
                plan, Set.of("compiler", "compilerVersion", "executable", "source"),
                "scenario plan", PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (!"AKIS".equals(requireText(
                plan, "compiler", PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED))
                || requireInteger(
                        plan, "compilerVersion",
                        PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED) != 2) {
            throw failure(
                    PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "The pilot supports only AKIS scenario compiler version 2.");
        }
        ObjectNode executable = requireObject(
                plan.get("executable"), PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "scenarioPlan.executable");
        requireOnlyFields(
                executable, Set.of("kind", "definition"), "scenario executable",
                PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (!"MAPPING".equals(requireText(
                executable, "kind", PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED))) {
            throw failure(
                    PilotPlanFailure.UNSUPPORTED_DEFINITION_TYPE,
                    "The pilot executes only a standalone MAPPING scenario.");
        }
        ObjectNode source = requireObject(
                plan.get("source"), PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "scenarioPlan.source");
        requireOnlyFields(
                source,
                Set.of("contentHash", "definitionType", "definitionUuid",
                        "definitionVersion", "definitionVersionUuid", "schemaVersion"),
                "scenario source", PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (!"MAPPING".equals(requireText(
                source, "definitionType",
                PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED))) {
            throw failure(
                    PilotPlanFailure.UNSUPPORTED_DEFINITION_TYPE,
                    "The scenario source must be a MAPPING definition.");
        }
        int schemaVersion = requireInteger(
                source, "schemaVersion", PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (schemaVersion != 2) {
            throw shape("ATOMIC_DELETE_INSERT requires mapping schema version 2.");
        }
        int definitionVersion = requireInteger(
                source, "definitionVersion",
                PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (definitionVersion <= 0) {
            throw failure(
                    PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "Scenario definitionVersion must be positive.");
        }
        String contentHash = requireText(
                source, "contentHash", PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        requireHash(
                contentHash, "Scenario definition content hash",
                PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        ObjectNode definition = requireObject(
                executable.get("definition"), PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "scenarioPlan.executable.definition");
        if (!contentHash.equals(sha256(canonicalize(definition).toString()))) {
            throw failure(
                    PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "The executable definition does not match source.contentHash.");
        }
        return new ScenarioSource(
                requireUuid(
                        source, "definitionUuid",
                        PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED),
                requireUuid(
                        source, "definitionVersionUuid",
                        PilotPlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED),
                definitionVersion,
                schemaVersion,
                contentHash,
                definition);
    }

    private void requireManifestIntegrity(
            ObjectNode manifest,
            String expectedReleaseHash,
            String expectedScenarioPlanHash) {
        String embeddedReleaseHash = requireText(manifest, "releaseHash");
        if (!expectedReleaseHash.equals(embeddedReleaseHash)) {
            throw failure(
                    PilotPlanFailure.RELEASE_INTEGRITY_FAILED,
                    "The requested release hash does not match the physical manifest.");
        }
        ObjectNode unsignedManifest = manifest.deepCopy();
        unsignedManifest.remove("releaseHash");
        if (!expectedReleaseHash.equals(sha256(canonicalize(unsignedManifest).toString()))) {
            throw failure(
                    PilotPlanFailure.RELEASE_INTEGRITY_FAILED,
                    "The physical manifest content does not match its release hash.");
        }
        requireManifestCore(manifest, expectedScenarioPlanHash, true);
    }

    private void requireManifestCore(
            ObjectNode manifest,
            String expectedScenarioPlanHash,
            boolean signed) {
        if (requireInteger(manifest, "manifestVersion") != 2) {
            throw failure(
                    PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "The pilot requires physical manifest version 2.");
        }
        Set<String> allowedFields = new HashSet<>(Set.of(
                "bindings", "definition", "environment", "manifestVersion",
                "runtimeCapability", "scenario"));
        if (signed) {
            allowedFields.add("releaseHash");
            allowedFields.add("runtimePlanHash");
        }
        requireOnlyFields(
                manifest,
                allowedFields,
                "physical manifest", PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
        if (!PILOT_CAPABILITY.equals(requireText(manifest, "runtimeCapability"))) {
            throw failure(
                    PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "The release is not executable by the Oracle table-copy pilot.");
        }
        ObjectNode scenario = requireObject(
                manifest.get("scenario"), PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                "manifest.scenario");
        requireOnlyFields(
                scenario, Set.of("planHash", "scenarioUuid"),
                "manifest scenario", PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
        String manifestPlanHash = requireText(scenario, "planHash");
        if (!expectedScenarioPlanHash.equals(manifestPlanHash)) {
            throw failure(
                    PilotPlanFailure.RELEASE_PLAN_MISMATCH,
                    "The release is not bound to the requested scenario plan.");
        }
    }

    private MappingShape mappingShape(ObjectNode definition) {
        requireOnlyFields(
                definition,
                Set.of("datasets", "columnMappings", "writeStrategy", "ui", "layout", "editor"),
                "mapping definition");
        JsonNode datasets = definition.get("datasets");
        if (datasets == null || !datasets.isArray() || datasets.size() != 2) {
            throw shape("The pilot requires exactly one SOURCE and one TARGET dataset.");
        }
        Map<String, DatasetRole> roles = new HashMap<>();
        String sourceId = null;
        String targetId = null;
        for (JsonNode datasetNode : datasets) {
            ObjectNode dataset = requireObject(
                    datasetNode, PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE, "dataset");
            requireOnlyFields(dataset, Set.of("id", "role", "ui", "layout"), "dataset");
            String id = requireText(dataset, "id");
            DatasetRole role = parseDatasetRole(requireText(dataset, "role"));
            if (roles.putIfAbsent(id, role) != null) {
                throw shape("Dataset identifiers must be unique.");
            }
            if (role == DatasetRole.SOURCE) {
                if (sourceId != null) {
                    throw shape("The pilot accepts exactly one SOURCE dataset.");
                }
                sourceId = id;
            }
            else {
                if (targetId != null) {
                    throw shape("The pilot accepts exactly one TARGET dataset.");
                }
                targetId = id;
            }
        }
        if (sourceId == null || targetId == null) {
            throw shape("The pilot requires exactly one SOURCE and one TARGET dataset.");
        }

        ObjectNode strategy = requireObject(
                definition.get("writeStrategy"),
                PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE, "writeStrategy");
        requireOnlyFields(strategy, Set.of("kind"), "writeStrategy");
        String strategyKind = requireText(strategy, "kind");
        if (!WriteStrategy.ATOMIC_DELETE_INSERT.name().equals(strategyKind)) {
            throw failure(
                    PilotPlanFailure.UNSUPPORTED_WRITE_STRATEGY,
                    "The pilot requires ATOMIC_DELETE_INSERT; received " + strategyKind + ".");
        }

        JsonNode mappings = definition.get("columnMappings");
        if (mappings == null || !mappings.isArray() || mappings.isEmpty()) {
            throw shape("The pilot requires at least one direct column mapping.");
        }
        List<DirectColumnMapping> columns = new ArrayList<>();
        Set<String> targetColumns = new HashSet<>();
        for (JsonNode mappingNode : mappings) {
            ObjectNode mapping = requireObject(
                    mappingNode, PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE,
                    "columnMapping");
            requireOnlyFields(
                    mapping, Set.of("source", "target", "expression", "ui", "layout"),
                    "columnMapping");
            if (mapping.has("expression") && !mapping.get("expression").isNull()) {
                throw shape("Expressions are not supported by the pilot.");
            }
            ObjectNode source = requireObject(
                    mapping.get("source"), PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE,
                    "columnMapping.source");
            ObjectNode target = requireObject(
                    mapping.get("target"), PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE,
                    "columnMapping.target");
            requireOnlyFields(source, Set.of("dataset", "column"), "columnMapping.source");
            requireOnlyFields(target, Set.of("dataset", "column"), "columnMapping.target");
            if (!sourceId.equals(requireText(source, "dataset"))
                    || !targetId.equals(requireText(target, "dataset"))) {
                throw shape("Column mappings must connect the single SOURCE to the single TARGET.");
            }
            String sourceColumn = requireOracleIdentifier(source, "column");
            String targetColumn = requireOracleIdentifier(target, "column");
            if (!targetColumns.add(targetColumn)) {
                throw shape("A target column may be written only once: " + targetColumn);
            }
            columns.add(new DirectColumnMapping(sourceColumn, targetColumn));
        }
        return new MappingShape(sourceId, targetId, Map.copyOf(roles), List.copyOf(columns));
    }

    private Map<String, DatasetBinding> manifestBindings(
            ObjectNode manifest, Map<String, DatasetRole> rolesByDataset) {
        JsonNode bindingsNode = manifest.get("bindings");
        if (bindingsNode == null || !bindingsNode.isArray()
                || bindingsNode.size() != rolesByDataset.size()) {
            throw failure(
                    PilotPlanFailure.DEFINITION_BINDING_MISMATCH,
                    "The manifest must contain exactly one binding per pilot dataset.");
        }
        Map<String, DatasetBinding> bindings = new HashMap<>();
        for (JsonNode bindingValue : bindingsNode) {
            ObjectNode binding = requireObject(
                    bindingValue, PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "manifest.binding");
            requireOnlyFields(
                    binding,
                    Set.of("bindingVersion", "connectionVersionUuid", "databaseType",
                            "dataObjectReference", "dataObjectType", "dataObjectUuid",
                            "definitionDataObjectUuid", "environmentSchemaBindingUuid",
                            "nodeCode", "physicalIdentity", "physicalSchemaReference",
                            "physicalSchemaUuid", "role", "schemaSnapshotFingerprint",
                            "schemaSnapshotUuid"),
                    "manifest binding", PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
            String nodeCode = requireText(binding, "nodeCode");
            DatasetRole expectedRole = rolesByDataset.get(nodeCode);
            if (expectedRole == null || bindings.containsKey(nodeCode)) {
                throw failure(
                        PilotPlanFailure.DEFINITION_BINDING_MISMATCH,
                        "Manifest bindings do not match the mapping dataset identifiers.");
            }
            String publishedRole = requireText(binding, "role");
            String expectedPublishedRole = expectedRole == DatasetRole.SOURCE ? "KAYNAK" : "HEDEF";
            if (!expectedPublishedRole.equals(publishedRole)) {
                throw failure(
                        PilotPlanFailure.DEFINITION_BINDING_MISMATCH,
                        "Manifest binding role does not match dataset " + nodeCode + ".");
            }
            String physicalIdentity = requireText(binding, "physicalIdentity");
            String[] identity = physicalIdentity.split("\\.", -1);
            if (identity.length != 2
                    || !ORACLE_IDENTIFIER.matcher(identity[0]).matches()
                    || !ORACLE_IDENTIFIER.matcher(identity[1]).matches()) {
                throw failure(
                        PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE,
                        "Pilot physical identities must be unquoted uppercase OWNER.TABLE names.");
            }
            if (!identity[0].equals(requireText(binding, "physicalSchemaReference"))
                    || !identity[1].equals(requireText(binding, "dataObjectReference"))) {
                throw failure(
                        PilotPlanFailure.RELEASE_INTEGRITY_FAILED,
                        "Manifest physical identity components are inconsistent.");
            }
            String fingerprint = requireText(binding, "schemaSnapshotFingerprint");
            requireHash(fingerprint, "Schema snapshot fingerprint");
            if (!"ORACLE".equals(requireText(binding, "databaseType"))) {
                throw shape("Both pilot datasets must resolve to Oracle connections.");
            }
            if (!"TABLO".equals(requireText(binding, "dataObjectType"))) {
                throw shape("Both pilot datasets must resolve to catalog TABLE objects.");
            }
            long bindingVersion = requireLong(binding, "bindingVersion");
            if (bindingVersion <= 0) {
                throw failure(
                        PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                        "Binding version must be positive.");
            }
            DatasetBinding resolved = new DatasetBinding(
                    nodeCode,
                    expectedRole,
                    DatabaseType.ORACLE,
                    DataObjectType.TABLE,
                    requireUuid(binding, "definitionDataObjectUuid"),
                    requireUuid(binding, "dataObjectUuid"),
                    requireUuid(binding, "environmentSchemaBindingUuid"),
                    requireUuid(binding, "physicalSchemaUuid"),
                    requireUuid(binding, "connectionVersionUuid"),
                    requireUuid(binding, "schemaSnapshotUuid"),
                    bindingVersion,
                    fingerprint,
                    physicalIdentity,
                    identity[0],
                    identity[1]);
            bindings.put(nodeCode, resolved);
        }
        return Map.copyOf(bindings);
    }

    private ObjectNode definitionNode(ScenarioSource mapping) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("contentHash", mapping.contentHash());
        node.put("definitionType", "MAPPING");
        node.put("definitionUuid", mapping.definitionUuid().toString());
        node.put("definitionVersion", mapping.definitionVersion());
        node.put("definitionVersionUuid", mapping.definitionVersionUuid().toString());
        node.put("schemaVersion", mapping.schemaVersion());
        return node;
    }

    private ObjectNode limitsNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("maximumSourceRows", PilotRuntimePlan.MAXIMUM_SOURCE_ROWS);
        return node;
    }

    private ObjectNode releaseNode(ObjectNode manifest, String scenarioPlanHash) {
        ObjectNode environment = requireObject(
                manifest.get("environment"), PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                "manifest.environment");
        requireOnlyFields(
                environment,
                Set.of("code", "environmentUuid", "policy", "policyVersion", "risk"),
                "manifest environment", PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("environmentUuid", requireUuid(environment, "environmentUuid").toString());
        node.put("scenarioPlanHash", scenarioPlanHash);
        return node;
    }

    private ArrayNode columnMappingsNode(List<DirectColumnMapping> mappings) {
        ArrayNode nodes = objectMapper.createArrayNode();
        for (DirectColumnMapping mapping : mappings) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("sourceColumn", mapping.sourceColumn());
            node.put("targetColumn", mapping.targetColumn());
            nodes.add(node);
        }
        return nodes;
    }

    private ObjectNode bindingNode(DatasetBinding binding) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("bindingVersion", binding.bindingVersion());
        node.put("connectionVersionUuid", binding.connectionVersionUuid().toString());
        node.put("dataObjectUuid", binding.dataObjectUuid().toString());
        node.put("dataObjectType", binding.dataObjectType().name());
        node.put("databaseType", binding.databaseType().name());
        node.put("datasetId", binding.datasetId());
        node.put("definitionDataObjectUuid", binding.definitionDataObjectUuid().toString());
        node.put("environmentSchemaBindingUuid",
                binding.environmentSchemaBindingUuid().toString());
        node.put("objectName", binding.objectName());
        node.put("owner", binding.owner());
        node.put("physicalIdentity", binding.physicalIdentity());
        node.put("physicalSchemaUuid", binding.physicalSchemaUuid().toString());
        node.put("role", binding.role().name());
        node.put("schemaSnapshotFingerprint", binding.schemaSnapshotFingerprint());
        node.put("schemaSnapshotUuid", binding.schemaSnapshotUuid().toString());
        return node;
    }

    private ObjectNode writeStrategyNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("kind", WriteStrategy.ATOMIC_DELETE_INSERT.name());
        return node;
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

    private ObjectNode requireObject(
            JsonNode node, PilotPlanFailure failure, String field) {
        if (node == null || !node.isObject()) {
            throw failure(failure, field + " must be a JSON object.");
        }
        return (ObjectNode) node;
    }

    private String requireText(ObjectNode node, String field) {
        return requireText(node, field, PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
    }

    private String requireText(
            ObjectNode node, String field, PilotPlanFailure failureType) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw failure(
                    failureType,
                    field + " must be a non-empty string.");
        }
        return value.stringValue();
    }

    private String requireOracleIdentifier(ObjectNode node, String field) {
        String value = requireText(node, field);
        if (!ORACLE_IDENTIFIER.matcher(value).matches()) {
            throw shape(field + " must be an unquoted uppercase Oracle identifier.");
        }
        return value;
    }

    private int requireInteger(ObjectNode node, String field) {
        return requireInteger(node, field, PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
    }

    private int requireInteger(
            ObjectNode node, String field, PilotPlanFailure failureType) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw failure(
                    failureType,
                    field + " must be an integer.");
        }
        return value.intValue();
    }

    private long requireLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw failure(
                    PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                    field + " must be an integer.");
        }
        return value.longValue();
    }

    private UUID requireUuid(ObjectNode node, String field) {
        return requireUuid(node, field, PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
    }

    private UUID requireUuid(
            ObjectNode node, String field, PilotPlanFailure failureType) {
        String value = requireText(node, field, failureType);
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException exception) {
            throw failure(
                    failureType,
                    field + " must be a UUID.");
        }
    }

    private DatasetRole parseDatasetRole(String role) {
        try {
            return DatasetRole.valueOf(role);
        }
        catch (IllegalArgumentException exception) {
            throw shape("The pilot supports only SOURCE and TARGET dataset roles.");
        }
    }

    private void requireOnlyFields(
            ObjectNode node, Set<String> allowedFields, String objectName) {
        requireOnlyFields(
                node, allowedFields, objectName,
                PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE);
    }

    private void requireOnlyFields(
            ObjectNode node,
            Set<String> allowedFields,
            String objectName,
            PilotPlanFailure failureType) {
        for (String field : node.propertyNames()) {
            if (!allowedFields.contains(field)) {
                throw failure(
                        failureType,
                        "An unsupported field is present in " + objectName + ".");
            }
        }
    }

    private void requireHash(String value, String field) {
        requireHash(value, field, PilotPlanFailure.INVALID_PUBLICATION_MANIFEST);
    }

    private void requireHash(
            String value, String field, PilotPlanFailure failureType) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw failure(
                    failureType,
                    field + " must be a lowercase SHA-256 value.");
        }
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private PilotRuntimePlanException shape(String message) {
        return failure(PilotPlanFailure.UNSUPPORTED_MAPPING_SHAPE, message);
    }

    private void rejectSensitiveManifest(JsonNode manifest) {
        if (!secretSanitizer.sensitivePaths(manifest).isEmpty()) {
            throw failure(
                    PilotPlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "Physical manifest must not contain secret-bearing keys or values.");
        }
    }

    private PilotRuntimePlanException failure(PilotPlanFailure failure, String message) {
        return new PilotRuntimePlanException(failure, message);
    }

    private record ScenarioSource(
            UUID definitionUuid,
            UUID definitionVersionUuid,
            int definitionVersion,
            int schemaVersion,
            String contentHash,
            ObjectNode definition) {
    }

    private record MappingShape(
            String sourceDatasetId,
            String targetDatasetId,
            Map<String, DatasetRole> rolesByDataset,
            List<DirectColumnMapping> columnMappings) {
    }
}

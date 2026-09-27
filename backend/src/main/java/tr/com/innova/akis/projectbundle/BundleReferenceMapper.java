package tr.com.innova.akis.projectbundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleIssue;

/**
 * Rewrites stable source-UUID references inside definition content to target
 * project UUIDs during bundle import. Unknown or unsupported references are
 * reported as issues; the caller is expected to fail closed when the returned
 * issue list is non-empty.
 */
final class BundleReferenceMapper {

    private final ObjectMapper objectMapper;

    BundleReferenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ReferenceContext(
            Map<UUID, UUID> definitionUuidBySource,
            Map<UUID, UUID> versionUuidBySource,
            Map<UUID, UUID> logicalSchemaUuidBySource,
            Map<UUID, UUID> environmentUuidBySource,
            Map<UUID, UUID> dataObjectUuidBySource,
            Map<UUID, UUID> schemaSnapshotUuidBySource,
            Map<UUID, UUID> schemaSnapshotUuidByDataObjectSource,
            List<BundleIssue> issues) {

        public ReferenceContext {
            definitionUuidBySource = Map.copyOf(definitionUuidBySource);
            versionUuidBySource = Map.copyOf(versionUuidBySource);
            logicalSchemaUuidBySource = Map.copyOf(logicalSchemaUuidBySource);
            environmentUuidBySource = Map.copyOf(environmentUuidBySource);
            dataObjectUuidBySource = Map.copyOf(dataObjectUuidBySource);
            schemaSnapshotUuidBySource = Map.copyOf(schemaSnapshotUuidBySource);
            schemaSnapshotUuidByDataObjectSource = Map.copyOf(schemaSnapshotUuidByDataObjectSource);
            issues = issues;
        }

        public ReferenceContext(
                Map<UUID, UUID> definitionUuidBySource,
                Map<UUID, UUID> versionUuidBySource,
                Map<UUID, UUID> logicalSchemaUuidBySource,
                Map<UUID, UUID> dataObjectUuidBySource,
                List<BundleIssue> issues) {
            this(definitionUuidBySource, versionUuidBySource, logicalSchemaUuidBySource,
                    Map.of(), dataObjectUuidBySource, Map.of(), Map.of(), issues);
        }

        public ReferenceContext(
                Map<UUID, UUID> definitionUuidBySource,
                Map<UUID, UUID> versionUuidBySource,
                Map<UUID, UUID> logicalSchemaUuidBySource,
                Map<UUID, UUID> dataObjectUuidBySource,
                Map<UUID, UUID> schemaSnapshotUuidBySource,
                List<BundleIssue> issues) {
            this(definitionUuidBySource, versionUuidBySource, logicalSchemaUuidBySource,
                    Map.of(), dataObjectUuidBySource, schemaSnapshotUuidBySource, Map.of(), issues);
        }
    }

    static ReferenceContext emptyContext() {
        return new ReferenceContext(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new ArrayList<>());
    }

    JsonNode remapDefinitionContent(
            JsonNode content,
            DefinitionType type,
            int schemaVersion,
            ReferenceContext ctx) {
        JsonNode copy = content.deepCopy();
        switch (type) {
            case PACKAGE -> remapPackageSteps(copy, ctx);
            case VARIABLE -> remapVariableLogicalSchema(copy, ctx);
            case PROCEDURE -> remapProcedureTasks(copy, ctx);
            case LOAD_PLAN -> remapLoadPlanSteps(copy.path("steps"), "steps", ctx);
            case MAPPING -> remapMapping(copy, schemaVersion, ctx);
            default -> {
                // no references to rewrite
            }
        }
        return copy;
    }

    private void remapPackageSteps(JsonNode content, ReferenceContext ctx) {
        JsonNode steps = content.path("steps");
        if (!steps.isArray()) return;
        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            if (!step.isObject()) continue;
            JsonNode ref = step.path("definitionUuid");
            if (ref.isMissingNode() || !ref.isTextual()) continue;
            String text = ref.asString();
            if (text == null || text.isBlank()) continue;
            UUID source = parseUuid(text);
            if (source == null) {
                ctx.issues().add(new BundleIssue(
                        "steps[" + index + "].definitionUuid",
                        "PACKAGE_DEFINITION_REFERENCE_UNRESOLVED",
                        "Paket adımındaki definitionUuid geçerli bir UUID değil: " + text));
                continue;
            }
            UUID target = ctx.definitionUuidBySource().get(source);
            String path = "steps[" + index + "].definitionUuid";
            if (target != null) {
                ((ObjectNode) step).put("definitionUuid", target.toString());
            }
            else {
                ctx.issues().add(new BundleIssue(
                        path,
                        "PACKAGE_DEFINITION_REFERENCE_UNRESOLVED",
                        "Paket adımındaki definitionUuid referansı çözülemedi: " + text));
            }
        }
    }

    private void remapVariableLogicalSchema(JsonNode content, ReferenceContext ctx) {
        if (!"REFRESH_QUERY".equals(content.path("valueSource").asString())) return;
        JsonNode ref = content.path("logicalSchemaUuid");
        if (ref.isMissingNode() || !ref.isTextual()) return;
        String text = ref.asString();
        if (text == null || text.isBlank()) return;
        UUID source = parseUuid(text);
        if (source == null) {
            ctx.issues().add(new BundleIssue(
                    "logicalSchemaUuid",
                    "VARIABLE_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED",
                    "Değişken REFRESH_QUERY mantıksal şema referansı geçerli bir UUID değil: " + text));
            return;
        }
        UUID target = ctx.logicalSchemaUuidBySource().get(source);
        if (target != null) {
            ((ObjectNode) content).put("logicalSchemaUuid", target.toString());
        }
        else {
            ctx.issues().add(new BundleIssue(
                    "logicalSchemaUuid",
                    "VARIABLE_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED",
                    "Değişken REFRESH_QUERY mantıksal şema referansı çözülemedi: " + text));
        }
    }

    private void remapProcedureTasks(JsonNode content, ReferenceContext ctx) {
        JsonNode tasks = content.path("tasks");
        if (!tasks.isArray()) return;
        for (int index = 0; index < tasks.size(); index++) {
            JsonNode task = tasks.get(index);
            if (!(task instanceof ObjectNode object)) continue;
            String path = "tasks[" + index + "]";
            remapUuidField(object, path, "logicalSchemaUuid",
                    ctx.logicalSchemaUuidBySource(), ctx,
                    "PROCEDURE_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED",
                    "Prosedür mantıksal şema referansı çözülemedi: ");
            remapUuidField(object, path, "environmentUuid",
                    ctx.environmentUuidBySource(), ctx,
                    "PROCEDURE_ENVIRONMENT_REFERENCE_UNRESOLVED",
                    "Prosedür ortam referansı çözülemedi: ");
            JsonNode parameters = object.path("parameters");
            if (!parameters.isObject()) continue;
            parameters.properties().forEach(entry -> {
                if (!(entry.getValue() instanceof ObjectNode value)) return;
                String parameterPath = path + ".parameters." + entry.getKey();
                remapUuidField(value, parameterPath, "definitionUuid",
                        ctx.definitionUuidBySource(), ctx,
                        "PROCEDURE_VARIABLE_REFERENCE_UNRESOLVED",
                        "Prosedür değişken referansı çözülemedi: ");
                remapUuidField(value, parameterPath, "logicalSchemaUuid",
                        ctx.logicalSchemaUuidBySource(), ctx,
                        "PROCEDURE_PARAMETER_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED",
                        "Prosedür parametre mantıksal şema referansı çözülemedi: ");
            });
        }
    }

    private void remapLoadPlanSteps(JsonNode steps, String path, ReferenceContext ctx) {
        if (!steps.isArray()) return;
        ArrayNode array = (ArrayNode) steps;
        for (int index = 0; index < array.size(); index++) {
            JsonNode step = array.get(index);
            if (!step.isObject()) continue;
            ObjectNode object = (ObjectNode) step;
            String stepPath = path + "[" + index + "]";
            String stepType = step.path("type").asString();
            if ("SCENARIO".equals(stepType)) {
                JsonNode ref = step.path("scenarioVersionUuid");
                if (!ref.isMissingNode() && ref.isTextual()) {
                    String text = ref.asString();
                    if (text != null && !text.isBlank()) {
                        UUID source = parseUuid(text);
                        if (source != null) {
                            UUID target = ctx.versionUuidBySource().get(source);
                            if (target != null) {
                                object.put("scenarioVersionUuid", target.toString());
                            }
                            else {
                                ctx.issues().add(new BundleIssue(
                                        stepPath + ".scenarioVersionUuid",
                                        "LOAD_PLAN_SCENARIO_VERSION_REFERENCE_UNRESOLVED",
                                        "Yükleme planı SCENARIO referansı çözülemedi: " + text));
                            }
                        }
                        else {
                            ctx.issues().add(new BundleIssue(
                                    stepPath + ".scenarioVersionUuid",
                                    "LOAD_PLAN_SCENARIO_VERSION_REFERENCE_UNRESOLVED",
                                    "Yükleme planı SCENARIO referansı geçerli bir UUID değil: " + text));
                        }
                    }
                }
            }
            else if (step.has("steps")) {
                remapLoadPlanSteps(step.path("steps"), stepPath + ".steps", ctx);
            }
        }
    }

    private void remapMapping(JsonNode content, int schemaVersion, ReferenceContext ctx) {
        switch (schemaVersion) {
            case 1, 2 -> {
                // legacy dataset/columnMappings format carries no staging or module references
            }
            case 3 -> {
                if (content instanceof ObjectNode object) {
                    remapMappingStagingLogicalSchema(object, ctx);
                    remapMappingModulePins(object, ctx);
                }
                else {
                    ctx.issues().add(new BundleIssue(
                            "content", "MAPPING_UNSUPPORTED_SCHEMA_VERSION",
                            "Mapping içeriği nesne olmalıdır."));
                }
            }
            case 4 -> remapMappingV4(content, ctx);
            default -> ctx.issues().add(new BundleIssue(
                    "schemaVersion", "MAPPING_UNSUPPORTED_SCHEMA_VERSION",
                    "Desteklenmeyen mapping şema sürümü: " + schemaVersion));
        }
    }

    private void remapMappingStagingLogicalSchema(ObjectNode content, ReferenceContext ctx) {
        JsonNode staging = content.get("staging");
        if (!(staging instanceof ObjectNode stagingObject)) {
            ctx.issues().add(new BundleIssue(
                    "staging.logicalSchemaUuid", "MAPPING_STAGING_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED",
                    "Mapping staging mantıksal şema referansı eksik."));
            return;
        }
        remapRequiredUuidField(stagingObject, "staging", "logicalSchemaUuid",
                ctx.logicalSchemaUuidBySource(), ctx,
                "MAPPING_STAGING_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED",
                "Mapping staging mantıksal şema referansı çözülemedi: ");
    }

    private void remapMappingModulePins(ObjectNode content, ReferenceContext ctx) {
        JsonNode modules = content.get("modules");
        if (!(modules instanceof ObjectNode modulesObject)) {
            ctx.issues().add(new BundleIssue(
                    "modules", "MAPPING_MODULE_VERSION_REFERENCE_UNRESOLVED",
                    "Mapping modül tanımları eksik."));
            return;
        }
        remapMappingModulePin(modulesObject, "loading", ctx);
        remapMappingModulePin(modulesObject, "integration", ctx);
        if (modulesObject.has("checking")) {
            remapMappingModulePin(modulesObject, "checking", ctx);
        }
    }

    private void remapMappingModulePin(ObjectNode modules, String role, ReferenceContext ctx) {
        JsonNode pin = modules.get(role);
        if (!(pin instanceof ObjectNode pinObject)) {
            ctx.issues().add(new BundleIssue(
                    "modules." + role, "MAPPING_MODULE_VERSION_REFERENCE_UNRESOLVED",
                    "Mapping " + role + " modül tanımı eksik."));
            return;
        }
        remapRequiredUuidField(pinObject, "modules." + role, "versionUuid",
                ctx.versionUuidBySource(), ctx,
                "MAPPING_MODULE_VERSION_REFERENCE_UNRESOLVED",
                "Mapping " + role + " modül sürüm referansı çözülemedi: ");
    }

    private void remapMappingV4(JsonNode content, ReferenceContext ctx) {
        if (content instanceof ObjectNode object) {
            remapMappingModulePins(object, ctx);
            remapMappingV4Objects(object.path("sources"), "sources", ctx);
            remapMappingV4Objects(object.path("target"), "target", ctx);
        }
        else {
            ctx.issues().add(new BundleIssue(
                    "content", "MAPPING_UNSUPPORTED_SCHEMA_VERSION",
                    "Mapping içeriği nesne olmalıdır."));
        }
    }

    private void remapMappingV4Objects(JsonNode node, String path, ReferenceContext ctx) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                JsonNode item = node.get(index);
                if (item.isObject()) remapMappingV4Object((ObjectNode) item, path + "[" + index + "]", ctx);
            }
        }
        else if (node.isObject()) remapMappingV4Object((ObjectNode) node, path, ctx);
    }

    private void remapMappingV4Object(ObjectNode object, String path, ReferenceContext ctx) {
        JsonNode originalDataObject = object.get("dataObjectUuid");
        UUID sourceDataObject = originalDataObject != null && originalDataObject.isTextual()
                ? parseUuid(originalDataObject.asString()) : null;
        remapUuidField(object, path, "dataObjectUuid", ctx.dataObjectUuidBySource(), ctx,
                "MAPPING_V4_DATA_OBJECT_REFERENCE_UNRESOLVED",
                "Mapping v4 veri nesnesi referansı çözülemedi: ");
        JsonNode snapshot = object.get("schemaSnapshotUuid");
        if (snapshot == null || snapshot.isNull()) return;
        if (!snapshot.isTextual()) {
            ctx.issues().add(new BundleIssue(
                    path + ".schemaSnapshotUuid",
                    "MAPPING_V4_SCHEMA_SNAPSHOT_REFERENCE_UNRESOLVED",
                    "Mapping v4 şema görüntüsü referansı çözülemedi: " + snapshot));
            return;
        }
        UUID sourceSnapshot = parseUuid(snapshot.asString());
        UUID targetSnapshot = sourceSnapshot == null
                ? null : ctx.schemaSnapshotUuidBySource().get(sourceSnapshot);
        if (targetSnapshot == null) {
            targetSnapshot = sourceDataObject == null
                    ? null : ctx.schemaSnapshotUuidByDataObjectSource().get(sourceDataObject);
        }
        if (targetSnapshot != null) {
            object.put("schemaSnapshotUuid", targetSnapshot.toString());
        }
        else {
            ctx.issues().add(new BundleIssue(
                    path + ".schemaSnapshotUuid",
                    "MAPPING_V4_SCHEMA_SNAPSHOT_REFERENCE_UNRESOLVED",
                    "Mapping v4 şema görüntüsü referansı çözülemedi: "
                            + snapshot.asString()));
        }
    }

    private void remapUuidField(
            ObjectNode object, String path, String field, Map<UUID, UUID> mappings,
            ReferenceContext ctx,
            String issueCode, String messagePrefix) {
        JsonNode ref = object.get(field);
        if (ref == null || ref.isNull()) return;
        if (!ref.isTextual()) {
            ctx.issues().add(new BundleIssue(path + "." + field, issueCode, messagePrefix + ref));
            return;
        }
        String text = ref.asString();
        UUID source = parseUuid(text);
        UUID target = source == null ? null : mappings.get(source);
        if (target != null) {
            object.put(field, target.toString());
            return;
        }
        // The caller cannot safely preserve a source-project UUID.
        ctx.issues().add(new BundleIssue(path + "." + field, issueCode, messagePrefix + text));
    }

    private void remapRequiredUuidField(
            ObjectNode object, String path, String field, Map<UUID, UUID> mappings,
            ReferenceContext ctx,
            String issueCode, String messagePrefix) {
        JsonNode ref = object.get(field);
        if (ref == null || ref.isNull() || !ref.isTextual() || ref.asString().isBlank()) {
            ctx.issues().add(new BundleIssue(path + "." + field, issueCode, messagePrefix + "eksik"));
            return;
        }
        String text = ref.asString();
        UUID source = parseUuid(text);
        UUID target = source == null ? null : mappings.get(source);
        if (target != null) {
            object.put(field, target.toString());
            return;
        }
        ctx.issues().add(new BundleIssue(path + "." + field, issueCode, messagePrefix + text));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            Collections.sort(names);
            for (String name : names) {
                result.set(name, canonical(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node.deepCopy();
    }
}

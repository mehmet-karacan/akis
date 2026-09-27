package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleIssue;

class BundleReferenceMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BundleReferenceMapper mapper = new BundleReferenceMapper(objectMapper);

    @Test
    void invalidPackageDefinitionUuidIsReported() {
        var context = BundleReferenceMapper.emptyContext();
        mapper.remapDefinitionContent(
                objectMapper.readTree("""
                        {"steps":[{"definitionUuid":"not-a-uuid"}]}
                        """), DefinitionType.PACKAGE, 1, context);

        assertEquals(1, context.issues().size());
        assertEquals("steps[0].definitionUuid", context.issues().getFirst().path());
    }

    @Test
    void invalidVariableLogicalSchemaUuidIsReported() {
        var context = BundleReferenceMapper.emptyContext();
        mapper.remapDefinitionContent(
                objectMapper.readTree("""
                        {"valueSource":"REFRESH_QUERY","logicalSchemaUuid":"not-a-uuid"}
                        """), DefinitionType.VARIABLE, 1, context);

        assertEquals(1, context.issues().size());
        assertEquals("logicalSchemaUuid", context.issues().getFirst().path());
    }

    @Test
    void invalidLoadPlanScenarioVersionUuidIsReported() {
        var context = BundleReferenceMapper.emptyContext();
        mapper.remapDefinitionContent(
                objectMapper.readTree("""
                        {"steps":[{"type":"SCENARIO","scenarioVersionUuid":"not-a-uuid"}]}
                        """), DefinitionType.LOAD_PLAN, 1, context);

        assertEquals(1, context.issues().size());
        assertEquals("steps[0].scenarioVersionUuid", context.issues().getFirst().path());
    }

    @Test
    void procedureTaskAndParameterReferencesUseTargetUuids() {
        UUID sourceDefinition = UUID.randomUUID();
        UUID targetDefinition = UUID.randomUUID();
        UUID sourceLogical = UUID.randomUUID();
        UUID targetLogical = UUID.randomUUID();
        UUID sourceEnvironment = UUID.randomUUID();
        UUID targetEnvironment = UUID.randomUUID();
        var context = new BundleReferenceMapper.ReferenceContext(
                Map.of(sourceDefinition, targetDefinition), Map.of(),
                Map.of(sourceLogical, targetLogical),
                Map.of(sourceEnvironment, targetEnvironment),
                Map.of(), Map.of(), Map.of(), new ArrayList<>());
        var content = objectMapper.createObjectNode();
        var task = content.putArray("tasks").addObject();
        task.put("logicalSchemaUuid", sourceLogical.toString());
        task.put("environmentUuid", sourceEnvironment.toString());
        var parameter = task.putObject("parameters").putObject("VALUE_1");
        parameter.put("definitionUuid", sourceDefinition.toString());
        parameter.put("logicalSchemaUuid", sourceLogical.toString());

        var remapped = mapper.remapDefinitionContent(content, DefinitionType.PROCEDURE, 1, context);

        assertEquals(targetLogical.toString(), remapped.path("tasks").get(0).path("logicalSchemaUuid").asString());
        assertEquals(targetEnvironment.toString(), remapped.path("tasks").get(0).path("environmentUuid").asString());
        assertEquals(targetDefinition.toString(), remapped.path("tasks").get(0).path("parameters").path("VALUE_1").path("definitionUuid").asString());
        assertEquals(targetLogical.toString(), remapped.path("tasks").get(0).path("parameters").path("VALUE_1").path("logicalSchemaUuid").asString());
        assertEquals(0, context.issues().size());
    }

    @Test
    void procedureReferenceOutsideBundleFailsClosed() {
        var context = BundleReferenceMapper.emptyContext();
        var content = objectMapper.createObjectNode();
        content.putArray("tasks").addObject()
                .put("environmentUuid", UUID.randomUUID().toString());

        mapper.remapDefinitionContent(content, DefinitionType.PROCEDURE, 1, context);

        assertEquals("PROCEDURE_ENVIRONMENT_REFERENCE_UNRESOLVED", context.issues().getFirst().code());
    }

    @Test
    void mappingV3StagingAndModuleReferencesUseTargetUuids() {
        UUID sourceLogical = UUID.randomUUID();
        UUID targetLogical = UUID.randomUUID();
        UUID sourceLoadingVersion = UUID.randomUUID();
        UUID targetLoadingVersion = UUID.randomUUID();
        UUID sourceIntegrationVersion = UUID.randomUUID();
        UUID targetIntegrationVersion = UUID.randomUUID();
        var context = new BundleReferenceMapper.ReferenceContext(
                Map.of(), Map.of(
                        sourceLoadingVersion, targetLoadingVersion,
                        sourceIntegrationVersion, targetIntegrationVersion),
                Map.of(sourceLogical, targetLogical),
                Map.of(), Map.of(), Map.of(), Map.of(), new ArrayList<>());
        var content = objectMapper.createObjectNode();
        content.putObject("staging").put("logicalSchemaUuid", sourceLogical.toString());
        var modules = content.putObject("modules");
        modules.putObject("loading").put("versionUuid", sourceLoadingVersion.toString());
        modules.putObject("integration").put("versionUuid", sourceIntegrationVersion.toString());

        var remapped = mapper.remapDefinitionContent(content, DefinitionType.MAPPING, 3, context);

        assertEquals(targetLogical.toString(), remapped.path("staging").path("logicalSchemaUuid").asString());
        assertEquals(targetLoadingVersion.toString(),
                remapped.path("modules").path("loading").path("versionUuid").asString());
        assertEquals(targetIntegrationVersion.toString(),
                remapped.path("modules").path("integration").path("versionUuid").asString());
        assertEquals(0, context.issues().size());
    }

    @Test
    void mappingV3MissingStagingFailsClosed() {
        var context = BundleReferenceMapper.emptyContext();
        var content = objectMapper.createObjectNode();
        var modules = content.putObject("modules");
        modules.putObject("loading").put("versionUuid", UUID.randomUUID().toString());
        modules.putObject("integration").put("versionUuid", UUID.randomUUID().toString());

        mapper.remapDefinitionContent(content, DefinitionType.MAPPING, 3, context);

        assertEquals(true, context.issues().stream()
                .anyMatch(issue -> "staging.logicalSchemaUuid".equals(issue.path())
                        && "MAPPING_STAGING_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED".equals(issue.code())));
    }

    @Test
    void mappingV3UnresolvedModuleVersionFailsClosed() {
        var context = BundleReferenceMapper.emptyContext();
        var content = objectMapper.createObjectNode();
        content.putObject("staging").put("logicalSchemaUuid", UUID.randomUUID().toString());
        var modules = content.putObject("modules");
        modules.putObject("loading").put("versionUuid", UUID.randomUUID().toString());
        modules.putObject("integration").put("versionUuid", UUID.randomUUID().toString());

        mapper.remapDefinitionContent(content, DefinitionType.MAPPING, 3, context);

        assertEquals(Set.of(
                "staging.logicalSchemaUuid", "modules.loading.versionUuid", "modules.integration.versionUuid"),
                context.issues().stream().map(BundleIssue::path).collect(Collectors.toSet()));
        assertEquals(true, context.issues().stream()
                .allMatch(issue -> "MAPPING_STAGING_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED".equals(issue.code())
                        || "MAPPING_MODULE_VERSION_REFERENCE_UNRESOLVED".equals(issue.code())));
    }

    @Test
    void mappingV4ModuleAndObjectReferencesUseTargetUuids() {
        UUID sourceLoadingVersion = UUID.randomUUID();
        UUID targetLoadingVersion = UUID.randomUUID();
        UUID sourceIntegrationVersion = UUID.randomUUID();
        UUID targetIntegrationVersion = UUID.randomUUID();
        UUID sourceDataObject = UUID.randomUUID();
        UUID targetDataObject = UUID.randomUUID();
        UUID sourceSnapshot = UUID.randomUUID();
        UUID targetSnapshot = UUID.randomUUID();
        var context = new BundleReferenceMapper.ReferenceContext(
                Map.of(), Map.of(
                        sourceLoadingVersion, targetLoadingVersion,
                        sourceIntegrationVersion, targetIntegrationVersion),
                Map.of(), Map.of(sourceDataObject, targetDataObject),
                Map.of(sourceSnapshot, targetSnapshot), new ArrayList<>());
        var content = objectMapper.createObjectNode();
        var sources = content.putArray("sources").addObject();
        sources.put("id", "SRC").put("alias", "S")
                .put("dataObjectUuid", sourceDataObject.toString())
                .put("schemaSnapshotUuid", sourceSnapshot.toString());
        var target = content.putObject("target");
        target.put("id", "TGT").put("alias", "T")
                .put("dataObjectUuid", sourceDataObject.toString())
                .put("schemaSnapshotUuid", sourceSnapshot.toString());
        var modules = content.putObject("modules");
        modules.putObject("loading").put("versionUuid", sourceLoadingVersion.toString());
        modules.putObject("integration").put("versionUuid", sourceIntegrationVersion.toString());

        var remapped = mapper.remapDefinitionContent(content, DefinitionType.MAPPING, 4, context);

        assertEquals(targetLoadingVersion.toString(),
                remapped.path("modules").path("loading").path("versionUuid").asString());
        assertEquals(targetIntegrationVersion.toString(),
                remapped.path("modules").path("integration").path("versionUuid").asString());
        assertEquals(targetDataObject.toString(),
                remapped.path("sources").get(0).path("dataObjectUuid").asString());
        assertEquals(targetSnapshot.toString(),
                remapped.path("sources").get(0).path("schemaSnapshotUuid").asString());
        assertEquals(targetDataObject.toString(), remapped.path("target").path("dataObjectUuid").asString());
        assertEquals(targetSnapshot.toString(), remapped.path("target").path("schemaSnapshotUuid").asString());
        assertEquals(0, context.issues().size());
    }

    @Test
    void mappingV4UnresolvedModuleVersionFailsClosed() {
        var context = BundleReferenceMapper.emptyContext();
        var content = objectMapper.createObjectNode();
        var modules = content.putObject("modules");
        modules.putObject("loading").put("versionUuid", UUID.randomUUID().toString());
        modules.putObject("integration").put("versionUuid", UUID.randomUUID().toString());

        mapper.remapDefinitionContent(content, DefinitionType.MAPPING, 4, context);

        assertEquals(Set.of("modules.loading.versionUuid", "modules.integration.versionUuid"),
                context.issues().stream().map(BundleIssue::path).collect(Collectors.toSet()));
        assertEquals(true, context.issues().stream()
                .allMatch(issue -> "MAPPING_MODULE_VERSION_REFERENCE_UNRESOLVED".equals(issue.code())));
    }

    @Test
    void mappingUnknownSchemaVersionFailsClosed() {
        var context = BundleReferenceMapper.emptyContext();
        var content = objectMapper.readTree("""
                {"staging":{"logicalSchemaUuid":"%s"},"modules":{}}
                """.formatted(UUID.randomUUID()));

        var remapped = mapper.remapDefinitionContent(content, DefinitionType.MAPPING, 99, context);

        assertEquals(1, context.issues().size());
        assertEquals("MAPPING_UNSUPPORTED_SCHEMA_VERSION", context.issues().getFirst().code());
        assertEquals(content, remapped);
    }
}

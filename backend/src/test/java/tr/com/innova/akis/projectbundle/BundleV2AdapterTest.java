package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.BundleV2Adapter.AdaptationResult;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DefinitionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DraftEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.FolderEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.VersionEntry;

class BundleV2AdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BundleV2Adapter adapter = new BundleV2Adapter(objectMapper);

    @Test
    void leavesV3BundleUnchanged() {
        ProjectBundle v3 = v3Bundle(emptyTopology());

        AdaptationResult result = adapter.adapt(v3, List.of());

        assertTrue(result.issues().isEmpty());
        assertEquals(v3, result.bundle());
    }

    @Test
    void v2BundleWithoutLegacyReferencesNormalizesToV3() {
        ProjectBundle v2 = v2Bundle(emptyTopology(), List.of(), List.of());

        AdaptationResult result = adapter.adapt(v2, List.of());

        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
        assertEquals(ProjectBundleModels.FORMAT_VERSION, result.bundle().formatVersion());
        assertEquals(ProjectBundleModels.SCHEMA_VERSION, result.bundle().schemaVersion());
        assertNotNull(result.bundle().producer());
        assertEquals(ProjectBundleModels.INCLUDED_SECTIONS, result.bundle().includedSections());
        assertEquals(List.of(), result.bundle().publications());
        assertEquals(List.of(), result.bundle().schedules());
    }

    @Test
    void v2BundleWithResolvableDefinitionSelfReferenceSucceeds() {
        UUID sourceDefUuid = UUID.randomUUID();
        JsonNode content = readTree("""
                {"firstStepId":"s1","steps":[{"id":"s1","type":"PROCEDURE","definitionUuid":"%s"}],"transitions":[]}
                """.formatted(sourceDefUuid));
        DefinitionEntry referenced = new DefinitionEntry(
                DefinitionType.PACKAGE, "REFERENCED_PKG", "ROOT/CHILD", "AKTIF",
                "Referenced", null, null, List.of(), sourceDefUuid);
        DefinitionEntry importer = new DefinitionEntry(
                DefinitionType.PACKAGE, "IMPORTER", "ROOT/CHILD", "AKTIF",
                "Importer", null, null, List.of(new VersionEntry(
                        1, 1, "0".repeat(64), content, null, OffsetDateTime.now())));
        ProjectBundle v2 = v2BundleWithDefinitions(referenced, importer);

        AdaptationResult result = adapter.adapt(v2, List.of());

        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
        assertEquals(ProjectBundleModels.FORMAT_VERSION, result.bundle().formatVersion());
    }

    @Test
    void v2BundleWithResolvableVersionSelfReferenceSucceeds() {
        UUID sourceVersionUuid = UUID.randomUUID();
        JsonNode content = readTree("""
                {"steps":[{"id":"s1","type":"SCENARIO","scenarioVersionUuid":"%s"}],"restartPolicy":"FAILED_STEP"}
                """.formatted(sourceVersionUuid));
        VersionEntry scenarioVersion = new VersionEntry(
                1, 1, "0".repeat(64), readTree("""
                        {"implementation":"REPOSITORY","start":1,"increment":1,"cycle":false}
                        """), null, OffsetDateTime.now(), sourceVersionUuid);
        VersionEntry loadPlanVersion = new VersionEntry(
                1, 1, "0".repeat(64), content, null, OffsetDateTime.now());
        DefinitionEntry scenario = new DefinitionEntry(
                DefinitionType.SEQUENCE, "SCENARIO_VERSION", "ROOT/CHILD", "AKTIF",
                "Scenario Version", null, null, List.of(scenarioVersion));
        DefinitionEntry loadPlan = new DefinitionEntry(
                DefinitionType.LOAD_PLAN, "LP", "ROOT/CHILD", "AKTIF",
                "Load Plan", null, null, List.of(loadPlanVersion));
        ProjectBundle v2 = v2BundleWithDefinitions(scenario, loadPlan);

        AdaptationResult result = adapter.adapt(v2, List.of());

        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
    }

    @Test
    void v2BundleWithUnresolvedPackageDefinitionReferenceIsRejected() {
        UUID missingUuid = UUID.randomUUID();
        JsonNode content = readTree("""
                {"firstStepId":"s1","steps":[{"id":"s1","type":"PROCEDURE","definitionUuid":"%s"}],"transitions":[]}
                """.formatted(missingUuid));
        DefinitionEntry importer = new DefinitionEntry(
                DefinitionType.PACKAGE, "IMPORTER", "ROOT/CHILD", "AKTIF",
                "Importer", null, null, List.of(new VersionEntry(
                        1, 1, "0".repeat(64), content, null, OffsetDateTime.now())));
        ProjectBundle v2 = v2BundleWithDefinitions(importer);

        AdaptationResult result = adapter.adapt(v2, List.of());

        assertFalse(result.issues().isEmpty());
        assertTrue(result.issues().stream().anyMatch(
                issue -> BundleV2Adapter.UNRESOLVED_LEGACY_REFERENCE.equals(issue.code())
                        && issue.path().equals("steps[0].definitionUuid")
                        && issue.message().contains("yeniden dışa aktarın")));
    }

    @Test
    void v2BundleWithUnresolvedLogicalSchemaSourceUuidIsRejected() {
        UUID missingUuid = UUID.randomUUID();
        ObjectNode topology = (ObjectNode) emptyTopology();
        ObjectNode schema = ((ArrayNode) topology.get("logicalSchemas")).addObject();
        schema.put("code", "UNBOUND_LS");
        schema.put("name", "Unbound Logical Schema");
        schema.put("sourceUuid", missingUuid.toString());
        ProjectBundle v2 = v2Bundle(topology, List.of(), List.of());

        AdaptationResult result = adapter.adapt(v2, List.of());

        assertFalse(result.issues().isEmpty());
        assertTrue(result.issues().stream().anyMatch(
                issue -> BundleV2Adapter.UNRESOLVED_LEGACY_REFERENCE.equals(issue.code())
                        && issue.path().equals("topology.definitions.logicalSchemas[0].sourceUuid")
                        && issue.message().contains("yeniden dışa aktarın")));
    }

    @Test
    void v2BundleWithUnresolvedVariableLogicalSchemaReferenceIsRejected() {
        UUID missingUuid = UUID.randomUUID();
        JsonNode content = readTree("""
                {"dataType":"STRING","scope":"PROJECT","historyMode":"NONE","valueSource":"REFRESH_QUERY","logicalSchemaUuid":"%s","query":"select 1"}
                """.formatted(missingUuid));
        DefinitionEntry variable = new DefinitionEntry(
                DefinitionType.VARIABLE, "VAR", "ROOT/CHILD", "AKTIF",
                "Variable", null, null, List.of(new VersionEntry(
                        1, 1, "0".repeat(64), content, null, OffsetDateTime.now())));
        ProjectBundle v2 = v2BundleWithDefinitions(variable);

        AdaptationResult result = adapter.adapt(v2, List.of());

        assertFalse(result.issues().isEmpty());
        assertTrue(result.issues().stream().anyMatch(
                issue -> BundleV2Adapter.UNRESOLVED_LEGACY_REFERENCE.equals(issue.code())
                        && issue.path().equals("logicalSchemaUuid")
                        && issue.message().contains("yeniden dışa aktarın")));
    }

    @Test
    void explicitLogicalSchemaBindingResolvesTopologyReference() {
        UUID sourceUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        ObjectNode topology = (ObjectNode) emptyTopology();
        ObjectNode schema = ((ArrayNode) topology.get("logicalSchemas")).addObject();
        schema.put("code", "LS");
        schema.put("name", "Logical Schema");
        schema.put("sourceUuid", sourceUuid.toString());
        ProjectBundle v2 = v2Bundle(topology, List.of(), List.of());

        AdaptationResult result = adapter.adapt(v2, List.of(new GlobalBinding(
                GlobalResourceType.LOGICAL_SCHEMA, "LS",
                GlobalBindingMode.BIND_EXISTING, targetUuid, null, null)));

        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
    }

    private ProjectBundle v2Bundle(JsonNode topology,
            List<ProjectBundleModels.PublicationEntry> publications,
            List<ProjectBundleModels.ScheduleEntry> schedules) {
        return new ProjectBundle(
                ProjectBundleModels.FORMAT,
                ProjectBundleModels.LEGACY_FORMAT_VERSION,
                ProjectBundleModels.LEGACY_SCHEMA_VERSION,
                null,
                OffsetDateTime.now(),
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                List.of(
                        new FolderEntry("ROOT/CHILD", "ROOT", "CHILD", "AKTIF", "Child", null),
                        new FolderEntry("ROOT", null, "ROOT", "AKTIF", "Root", null)),
                List.of(),
                new TopologyEntry(true, topology),
                null, null, publications, schedules);
    }

    private ProjectBundle v2BundleWithDefinitions(DefinitionEntry... definitions) {
        ProjectBundle base = v2Bundle(emptyTopology(), List.of(), List.of());
        return new ProjectBundle(
                base.format(), base.formatVersion(), base.schemaVersion(), null,
                base.exportedAt(), base.project(), base.folders(), List.of(definitions),
                base.topology(), null, null, base.publications(), base.schedules());
    }

    private ProjectBundle v3Bundle(JsonNode topology) {
        return new ProjectBundle(
                ProjectBundleModels.FORMAT,
                ProjectBundleModels.FORMAT_VERSION,
                ProjectBundleModels.SCHEMA_VERSION,
                "0".repeat(64),
                OffsetDateTime.now(),
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                List.of(),
                List.of(),
                new TopologyEntry(true, topology),
                new ProjectBundleModels.ProducerEntry("akis-backend", "0.1.0-SNAPSHOT", "unknown"),
                ProjectBundleModels.INCLUDED_SECTIONS,
                List.of(),
                List.of());
    }

    private JsonNode emptyTopology() {
        ObjectNode node = objectMapper.createObjectNode();
        for (String name : List.of(
                "connections", "physicalSchemas", "logicalSchemas", "environments",
                "schemaBindings", "models", "submodels", "dataObjects")) {
            node.putArray(name);
        }
        return node;
    }

    private JsonNode readTree(String value) {
        return objectMapper.readTree(value);
    }
}

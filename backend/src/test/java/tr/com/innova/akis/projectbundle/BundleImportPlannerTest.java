package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleCounts;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProducerEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.PublicationEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ValidationReport;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.GlobalResourceRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ProjectRow;

/**
 * Focused tests for {@link BundleImportPlanner}'s plan-side global dependency scan,
 * exercised directly (bypassing {@link ProjectBundleService}) so the environment
 * dependency contributed by publication recipes is isolated from the rest of the
 * import pipeline.
 */
class BundleImportPlannerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProjectRow target = new ProjectRow(
            1, UUID.randomUUID(), "TARGET", 1, "AKTIF", "Target", null);

    @Test
    void publicationEnvironmentRequiresExplicitBindEvenWithoutTopologyBinding() {
        BundleImportPlanner planner = new BundleImportPlanner(new StubRepository(), objectMapper);
        ProjectBundle bundle = bundle(emptyTopology(), List.of(publication("PROD")));

        var planned = planner.plan(target, bundle, List.of(), validation());

        assertFalse(planned.plan().valid());
        assertTrue(planned.plan().issues().stream().anyMatch(
                issue -> issue.code().equals("GLOBAL_BINDING_REQUIRED")
                        && issue.path().equals("publications[0].environmentCode")));
        assertTrue(planned.plan().globalDependencies().stream().anyMatch(
                dependency -> dependency.type() == GlobalResourceType.ENVIRONMENT
                        && dependency.sourceCode().equals("PROD")
                        && !dependency.resolved()));
    }

    @Test
    void deduplicatesEnvironmentDependencyBetweenTopologyAndPublicationByTypeAndCode() {
        BundleImportPlanner planner = new BundleImportPlanner(new StubRepository(), objectMapper);
        ProjectBundle bundle = bundle(topologyWithEnvironment("PROD"), List.of(publication("PROD")));

        var planned = planner.plan(target, bundle, List.of(), validation());

        long environmentDependencyCount = planned.plan().globalDependencies().stream()
                .filter(dependency -> dependency.type() == GlobalResourceType.ENVIRONMENT
                        && dependency.sourceCode().equals("PROD"))
                .count();
        assertEquals(1, environmentDependencyCount);
    }

    @Test
    void resolvesPublicationOnlyEnvironmentWithExplicitBindExisting() {
        UUID targetEnvironmentUuid = UUID.randomUUID();
        StubRepository repository = new StubRepository();
        repository.resource = new GlobalResourceRow(
                GlobalResourceType.ENVIRONMENT, targetEnvironmentUuid, "PROD_TARGET", null, null);
        BundleImportPlanner planner = new BundleImportPlanner(repository, objectMapper);
        ProjectBundle bundle = bundle(emptyTopology(), List.of(publication("PROD")));
        List<GlobalBinding> bindings = List.of(new GlobalBinding(
                GlobalResourceType.ENVIRONMENT, "PROD",
                GlobalBindingMode.BIND_EXISTING, targetEnvironmentUuid, null, null));

        var planned = planner.plan(target, bundle, bindings, validation());

        assertTrue(planned.plan().valid(), () -> planned.plan().issues().toString());
        assertTrue(planned.plan().globalDependencies().stream().anyMatch(
                dependency -> dependency.type() == GlobalResourceType.ENVIRONMENT
                        && dependency.sourceCode().equals("PROD")
                        && dependency.resolved()
                        && targetEnvironmentUuid.equals(dependency.targetUuid())
                        && "PROD_TARGET".equals(dependency.targetCode())));
    }

    @Test
    void warnsWhenTheBoundEnvironmentIsTheSameGlobalResourceAsTheSource() {
        UUID sourceEnvironmentUuid = UUID.randomUUID();
        StubRepository repository = new StubRepository();
        repository.resource = new GlobalResourceRow(
                GlobalResourceType.ENVIRONMENT, sourceEnvironmentUuid, "PROD", null, null);
        BundleImportPlanner planner = new BundleImportPlanner(repository, objectMapper);
        ObjectNode topology = (ObjectNode) topologyWithEnvironment("PROD");
        ((ObjectNode) topology.path("environments").get(0))
                .put("sourceUuid", sourceEnvironmentUuid.toString());
        ProjectBundle bundle = bundle(topology, List.of(publication("PROD")));
        List<GlobalBinding> bindings = List.of(new GlobalBinding(
                GlobalResourceType.ENVIRONMENT, "PROD",
                GlobalBindingMode.BIND_EXISTING, sourceEnvironmentUuid, null, null));

        var planned = planner.plan(target, bundle, bindings, validation());

        assertTrue(planned.plan().valid(), () -> planned.plan().issues().toString());
        assertTrue(planned.plan().globalDependencies().stream().anyMatch(
                dependency -> dependency.type() == GlobalResourceType.ENVIRONMENT
                        && dependency.resolved()
                        && dependency.message().contains("fiziksel veri yalıtımı sağlamaz")));
    }

    @Test
    void planDigestAndDependencyOrderAreDeterministicAcrossRepeatedPlanning() {
        BundleImportPlanner planner = new BundleImportPlanner(new StubRepository(), objectMapper);
        ProjectBundle bundle = bundle(
                topologyWithEnvironment("STAGING"),
                List.of(publication("PROD"), publication("STAGING")));

        var first = planner.plan(target, bundle, List.of(), validation());
        var second = planner.plan(target, bundle, List.of(), validation());

        assertEquals(first.plan().planDigest(), second.plan().planDigest());
        assertEquals(
                first.plan().globalDependencies().stream().map(d -> d.type() + ":" + d.sourceCode()).toList(),
                second.plan().globalDependencies().stream().map(d -> d.type() + ":" + d.sourceCode()).toList());
        assertEquals(2, first.plan().globalDependencies().stream()
                .filter(d -> d.type() == GlobalResourceType.ENVIRONMENT).count());
    }

    @Test
    void remapsPortableSnapshotToExplicitlyBoundDifferentPhysicalTarget() {
        UUID targetConnectionUuid = UUID.randomUUID();
        UUID targetSchemaUuid = UUID.randomUUID();
        StubRepository repository = new StubRepository();
        repository.resources = Map.of(
                targetConnectionUuid, new GlobalResourceRow(
                        GlobalResourceType.CONNECTION, targetConnectionUuid, "TARGET_CONNECTION", null, null),
                targetSchemaUuid, new GlobalResourceRow(
                        GlobalResourceType.PHYSICAL_SCHEMA, targetSchemaUuid, "TARGET_SCHEMA", null,
                        "TARGET_CONNECTION"));
        ObjectNode topology = (ObjectNode) emptyTopology();
        ((ArrayNode) topology.get("connections")).addObject().put("code", "SOURCE_CONNECTION");
        ((ArrayNode) topology.get("physicalSchemas")).addObject()
                .put("code", "SOURCE_SCHEMA").put("connectionCode", "SOURCE_CONNECTION");
        ((ArrayNode) topology.get("dataObjects")).addObject()
                .put("sourceUuid", UUID.randomUUID().toString())
                .put("sourceConnectionCode", "SOURCE_CONNECTION")
                .put("sourcePhysicalSchemaCode", "SOURCE_SCHEMA")
                .put("sourceSchemaSnapshotUuid", UUID.randomUUID().toString())
                .put("sourceSnapshotFingerprint", "a".repeat(64));

        var planned = new BundleImportPlanner(repository, objectMapper).plan(
                target, bundle(topology, List.of()), List.of(
                        new GlobalBinding(GlobalResourceType.CONNECTION, "SOURCE_CONNECTION",
                                GlobalBindingMode.BIND_EXISTING, targetConnectionUuid, null, null),
                        new GlobalBinding(GlobalResourceType.PHYSICAL_SCHEMA,
                                "SOURCE_CONNECTION::SOURCE_SCHEMA",
                                GlobalBindingMode.BIND_EXISTING, targetSchemaUuid, null, null)), validation());

        assertTrue(planned.plan().valid(), () -> planned.plan().issues().toString());
        JsonNode dataObject = planned.resolvedTopology().path("dataObjects").get(0);
        assertEquals("TARGET_CONNECTION", dataObject.path("sourceConnectionCode").asString());
        assertEquals("TARGET_SCHEMA", dataObject.path("sourcePhysicalSchemaCode").asString());

        repository.resources = Map.of(
                targetConnectionUuid, new GlobalResourceRow(
                        GlobalResourceType.CONNECTION, targetConnectionUuid, "TARGET_CONNECTION", null, null),
                targetSchemaUuid, new GlobalResourceRow(
                        GlobalResourceType.PHYSICAL_SCHEMA, targetSchemaUuid, "TARGET_SCHEMA", null,
                        "UNRELATED_CONNECTION"));
        var mismatched = new BundleImportPlanner(repository, objectMapper).plan(
                target, bundle(topology, List.of()), List.of(
                        new GlobalBinding(GlobalResourceType.CONNECTION, "SOURCE_CONNECTION",
                                GlobalBindingMode.BIND_EXISTING, targetConnectionUuid, null, null),
                        new GlobalBinding(GlobalResourceType.PHYSICAL_SCHEMA,
                                "SOURCE_CONNECTION::SOURCE_SCHEMA",
                                GlobalBindingMode.BIND_EXISTING, targetSchemaUuid, null, null)), validation());
        assertFalse(mismatched.plan().valid());
        assertTrue(mismatched.plan().issues().stream().anyMatch(issue ->
                issue.code().equals("PHYSICAL_SCHEMA_CONNECTION_MISMATCH")));
    }

    private ValidationReport validation() {
        return new ValidationReport(true, new BundleCounts(0, 0, 0, 0, 1, 0), List.of());
    }

    private PublicationEntry publication(String environmentCode) {
        return new PublicationEntry(DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, environmentCode);
    }

    private ProjectBundle bundle(JsonNode topology, List<PublicationEntry> publications) {
        return new ProjectBundle(
                ProjectBundleModels.FORMAT, ProjectBundleModels.FORMAT_VERSION,
                ProjectBundleModels.SCHEMA_VERSION, "0".repeat(64), OffsetDateTime.now(),
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                List.of(), List.of(), new TopologyEntry(true, topology),
                new ProducerEntry("akis-backend", "0.1.0-SNAPSHOT", "unknown"),
                ProjectBundleModels.INCLUDED_SECTIONS, publications, List.of());
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

    private JsonNode topologyWithEnvironment(String code) {
        ObjectNode node = (ObjectNode) emptyTopology();
        ObjectNode environment = ((ArrayNode) node.get("environments")).addObject();
        environment.put("code", code);
        environment.put("name", code);
        return node;
    }

    private static final class StubRepository extends ProjectBundleRepository {

        private GlobalResourceRow resource;
        private Map<UUID, GlobalResourceRow> resources = Map.of();

        StubRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        boolean projectContentIsEmpty(long projectId) {
            return true;
        }

        @Override
        Optional<GlobalResourceRow> findGlobalResource(GlobalResourceType type, UUID uuid) {
            GlobalResourceRow match = resources.get(uuid);
            return Optional.ofNullable(match == null ? resource : match);
        }
    }
}

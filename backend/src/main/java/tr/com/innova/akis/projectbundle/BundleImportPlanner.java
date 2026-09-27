package tr.com.innova.akis.projectbundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.BundleReferenceMapper.ReferenceContext;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleIssue;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DefinitionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalDependency;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.PublicationEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportPlan;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ValidationReport;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.VersionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.GlobalResourceRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ProjectRow;

/** Builds a deterministic, read-only import plan for an existing empty project. */
@Component
final class BundleImportPlanner {

    private final ProjectBundleRepository repository;
    private final ObjectMapper objectMapper;

    BundleImportPlanner(ProjectBundleRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    PlannedImport plan(
            ProjectRow target,
            ProjectBundle bundle,
            List<GlobalBinding> requestedBindings,
            ValidationReport validation) {
        List<BundleIssue> issues = new ArrayList<>(validation.issues());
        if (!repository.projectContentIsEmpty(target.id())) {
            issues.add(new BundleIssue(
                    "targetProject", "TARGET_PROJECT_NOT_EMPTY",
                    "Paket yalnız boş bir hedef projeye aktarılabilir."));
        }

        Map<DependencyKey, GlobalBinding> bindings = indexBindings(requestedBindings, issues);
        List<GlobalDependency> dependencies = new ArrayList<>();
        Map<DependencyKey, GlobalResourceRow> resolved = new HashMap<>();
        for (SourceDependency source : sourceDependencies(bundle)) {
            DependencyKey key = new DependencyKey(source.type(), source.code());
            GlobalBinding binding = bindings.get(key);
            if (binding == null) {
                issues.add(new BundleIssue(
                        source.path(), "GLOBAL_BINDING_REQUIRED",
                        source.type() + ":" + source.code()
                                + " için açık global eşleştirme gereklidir."));
                dependencies.add(new GlobalDependency(
                        source.type(), source.code(), source.provider(), true,
                        null, null, null, false, "Eşleştirme seçilmedi."));
                continue;
            }
            if (binding.mode() == GlobalBindingMode.CREATE_GLOBAL) {
                issues.add(new BundleIssue(
                        source.path(), "CREATE_GLOBAL_CONFIGURATION_REQUIRED",
                        "CREATE_GLOBAL için bağlantı bilgileri ve ayrı güvenli kimlik kurulumu gerekir; paket içeriğinden oluşturulamaz."));
                dependencies.add(new GlobalDependency(
                        source.type(), source.code(), source.provider(), true,
                        binding.mode(), binding.targetUuid(), binding.newCode(), false,
                        "Yönetici yapılandırması gerekli."));
                continue;
            }
            if (binding.targetUuid() == null) {
                issues.add(new BundleIssue(
                        source.path(), "GLOBAL_BINDING_TARGET_REQUIRED",
                        "BIND_EXISTING için targetUuid gereklidir."));
                dependencies.add(new GlobalDependency(
                        source.type(), source.code(), source.provider(), true,
                        binding.mode(), null, null, false, "Hedef seçilmedi."));
                continue;
            }
            GlobalResourceRow resource = repository
                    .findGlobalResource(source.type(), binding.targetUuid())
                    .orElse(null);
            if (resource == null) {
                issues.add(new BundleIssue(
                        source.path(), "GLOBAL_BINDING_NOT_FOUND",
                        "Seçilen global nesne bulunamadı."));
                dependencies.add(new GlobalDependency(
                        source.type(), source.code(), source.provider(), true,
                        binding.mode(), binding.targetUuid(), null, false,
                        "Hedef bulunamadı."));
                continue;
            }
            if (source.provider() != null && resource.provider() != null
                    && !source.provider().equals(resource.provider())) {
                issues.add(new BundleIssue(
                        source.path(), "GLOBAL_BINDING_PROVIDER_MISMATCH",
                        "Kaynak ve hedef teknoloji türü uyuşmuyor."));
                dependencies.add(new GlobalDependency(
                        source.type(), source.code(), source.provider(), true,
                        binding.mode(), resource.uuid(), resource.code(), false,
                        "Teknoloji uyuşmazlığı."));
                continue;
            }
            resolved.put(key, resource);
            String message = sameGlobalIdentity(bundle, source, resource.uuid())
                    ? "Kaynakla aynı global kaynak seçildi; bu eşleştirme fiziksel veri yalıtımı sağlamaz. Çalıştırmadan önce hedef bağlantı ve şemayı doğrulayın."
                    : "Eşleştirildi.";
            dependencies.add(new GlobalDependency(
                    source.type(), source.code(), source.provider(), true,
                    binding.mode(), resource.uuid(), resource.code(), true, message));
        }

        Set<DependencyKey> known = new HashSet<>();
        for (SourceDependency dependency : sourceDependencies(bundle)) {
            known.add(new DependencyKey(dependency.type(), dependency.code()));
        }
        for (DependencyKey supplied : bindings.keySet()) {
            if (!known.contains(supplied)) {
                issues.add(new BundleIssue(
                        "globalBindings", "UNKNOWN_GLOBAL_BINDING",
                        supplied.type() + ":" + supplied.code()
                                + " pakette tanımlı bir bağımlılık değildir."));
            }
        }

        validatePhysicalBindings(bundle, resolved, issues);

        dependencies.sort(Comparator
                .comparing((GlobalDependency item) -> item.type().name())
                .thenComparing(GlobalDependency::sourceCode));

        validateReferenceRemap(bundle, bindings, resolved, issues);

        String digest = planDigest(target, bundle.checksum(), dependencies);
        Map<String, Integer> changes = new LinkedHashMap<>();
        changes.put("folders", validation.counts().folders());
        changes.put("definitions", validation.counts().definitions());
        changes.put("drafts", validation.counts().drafts());
        changes.put("versions", validation.counts().versions());
        changes.put("models", size(bundle, "models"));
        changes.put("submodels", size(bundle, "submodels"));
        changes.put("dataObjects", size(bundle, "dataObjects"));

        TargetImportPlan publicPlan = new TargetImportPlan(
                issues.isEmpty(), target.uuid(), target.version(), bundle.checksum(), digest,
                validation.counts(), Map.copyOf(changes), List.copyOf(dependencies),
                List.copyOf(issues));
        return new PlannedImport(publicPlan, remapTopology(bundle, resolved));
    }

    private void validatePhysicalBindings(
            ProjectBundle bundle, Map<DependencyKey, GlobalResourceRow> resolved,
            List<BundleIssue> issues) {
        if (bundle.topology() == null || bundle.topology().definitions() == null) return;
        JsonNode topology = bundle.topology().definitions();
        for (JsonNode schema : array(topology, "physicalSchemas")) {
            String sourceConnection = schema.path("connectionCode").asText(null);
            if (sourceConnection == null) continue;
            String sourceSchema = schema.path("code").asText();
            GlobalResourceRow targetConnection = resolved.get(new DependencyKey(
                    GlobalResourceType.CONNECTION, sourceConnection));
            GlobalResourceRow targetSchema = resolved.get(new DependencyKey(
                    GlobalResourceType.PHYSICAL_SCHEMA,
                    sourceConnection + "::" + sourceSchema));
            if (targetConnection != null && targetSchema != null
                    && !targetConnection.code().equals(targetSchema.parentCode())) {
                issues.add(new BundleIssue("topology.physicalSchemas",
                        "PHYSICAL_SCHEMA_CONNECTION_MISMATCH",
                        "Seçilen fiziksel şema seçilen hedef bağlantıya ait değil."));
            }
        }
        for (JsonNode object : array(topology, "dataObjects")) {
            if (!object.hasNonNull("sourceSchemaSnapshotUuid")) continue;
            String sourceConnection = object.path("sourceConnectionCode").asText(null);
            String sourceSchema = object.path("sourcePhysicalSchemaCode").asText(null);
            if (sourceConnection == null || sourceSchema == null
                    || !resolved.containsKey(new DependencyKey(
                            GlobalResourceType.CONNECTION, sourceConnection))
                    || !resolved.containsKey(new DependencyKey(
                            GlobalResourceType.PHYSICAL_SCHEMA,
                            sourceConnection + "::" + sourceSchema))) {
                issues.add(new BundleIssue("topology.dataObjects",
                        "SNAPSHOT_BINDING_REQUIRED",
                        "Şema görüntüsü için hedef bağlantı ve fiziksel şema eşleştirmesi gereklidir."));
            }
        }
    }

    private Iterable<JsonNode> array(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        return value.isArray() ? value : List.of();
    }

    private boolean sameGlobalIdentity(
            ProjectBundle bundle, SourceDependency source, UUID targetUuid) {
        String arrayName = switch (source.type()) {
            case LOGICAL_SCHEMA -> "logicalSchemas";
            case ENVIRONMENT -> "environments";
            default -> null;
        };
        if (arrayName == null || bundle.topology() == null
                || bundle.topology().definitions() == null) return false;
        JsonNode values = bundle.topology().definitions().path(arrayName);
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (source.code().equals(value.path("code").asString())
                    && targetUuid.toString().equals(value.path("sourceUuid").asString())) {
                return true;
            }
        }
        return false;
    }

    private Map<DependencyKey, GlobalBinding> indexBindings(
            List<GlobalBinding> requested, List<BundleIssue> issues) {
        Map<DependencyKey, GlobalBinding> result = new HashMap<>();
        if (requested == null) return result;
        for (int index = 0; index < requested.size(); index++) {
            GlobalBinding binding = requested.get(index);
            if (binding == null || binding.type() == null
                    || binding.sourceCode() == null || binding.sourceCode().isBlank()
                    || binding.mode() == null) {
                issues.add(new BundleIssue(
                        "globalBindings[" + index + "]", "INVALID_GLOBAL_BINDING",
                        "Tür, kaynak kodu ve bağlama modu gereklidir."));
                continue;
            }
            DependencyKey key = new DependencyKey(binding.type(), binding.sourceCode());
            if (result.putIfAbsent(key, binding) != null) {
                issues.add(new BundleIssue(
                        "globalBindings[" + index + "]", "DUPLICATE_GLOBAL_BINDING",
                        "Aynı global bağımlılık birden fazla eşleştirilemez."));
            }
        }
        return result;
    }

    /**
     * Global dependencies a bundle requires an explicit binding for, deduplicated by
     * (type, code). Topology (connections/schemas/environments) is scanned first;
     * publication recipes then contribute their environmentCode as an ENVIRONMENT
     * dependency even when no topology item references that environment, so an
     * environment used only by a publication still requires BIND_EXISTING.
     */
    private List<SourceDependency> sourceDependencies(ProjectBundle bundle) {
        if (bundle == null) return List.of();
        Map<DependencyKey, SourceDependency> byKey = new LinkedHashMap<>();
        if (bundle.topology() != null && bundle.topology().definitions() != null
                && bundle.topology().definitions().isObject()) {
            JsonNode topology = bundle.topology().definitions();
            List<SourceDependency> topologyDependencies = new ArrayList<>();
            addDependencies(topologyDependencies, topology, "connections", GlobalResourceType.CONNECTION);
            addDependencies(topologyDependencies, topology, "physicalSchemas", GlobalResourceType.PHYSICAL_SCHEMA);
            addDependencies(topologyDependencies, topology, "logicalSchemas", GlobalResourceType.LOGICAL_SCHEMA);
            addDependencies(topologyDependencies, topology, "environments", GlobalResourceType.ENVIRONMENT);
            for (SourceDependency dependency : topologyDependencies) {
                byKey.putIfAbsent(new DependencyKey(dependency.type(), dependency.code()), dependency);
            }
        }
        List<PublicationEntry> publications = bundle.publications() == null
                ? List.of() : bundle.publications();
        for (int index = 0; index < publications.size(); index++) {
            PublicationEntry publication = publications.get(index);
            if (publication == null || publication.environmentCode() == null) continue;
            DependencyKey key = new DependencyKey(GlobalResourceType.ENVIRONMENT, publication.environmentCode());
            byKey.putIfAbsent(key, new SourceDependency(
                    GlobalResourceType.ENVIRONMENT, publication.environmentCode(), null,
                    "publications[" + index + "].environmentCode"));
        }
        return List.copyOf(byKey.values());
    }

    private void addDependencies(
            List<SourceDependency> target, JsonNode topology,
            String arrayName, GlobalResourceType type) {
        JsonNode values = topology.get(arrayName);
        if (values == null || !values.isArray()) return;
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            String code = dependencyCode(value, type);
            String provider = value.hasNonNull("provider")
                    ? value.path("provider").asString() : null;
            target.add(new SourceDependency(
                    type, code, provider, "topology." + arrayName + "[" + index + "]"));
        }
    }

    private JsonNode remapTopology(
            ProjectBundle bundle, Map<DependencyKey, GlobalResourceRow> resolved) {
        ObjectNode copy = (ObjectNode) bundle.topology().definitions().deepCopy();
        Map<GlobalResourceType, Map<String, String>> codes = new HashMap<>();
        resolved.forEach((key, value) -> codes
                .computeIfAbsent(key.type(), ignored -> new HashMap<>())
                .put(key.code(), value.code()));
        rewriteArrayCodes(copy, "connections", GlobalResourceType.CONNECTION, codes);
        rewriteArrayCodes(copy, "physicalSchemas", GlobalResourceType.PHYSICAL_SCHEMA, codes);
        rewriteArrayCodes(copy, "logicalSchemas", GlobalResourceType.LOGICAL_SCHEMA, codes);
        rewriteArrayCodes(copy, "environments", GlobalResourceType.ENVIRONMENT, codes);
        rewriteFields(copy, "physicalSchemas", Map.of(
                "connectionCode", GlobalResourceType.CONNECTION), codes);
        rewriteFields(copy, "schemaBindings", Map.of(
                "connectionCode", GlobalResourceType.CONNECTION,
                "physicalSchemaCode", GlobalResourceType.PHYSICAL_SCHEMA,
                "logicalSchemaCode", GlobalResourceType.LOGICAL_SCHEMA,
                "environmentCode", GlobalResourceType.ENVIRONMENT), codes);
        rewriteFields(copy, "models", Map.of(
                "logicalSchemaCode", GlobalResourceType.LOGICAL_SCHEMA,
                "reverseEnvironmentCode", GlobalResourceType.ENVIRONMENT), codes);
        rewriteFields(copy, "dataObjects", Map.of(
                "sourceConnectionCode", GlobalResourceType.CONNECTION,
                "sourcePhysicalSchemaCode", GlobalResourceType.PHYSICAL_SCHEMA), codes);
        return copy;
    }

    private void rewriteArrayCodes(
            ObjectNode topology, String name, GlobalResourceType type,
            Map<GlobalResourceType, Map<String, String>> codes) {
        JsonNode values = topology.get(name);
        if (!(values instanceof ArrayNode array)) return;
        for (JsonNode value : array) {
            if (value instanceof ObjectNode object && object.hasNonNull("code")) {
                String mapped = codes.getOrDefault(type, Map.of())
                        .get(dependencyCode(object, type));
                if (mapped != null) object.put("code", mapped);
            }
        }
    }

    private void rewriteFields(
            ObjectNode topology, String arrayName,
            Map<String, GlobalResourceType> fields,
            Map<GlobalResourceType, Map<String, String>> codes) {
        JsonNode values = topology.get(arrayName);
        if (!(values instanceof ArrayNode array)) return;
        for (JsonNode value : array) {
            if (!(value instanceof ObjectNode object)) continue;
            String parentCode = object.path(arrayName.equals("dataObjects")
                    ? "sourceConnectionCode" : "connectionCode").asText(null);
            fields.forEach((field, type) -> {
                if (!object.hasNonNull(field)) return;
                String sourceCode = object.path(field).asString();
                if (type == GlobalResourceType.PHYSICAL_SCHEMA && parentCode != null) {
                    sourceCode = parentCode + "::" + sourceCode;
                }
                String mapped = codes.getOrDefault(type, Map.of())
                        .get(sourceCode);
                if (mapped != null) object.put(field, mapped);
            });
        }
    }

    private String dependencyCode(JsonNode value, GlobalResourceType type) {
        String code = value.path("code").asString();
        if (type == GlobalResourceType.PHYSICAL_SCHEMA) {
            String connectionCode = value.path("connectionCode").asText(null);
            if (connectionCode != null && !connectionCode.isBlank()) {
                return connectionCode + "::" + code;
            }
        }
        return code;
    }

    private String dependencyFieldCode(
            JsonNode value, String field, GlobalResourceType type) {
        String code = value.path(field).asString();
        if (type == GlobalResourceType.PHYSICAL_SCHEMA) {
            String connectionCode = value.path("connectionCode").asText(null);
            if (connectionCode != null && !connectionCode.isBlank()) {
                return connectionCode + "::" + code;
            }
        }
        return code;
    }

    private void validateReferenceRemap(
            ProjectBundle bundle,
            Map<DependencyKey, GlobalBinding> bindings,
            Map<DependencyKey, GlobalResourceRow> resolved,
            List<BundleIssue> issues) {
        Map<UUID, UUID> definitionUuidBySource = new java.util.HashMap<>();
        Map<UUID, UUID> versionUuidBySource = new java.util.HashMap<>();
        Map<UUID, UUID> logicalSchemaUuidBySource = new java.util.HashMap<>();
        Map<UUID, UUID> environmentUuidBySource = new java.util.HashMap<>();
        Map<UUID, UUID> dataObjectUuidBySource = new java.util.HashMap<>();
        Map<UUID, UUID> schemaSnapshotUuidBySource = new java.util.HashMap<>();
        Map<UUID, UUID> schemaSnapshotUuidByDataObjectSource = new java.util.HashMap<>();

        for (DefinitionEntry definition : bundle.definitions()) {
            if (definition.sourceUuid() != null) {
                definitionUuidBySource.put(definition.sourceUuid(), definition.sourceUuid());
            }
            for (VersionEntry version : safeList(definition.versions())) {
                if (version.sourceUuid() != null) {
                    versionUuidBySource.put(version.sourceUuid(), version.sourceUuid());
                }
            }
        }

        JsonNode topology = bundle.topology().definitions();
        JsonNode logicalSchemas = topology.path("logicalSchemas");
        if (logicalSchemas.isArray()) {
            for (JsonNode item : logicalSchemas) {
                String code = item.path("code").asString();
                UUID sourceUuid = item.hasNonNull("sourceUuid")
                        ? UUID.fromString(item.get("sourceUuid").asString()) : null;
                DependencyKey key = new DependencyKey(GlobalResourceType.LOGICAL_SCHEMA, code);
                GlobalBinding binding = bindings.get(key);
                GlobalResourceRow resource = resolved.get(key);
                if (sourceUuid != null && binding != null && resource != null) {
                    logicalSchemaUuidBySource.put(sourceUuid, resource.uuid());
                }
            }
        }

        JsonNode environments = topology.path("environments");
        if (environments.isArray()) {
            for (JsonNode item : environments) {
                if (!item.hasNonNull("sourceUuid")) continue;
                UUID sourceUuid = UUID.fromString(item.get("sourceUuid").asString());
                DependencyKey key = new DependencyKey(
                        GlobalResourceType.ENVIRONMENT, item.path("code").asString());
                GlobalResourceRow resource = resolved.get(key);
                if (resource != null && bindings.containsKey(key)) {
                    environmentUuidBySource.put(sourceUuid, resource.uuid());
                }
            }
        }

        JsonNode dataObjects = topology.path("dataObjects");
        if (dataObjects.isArray()) {
            for (JsonNode item : dataObjects) {
                UUID sourceUuid = item.hasNonNull("sourceUuid")
                        ? UUID.fromString(item.get("sourceUuid").asString()) : null;
                if (sourceUuid != null) {
                    dataObjectUuidBySource.put(sourceUuid, sourceUuid);
                }
                UUID snapshotUuid = item.hasNonNull("sourceSchemaSnapshotUuid")
                        ? UUID.fromString(item.get("sourceSchemaSnapshotUuid").asString()) : null;
                if (snapshotUuid != null
                        && item.hasNonNull("sourcePhysicalSchemaCode")
                        && item.hasNonNull("sourceConnectionCode")
                        && item.hasNonNull("sourceSnapshotFingerprint")) {
                    // Planning has no target UUID yet. Presence of a complete
                    // portable snapshot is enough to prove that import can
                    // create and remap it; the real UUID is assigned during import.
                    schemaSnapshotUuidBySource.put(snapshotUuid, snapshotUuid);
                    schemaSnapshotUuidByDataObjectSource.put(sourceUuid, snapshotUuid);
                }
            }
        }

        ReferenceContext ctx = new ReferenceContext(
                definitionUuidBySource,
                versionUuidBySource,
                logicalSchemaUuidBySource,
                environmentUuidBySource,
                dataObjectUuidBySource,
                schemaSnapshotUuidBySource,
                schemaSnapshotUuidByDataObjectSource,
                issues);

        BundleReferenceMapper mapper = new BundleReferenceMapper(objectMapper);
        for (DefinitionEntry definition : bundle.definitions()) {
            DefinitionType type = definition.type();
            if (definition.draft() != null) {
                mapper.remapDefinitionContent(
                        definition.draft().content(), type,
                        definition.draft().schemaVersion(), ctx);
            }
            for (VersionEntry version : safeList(definition.versions())) {
                mapper.remapDefinitionContent(
                        version.content(), type, version.schemaVersion(), ctx);
            }
        }
    }

    private List<VersionEntry> safeList(List<VersionEntry> list) {
        return list == null ? List.of() : list;
    }

    private int size(ProjectBundle bundle, String name) {
        JsonNode value = bundle.topology().definitions().get(name);
        return value != null && value.isArray() ? value.size() : 0;
    }

    private String planDigest(
            ProjectRow target, String bundleChecksum,
            List<GlobalDependency> dependencies) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("targetProjectUuid", target.uuid().toString());
        payload.put("targetVersion", target.version());
        payload.put("bundleChecksum", bundleChecksum);
        ArrayNode bindings = payload.putArray("globalBindings");
        dependencies.stream()
                .sorted(Comparator.comparing((GlobalDependency item) -> item.type().name())
                        .thenComparing(GlobalDependency::sourceCode))
                .forEach(item -> {
                    ObjectNode value = bindings.addObject();
                    value.put("type", item.type().name());
                    value.put("sourceCode", item.sourceCode());
                    if (item.mode() != null) value.put("mode", item.mode().name());
                    if (item.targetUuid() != null) value.put("targetUuid", item.targetUuid().toString());
                    if (item.targetCode() != null) value.put("targetCode", item.targetCode());
                });
        return sha256(canonical(payload).toString());
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            names.sort(String::compareTo);
            names.forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node.deepCopy();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record PlannedImport(TargetImportPlan plan, JsonNode resolvedTopology) {
    }

    private record DependencyKey(GlobalResourceType type, String code) {
    }

    private record SourceDependency(
            GlobalResourceType type, String code, String provider, String path) {
    }
}

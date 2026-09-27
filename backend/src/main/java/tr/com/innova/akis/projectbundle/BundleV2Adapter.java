package tr.com.innova.akis.projectbundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.BundleReferenceMapper.ReferenceContext;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleIssue;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DefinitionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProducerEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.PublicationEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ScheduleEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.VersionEntry;

/**
 * Converts a safely resolvable v2 project bundle into the internal v3 portable
 * target import plan representation. Legacy source-owned UUIDs are validated
 * against the bundle's own definitions/versions, explicit global bindings and
 * the portable topology. Unresolvable references are reported as
 * {@code UNRESOLVED_LEGACY_REFERENCE} so that callers can guide users to
 * re-export from the source project using v3.
 */
final class BundleV2Adapter {

    static final String UNRESOLVED_LEGACY_REFERENCE = "UNRESOLVED_LEGACY_REFERENCE";
    private static final String REEXPORT_GUIDANCE =
            "Bu miras referansı v3 aktarım mekanizmasıyla çözümlenemedi; "
                    + "lütfen kaynak projeden v3 formatında yeniden dışa aktarın.";
    private static final ProducerEntry LEGACY_PRODUCER =
            new ProducerEntry("akis-backend", "0.1.0-SNAPSHOT", "development");

    private final ObjectMapper objectMapper;
    private final BundleReferenceMapper referenceMapper;

    BundleV2Adapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.referenceMapper = new BundleReferenceMapper(objectMapper);
    }

    record AdaptationResult(ProjectBundle bundle, List<BundleIssue> issues) {
    }

    AdaptationResult adapt(ProjectBundle bundle, List<GlobalBinding> globalBindings) {
        if (bundle == null || bundle.formatVersion() != ProjectBundleModels.LEGACY_FORMAT_VERSION) {
            return new AdaptationResult(bundle, List.of());
        }

        List<BundleIssue> issues = new ArrayList<>();
        JsonNode topology = bundle.topology() == null ? null : bundle.topology().definitions();

        Map<UUID, UUID> definitionUuidBySource = collectDefinitionUuids(bundle);
        Map<UUID, UUID> versionUuidBySource = collectVersionUuids(bundle);
        Map<String, UUID> logicalSchemaBindings = new HashMap<>();
        Map<String, UUID> environmentBindings = new HashMap<>();
        for (GlobalBinding binding : safeList(globalBindings)) {
            if (binding != null
                    && binding.mode() == GlobalBindingMode.BIND_EXISTING
                    && binding.targetUuid() != null) {
                if (binding.type() == GlobalResourceType.LOGICAL_SCHEMA) {
                    logicalSchemaBindings.put(binding.sourceCode(), binding.targetUuid());
                }
                else if (binding.type() == GlobalResourceType.ENVIRONMENT) {
                    environmentBindings.put(binding.sourceCode(), binding.targetUuid());
                }
            }
        }

        Map<UUID, UUID> logicalSchemaUuidBySource = new HashMap<>();
        Map<UUID, UUID> environmentUuidBySource = new HashMap<>();
        Map<UUID, UUID> dataObjectUuidBySource = new HashMap<>();
        Map<UUID, UUID> schemaSnapshotUuidBySource = new HashMap<>();
        Map<UUID, UUID> schemaSnapshotUuidByDataObjectSource = new HashMap<>();

        scanTopology(
                topology, logicalSchemaBindings, environmentBindings,
                logicalSchemaUuidBySource, environmentUuidBySource,
                dataObjectUuidBySource, schemaSnapshotUuidBySource,
                schemaSnapshotUuidByDataObjectSource, issues);

        ReferenceContext ctx = new ReferenceContext(
                definitionUuidBySource,
                versionUuidBySource,
                logicalSchemaUuidBySource,
                environmentUuidBySource,
                dataObjectUuidBySource,
                schemaSnapshotUuidBySource,
                schemaSnapshotUuidByDataObjectSource,
                new ArrayList<>());

        scanDefinitionContent(bundle, ctx);
        for (BundleIssue issue : ctx.issues()) {
            issues.add(new BundleIssue(
                    issue.path(), UNRESOLVED_LEGACY_REFERENCE,
                    issue.message() + " " + REEXPORT_GUIDANCE));
        }

        ProjectBundle normalized = normalize(bundle);
        return new AdaptationResult(normalized, List.copyOf(issues));
    }

    private void scanTopology(
            JsonNode topology,
            Map<String, UUID> logicalSchemaBindings,
            Map<String, UUID> environmentBindings,
            Map<UUID, UUID> logicalSchemaUuidBySource,
            Map<UUID, UUID> environmentUuidBySource,
            Map<UUID, UUID> dataObjectUuidBySource,
            Map<UUID, UUID> schemaSnapshotUuidBySource,
            Map<UUID, UUID> schemaSnapshotUuidByDataObjectSource,
            List<BundleIssue> issues) {
        if (topology == null || !topology.isObject()) {
            return;
        }

        JsonNode logicalSchemas = topology.path("logicalSchemas");
        if (logicalSchemas.isArray()) {
            for (int index = 0; index < logicalSchemas.size(); index++) {
                JsonNode item = logicalSchemas.get(index);
                UUID sourceUuid = parseUuid(item.path("sourceUuid").asText(null));
                String code = item.path("code").asText(null);
                if (sourceUuid == null) {
                    continue;
                }
                UUID targetUuid = code == null ? null : logicalSchemaBindings.get(code);
                if (targetUuid != null) {
                    logicalSchemaUuidBySource.put(sourceUuid, targetUuid);
                }
                else {
                    issues.add(new BundleIssue(
                            "topology.definitions.logicalSchemas[" + index + "].sourceUuid",
                            UNRESOLVED_LEGACY_REFERENCE,
                            "Mantıksal şema kaynak UUID'si açık bir global eşleştirme ile "
                                    + "çözümlenemedi: " + sourceUuid + ". " + REEXPORT_GUIDANCE));
                }
            }
        }

        JsonNode environments = topology.path("environments");
        if (environments.isArray()) {
            for (int index = 0; index < environments.size(); index++) {
                JsonNode item = environments.get(index);
                UUID sourceUuid = parseUuid(item.path("sourceUuid").asText(null));
                String code = item.path("code").asText(null);
                if (sourceUuid == null) {
                    continue;
                }
                UUID targetUuid = code == null ? null : environmentBindings.get(code);
                if (targetUuid != null) {
                    environmentUuidBySource.put(sourceUuid, targetUuid);
                }
                else {
                    issues.add(new BundleIssue(
                            "topology.definitions.environments[" + index + "].sourceUuid",
                            UNRESOLVED_LEGACY_REFERENCE,
                            "Ortam kaynak UUID'si açık bir global eşleştirme ile "
                                    + "çözümlenemedi: " + sourceUuid + ". " + REEXPORT_GUIDANCE));
                }
            }
        }

        JsonNode dataObjects = topology.path("dataObjects");
        if (dataObjects.isArray()) {
            for (int index = 0; index < dataObjects.size(); index++) {
                JsonNode item = dataObjects.get(index);
                UUID sourceUuid = parseUuid(item.path("sourceUuid").asText(null));
                if (sourceUuid != null) {
                    dataObjectUuidBySource.put(sourceUuid, sourceUuid);
                }
                UUID snapshotUuid = parseUuid(item.path("sourceSchemaSnapshotUuid").asText(null));
                if (snapshotUuid != null
                        && item.hasNonNull("sourcePhysicalSchemaCode")
                        && item.hasNonNull("sourceConnectionCode")
                        && item.hasNonNull("sourceSnapshotFingerprint")) {
                    schemaSnapshotUuidBySource.put(snapshotUuid, snapshotUuid);
                    if (sourceUuid != null) {
                        schemaSnapshotUuidByDataObjectSource.put(sourceUuid, snapshotUuid);
                    }
                }
            }
        }
    }

    private void scanDefinitionContent(ProjectBundle bundle, ReferenceContext ctx) {
        for (DefinitionEntry definition : safeList(bundle.definitions())) {
            if (definition == null) {
                continue;
            }
            DefinitionType type = definition.type();
            if (definition.draft() != null && definition.draft().content() != null) {
                referenceMapper.remapDefinitionContent(
                        definition.draft().content(), type,
                        definition.draft().schemaVersion(), ctx);
            }
            for (VersionEntry version : safeList(definition.versions())) {
                if (version != null && version.content() != null) {
                    referenceMapper.remapDefinitionContent(
                            version.content(), type, version.schemaVersion(), ctx);
                }
            }
        }
    }

    private ProjectBundle normalize(ProjectBundle bundle) {
        List<PublicationEntry> publications =
                bundle.publications() == null ? List.of() : bundle.publications();
        List<ScheduleEntry> schedules =
                bundle.schedules() == null ? List.of() : bundle.schedules();
        return new ProjectBundle(
                bundle.format(),
                ProjectBundleModels.FORMAT_VERSION,
                ProjectBundleModels.SCHEMA_VERSION,
                null,
                bundle.exportedAt(),
                bundle.project(),
                bundle.folders(),
                bundle.definitions(),
                bundle.topology(),
                LEGACY_PRODUCER,
                ProjectBundleModels.INCLUDED_SECTIONS,
                publications,
                schedules);
    }

    private Map<UUID, UUID> collectDefinitionUuids(ProjectBundle bundle) {
        Map<UUID, UUID> result = new HashMap<>();
        for (DefinitionEntry definition : safeList(bundle.definitions())) {
            if (definition != null && definition.sourceUuid() != null) {
                result.put(definition.sourceUuid(), definition.sourceUuid());
            }
        }
        return result;
    }

    private Map<UUID, UUID> collectVersionUuids(ProjectBundle bundle) {
        Map<UUID, UUID> result = new HashMap<>();
        for (DefinitionEntry definition : safeList(bundle.definitions())) {
            if (definition == null) {
                continue;
            }
            for (VersionEntry version : safeList(definition.versions())) {
                if (version != null && version.sourceUuid() != null) {
                    result.put(version.sourceUuid(), version.sourceUuid());
                }
            }
        }
        return result;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}

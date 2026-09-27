package tr.com.innova.akis.projectbundle;

import static tr.com.innova.akis.projectbundle.ProjectBundleModels.FORMAT;
import static tr.com.innova.akis.projectbundle.ProjectBundleModels.FORMAT_VERSION;
import static tr.com.innova.akis.projectbundle.ProjectBundleModels.INCLUDED_SECTIONS;
import static tr.com.innova.akis.projectbundle.ProjectBundleModels.LEGACY_FORMAT_VERSION;
import static tr.com.innova.akis.projectbundle.ProjectBundleModels.LEGACY_SCHEMA_VERSION;
import static tr.com.innova.akis.projectbundle.ProjectBundleModels.SCHEMA_VERSION;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tr.com.innova.akis.projectbundle.BundleReferenceMapper.ReferenceContext;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleCounts;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleIssue;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ConflictPolicy;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DefinitionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DraftEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.FolderEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ImportResult;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProducerEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.PublicationEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ScheduleEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportPlan;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportRequest;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportResult;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ValidationReport;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.VersionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DefinitionRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DraftRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ExportSnapshot;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.FolderRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.PublicationRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ScheduleRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.VersionRow;

@Service
public class ProjectBundleService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PRODUCER_VERSION = Pattern.compile(
            "\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?");
    private static final Pattern PRODUCER_COMMIT = Pattern.compile(
            "(?:[0-9a-fA-F]{7,64}|development|unknown)");
    static final int MAX_FOLDERS = 10_000;
    static final int MAX_DEFINITIONS = 20_000;
    static final int MAX_VERSIONS = 100_000;
    static final int MAX_FOLDER_DEPTH = 100;
    static final int MAX_JSON_DEPTH = 100;
    static final int MAX_JSON_NODES = 100_000;
    static final int MAX_BUNDLE_JSON_NODES = 1_000_000;
    static final int MAX_PUBLICATIONS = 10_000;
    static final int MAX_SCHEDULES = 10_000;
    private static final Set<String> FOLDER_STATUSES = Set.of("AKTIF", "ARSIV");
    private static final Set<String> DEFINITION_STATUSES = Set.of("TASLAK", "AKTIF", "ARSIV");
    private static final Set<String> CONFLICT_POLICIES = Set.of("SKIP", "QUEUE");
    private static final Set<String> MISFIRE_POLICIES = Set.of("SKIP", "RUN_ONCE");
    private static final Set<String> PUBLICATION_POLICIES = Set.of("LATEST_ACTIVE", "PINNED");

    private final ProjectBundleRepository repository;
    private final DefinitionContentValidator contentValidator;
    private final SecretValueSanitizer secretSanitizer;
    private final ObjectMapper objectMapper;
    private final BundleImportPlanner importPlanner;
    private final BundleReferenceMapper referenceMapper;
    private final BundleV2Adapter v2Adapter;
    private final PendingRecipeImportWriter pendingRecipeImportWriter;

    public ProjectBundleService(
            ProjectBundleRepository repository,
            DefinitionContentValidator contentValidator,
            SecretValueSanitizer secretSanitizer,
            ObjectMapper objectMapper) {
        this(repository, contentValidator, secretSanitizer, objectMapper, null);
    }

    @Autowired
    public ProjectBundleService(
            ProjectBundleRepository repository,
            DefinitionContentValidator contentValidator,
            SecretValueSanitizer secretSanitizer,
            ObjectMapper objectMapper,
            PendingRecipeImportWriter pendingRecipeImportWriter) {
        this.repository = repository;
        this.contentValidator = contentValidator;
        this.secretSanitizer = secretSanitizer;
        this.objectMapper = objectMapper;
        this.importPlanner = repository == null || objectMapper == null
                ? null : new BundleImportPlanner(repository, objectMapper);
        this.referenceMapper = objectMapper == null
                ? null : new BundleReferenceMapper(objectMapper);
        this.v2Adapter = objectMapper == null
                ? null : new BundleV2Adapter(objectMapper);
        this.pendingRecipeImportWriter = pendingRecipeImportWriter;
    }

    private ProjectBundle adaptIfLegacy(ProjectBundle bundle, List<GlobalBinding> globalBindings) {
        if (bundle == null || bundle.formatVersion() != LEGACY_FORMAT_VERSION) {
            return bundle;
        }
        BundleV2Adapter.AdaptationResult result = v2Adapter.adapt(bundle, globalBindings);
        if (!result.issues().isEmpty()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BUNDLE_IMPORT_REFERENCE_UNRESOLVED",
                    "Bundle içeriğinde çözülemeyen miras referanslar var.",
                    new ValidationReport(false, counts(result.bundle()), result.issues()));
        }
        return withChecksum(result.bundle(), checksum(result.bundle()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectBundle exportBundle(UUID projectUuid) {
        ExportSnapshot snapshot;
        try {
            snapshot = repository.loadSnapshot(projectUuid);
        }
        catch (java.util.NoSuchElementException exception) {
            throw new ProjectBundleException(
                    HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Proje bulunamadı.", null);
        }

        Map<Long, FolderRow> foldersById = snapshot.folders().stream()
                .collect(Collectors.toMap(FolderRow::id, Function.identity()));
        Map<Long, String> pathsByFolderId = new HashMap<>();
        for (FolderRow folder : snapshot.folders()) {
            resolveFolderPath(folder, foldersById, pathsByFolderId);
        }

        List<FolderEntry> folders = snapshot.folders().stream()
                .map(folder -> new FolderEntry(
                        pathsByFolderId.get(folder.id()),
                        folder.parentId() == null ? null : pathsByFolderId.get(folder.parentId()),
                        folder.code(), folder.status(), folder.name(), folder.description()))
                .sorted(Comparator.comparing(FolderEntry::path))
                .toList();

        Map<Long, DraftRow> draftsByDefinition = snapshot.drafts().stream()
                .collect(Collectors.toMap(DraftRow::definitionId, Function.identity()));
        Map<Long, List<VersionRow>> versionsByDefinition = snapshot.versions().stream()
                .collect(Collectors.groupingBy(VersionRow::definitionId));

        List<DefinitionEntry> definitions = snapshot.definitions().stream()
                .map(definition -> exportDefinition(
                        definition, pathsByFolderId, draftsByDefinition,
                        versionsByDefinition.getOrDefault(definition.id(), List.of())))
                .sorted(Comparator.comparing((DefinitionEntry item) -> item.type().name())
                        .thenComparing(DefinitionEntry::code))
                .toList();

        List<PublicationEntry> publications = snapshot.publications().stream()
                .map(row -> new PublicationEntry(
                        row.definitionType(), row.definitionCode(),
                        row.definitionVersionNumber(), row.environmentCode()))
                .sorted(Comparator.comparing((PublicationEntry item) -> item.definitionType().name())
                        .thenComparing(PublicationEntry::definitionCode)
                        .thenComparingInt(PublicationEntry::definitionVersionNumber)
                        .thenComparing(PublicationEntry::environmentCode))
                .toList();
        List<ScheduleEntry> schedules = snapshot.schedules().stream()
                .map(row -> new ScheduleEntry(
                        row.code(), row.name(), row.cronExpression(), row.timeZone(),
                        row.conflictPolicy(), row.misfirePolicy(), row.publicationPolicy(),
                        new PublicationEntry(
                                row.selectedDefinitionType(), row.selectedDefinitionCode(),
                                row.selectedDefinitionVersionNumber(), row.selectedEnvironmentCode()),
                        row.startsAt(), row.endsAt()))
                .sorted(Comparator.comparing(ScheduleEntry::code))
                .toList();

        var project = snapshot.project();
        ProjectBundle withoutChecksum = new ProjectBundle(
                FORMAT, FORMAT_VERSION, SCHEMA_VERSION, null, OffsetDateTime.now(),
                new ProjectEntry(
                        project.code(), project.status(), project.name(), project.description()),
                folders, definitions, new TopologyEntry(
                        true, repository.loadPortableTopology(project.id())),
                producer(), INCLUDED_SECTIONS, publications, schedules);
        assertNoSecretValues(withoutChecksum);
        List<BundleIssue> exportIssues = new ArrayList<>();
        validateDocument(withoutChecksum, exportIssues);
        exportIssues.removeIf(issue -> "INVALID_BUNDLE_CHECKSUM".equals(issue.code()));
        if (!exportIssues.isEmpty()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "BUNDLE_EXPORT_INVALID",
                    "Project metadata cannot be represented as a valid bundle.",
                    new ValidationReport(false, counts(withoutChecksum), List.copyOf(exportIssues)));
        }
        return withChecksum(withoutChecksum, checksum(withoutChecksum));
    }

    public ValidationReport validate(ProjectBundle bundle) {
        List<BundleIssue> issues = new ArrayList<>();
        validateDocument(bundle, issues);
        return report(bundle, issues);
    }

    @Transactional
    public ImportResult importBundle(
            ProjectBundle bundle, ConflictPolicy conflictPolicy, boolean dryRun) {
        ConflictPolicy policy = conflictPolicy == null ? ConflictPolicy.FAIL : conflictPolicy;
        if (policy == ConflictPolicy.SKIP || policy == ConflictPolicy.NEW_VERSION) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_CONFLICT_POLICY",
                    "SKIP and NEW_VERSION are reserved for a later bundle version.", null);
        }
        bundle = adaptIfLegacy(bundle, List.of());
        ValidationReport validation = validate(bundle);
        if (!validation.valid()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BUNDLE_VALIDATION_FAILED",
                    "Bundle validation failed.",
                    validation);
        }
        List<BundleIssue> referenceIssues = validateStandaloneImportReferences(bundle);
        if (!referenceIssues.isEmpty()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BUNDLE_IMPORT_REFERENCE_UNRESOLVED",
                    "Bundle içeriğinde çözülemeyen referanslar var.",
                    new ValidationReport(false, validation.counts(), referenceIssues));
        }
        if (!bundle.publications().isEmpty() || !bundle.schedules().isEmpty()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "TARGET_IMPORT_REQUIRED",
                    "Yayın ve zamanlama tarifleri eski içe aktarım yolunda korunamaz; "
                            + "boş hedef projeye planlı içe aktarımı kullanın.", null);
        }
        if (dryRun) {
            String targetCode = resolveTargetProjectCode(bundle.project().code(), policy);
            return new ImportResult(
                    false, true, targetCode, null, validation.counts());
        }

        repository.lockProjectCodeNamespace();
        String targetCode = resolveTargetProjectCode(bundle.project().code(), policy);

        UUID projectUuid = UUID.randomUUID();
        var project = repository.insertProject(
                projectUuid, targetCode, bundle.project().status(),
                bundle.project().name(), bundle.project().description());

        var planned = importPlanner.plan(project, bundle, List.of(), validation);
        if (!planned.plan().valid()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "BUNDLE_IMPORT_PLAN_INVALID",
                    "Eski içe aktarım yolu açık global eşleştirme gerektiren paketi uygulayamaz.",
                    new ValidationReport(false, validation.counts(), planned.plan().issues()));
        }
        importContent(project, bundle, planned.resolvedTopology(), List.of());
        return new ImportResult(
                true, false, project.code(), projectUuid, validation.counts());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TargetImportPlan planTargetImport(
            UUID targetProjectUuid,
            ProjectBundle bundle,
            List<GlobalBinding> globalBindings) {
        var target = repository.findProject(targetProjectUuid)
                .orElseThrow(() -> new ProjectBundleException(
                        HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                        "Hedef proje bulunamadı.", null));
        bundle = adaptIfLegacy(bundle, globalBindings);
        ValidationReport validation = validate(bundle);
        return importPlanner.plan(target, bundle, globalBindings, validation).plan();
    }

    @Transactional
    public TargetImportResult importIntoTarget(
            UUID targetProjectUuid,
            TargetImportRequest request,
            String idempotencyKey,
            long actorId) {
        String safeKey = requireIdempotencyKey(idempotencyKey);
        if (request == null || request.bundle() == null) {
            throw new ProjectBundleException(
                    HttpStatus.BAD_REQUEST, "BUNDLE_REQUIRED",
                    "İçe aktarılacak proje paketi gereklidir.", null);
        }

        ProjectBundle bundle = adaptIfLegacy(request.bundle(), request.globalBindings());
        ValidationReport validation = validate(bundle);
        if (!validation.valid()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "BUNDLE_VALIDATION_FAILED",
                    "Bundle validation failed.", validation);
        }

        var target = lockTarget(targetProjectUuid);
        var existing = repository.findImportReceipt(target.id(), safeKey);
        if (existing.isPresent()) {
            var receipt = existing.orElseThrow();
            if (!receipt.bundleChecksum().equals(bundle.checksum())
                || !receipt.planDigest().equals(request.planDigest())) {
                throw new ProjectBundleException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key farklı bir proje paketi için daha önce kullanılmış.", null);
            }
            return receiptResult(targetProjectUuid, receipt.result(), true);
        }

        if (request.targetVersion() != target.version()) {
            throw new ProjectBundleException(
                    HttpStatus.CONFLICT, "TARGET_PROJECT_VERSION_CHANGED",
                    "Hedef proje plan oluşturulduktan sonra değişmiş; planı yenileyin.", null);
        }

        var planned = importPlanner.plan(
                target, bundle, request.globalBindings(), validation);
        TargetImportPlan plan = planned.plan();
        if (!plan.valid()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "BUNDLE_IMPORT_PLAN_INVALID",
                    "Hedef proje aktarım planı geçerli değil.",
                    new ValidationReport(false, validation.counts(), plan.issues()));
        }
        if (request.planDigest() == null
                || !request.planDigest().equals(plan.planDigest())) {
            throw new ProjectBundleException(
                    HttpStatus.CONFLICT, "IMPORT_PLAN_CHANGED",
                    "Onaylanan aktarım planı güncel değil; planı yeniden oluşturun.", null);
        }

        ImportContentResult importedContent = importContent(
                target, bundle, planned.resolvedTopology(), request.globalBindings());
        if (pendingRecipeImportWriter != null) {
            pendingRecipeImportWriter.stage(
                    target.id(), bundle, importedContent.definitionIdsByCode(),
                    request.globalBindings(), actorId);
        }
        long newVersion;
        try {
            newVersion = repository.advanceProjectVersion(target.id(), target.version());
        }
        catch (java.util.NoSuchElementException exception) {
            throw new ProjectBundleException(
                    HttpStatus.CONFLICT, "TARGET_PROJECT_VERSION_CHANGED",
                    "Hedef proje aktarım sırasında değişti.", null);
        }
        TargetImportResult result = new TargetImportResult(
                true, false, targetProjectUuid, newVersion,
                bundle.checksum(), plan.planDigest(), validation.counts());
        repository.saveImportReceipt(
                target.id(), safeKey, bundle.checksum(), plan.planDigest(),
                newVersion, objectMapper.valueToTree(result), actorId);
        return result;
    }

    private ProjectBundleRepository.ProjectRow lockTarget(UUID targetProjectUuid) {
        try {
            return repository.lockProject(targetProjectUuid);
        }
        catch (java.util.NoSuchElementException exception) {
            throw new ProjectBundleException(
                    HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                    "Hedef proje bulunamadı.", null);
        }
    }

    private ImportContentResult importContent(
            ProjectBundleRepository.ProjectRow project,
            ProjectBundle bundle,
            JsonNode resolvedTopology,
            List<ProjectBundleModels.GlobalBinding> globalBindings) {
        return importProjectContent(project, bundle, resolvedTopology, globalBindings);
    }

    private List<BundleIssue> validateStandaloneImportReferences(ProjectBundle bundle) {
        Map<UUID, UUID> definitions = new HashMap<>();
        Map<UUID, UUID> versions = new HashMap<>();
        for (DefinitionEntry definition : safeList(bundle.definitions())) {
            if (definition.sourceUuid() != null) {
                definitions.put(definition.sourceUuid(), definition.sourceUuid());
            }
            for (VersionEntry version : safeList(definition.versions())) {
                if (version.sourceUuid() != null) {
                    versions.put(version.sourceUuid(), version.sourceUuid());
                }
            }
        }
        List<BundleIssue> issues = new ArrayList<>();
        BundleReferenceMapper.ReferenceContext context = new BundleReferenceMapper.ReferenceContext(
                definitions, versions, Map.of(), Map.of(), issues);
        for (DefinitionEntry definition : safeList(bundle.definitions())) {
            if (definition.draft() != null) {
                referenceMapper.remapDefinitionContent(
                        definition.draft().content(), definition.type(),
                        definition.draft().schemaVersion(), context);
            }
            for (VersionEntry version : safeList(definition.versions())) {
                referenceMapper.remapDefinitionContent(
                        version.content(), definition.type(), version.schemaVersion(), context);
            }
        }
        return List.copyOf(issues);
    }

    private record ImportContentResult(
            Map<DefinitionType, Map<String, Long>> definitionIdsByCode,
            Map<UUID, UUID> definitionUuidBySource,
            Map<UUID, UUID> versionUuidBySource) {
    }

    private ImportContentResult importProjectContent(
            ProjectBundleRepository.ProjectRow project,
            ProjectBundle bundle,
            JsonNode topology,
            List<ProjectBundleModels.GlobalBinding> globalBindings) {
        Map<String, Long> folderIdsByPath = new HashMap<>();
        bundle.folders().stream()
                .sorted(Comparator.comparingInt((FolderEntry item) -> pathDepth(item.path()))
                        .thenComparing(FolderEntry::path))
                .forEach(folder -> {
                    Long parentId = folder.parentPath() == null
                            ? null : folderIdsByPath.get(folder.parentPath());
                    long id = repository.insertFolder(
                            project.id(), parentId, folder.code(), folder.status(),
                            folder.name(), folder.description());
                    folderIdsByPath.put(folder.path(), id);
                });

        Map<DefinitionType, Map<String, Long>> definitionIdsByCode = new HashMap<>();
        for (DefinitionEntry definition : bundle.definitions()) {
            Long folderId = definition.folderPath() == null
                    ? null : folderIdsByPath.get(definition.folderPath());
            long definitionId = repository.insertDefinition(
                    project.id(), folderId, definition.type(), definition.code(),
                    definition.status(), definition.name(), definition.description());
            definitionIdsByCode
                    .computeIfAbsent(definition.type(), ignored -> new HashMap<>())
                    .put(definition.code(), definitionId);
        }

        Map<UUID, UUID> definitionUuidBySource = new HashMap<>();
        for (DefinitionEntry definition : bundle.definitions()) {
            if (definition.sourceUuid() == null) continue;
            Map<String, Long> byCode = definitionIdsByCode.get(definition.type());
            Long definitionId = byCode == null ? null : byCode.get(definition.code());
            if (definitionId == null) continue;
            UUID targetUuid = repository.findDefinitionUuid(
                    project.id(), definition.type(), definition.code());
            if (targetUuid != null) {
                definitionUuidBySource.put(definition.sourceUuid(), targetUuid);
            }
        }

        Map<UUID, UUID> logicalSchemaUuidBySource = new HashMap<>();
        Map<String, UUID> logicalSchemaTargetByCode = new HashMap<>();
        for (ProjectBundleModels.GlobalBinding binding : safeList(globalBindings)) {
            if (binding.type() == ProjectBundleModels.GlobalResourceType.LOGICAL_SCHEMA
                    && binding.targetUuid() != null) {
                logicalSchemaTargetByCode.put(binding.sourceCode(), binding.targetUuid());
            }
        }
        JsonNode sourceTopology = bundle.topology() == null
                ? null : bundle.topology().definitions();
        JsonNode logicalSchemas = sourceTopology == null
                ? null : sourceTopology.path("logicalSchemas");
        if (logicalSchemas != null && logicalSchemas.isArray()) {
            for (JsonNode item : logicalSchemas) {
                String code = item.path("code").asString();
                UUID sourceUuid = item.hasNonNull("sourceUuid")
                        ? UUID.fromString(item.get("sourceUuid").asString()) : null;
                UUID targetUuid = logicalSchemaTargetByCode.get(code);
                if (sourceUuid != null && targetUuid != null) {
                    logicalSchemaUuidBySource.put(sourceUuid, targetUuid);
                }
            }
        }

        Map<UUID, UUID> environmentUuidBySource = new HashMap<>();
        Map<String, UUID> environmentTargetByCode = new HashMap<>();
        for (ProjectBundleModels.GlobalBinding binding : safeList(globalBindings)) {
            if (binding.type() == ProjectBundleModels.GlobalResourceType.ENVIRONMENT
                    && binding.targetUuid() != null) {
                environmentTargetByCode.put(binding.sourceCode(), binding.targetUuid());
            }
        }
        JsonNode environments = sourceTopology == null
                ? null : sourceTopology.path("environments");
        if (environments != null && environments.isArray()) {
            for (JsonNode item : environments) {
                if (!item.hasNonNull("sourceUuid")) continue;
                UUID sourceUuid = UUID.fromString(item.get("sourceUuid").asString());
                UUID targetUuid = environmentTargetByCode.get(item.path("code").asString());
                if (targetUuid != null) environmentUuidBySource.put(sourceUuid, targetUuid);
            }
        }

        ProjectBundleRepository.PortableTopologyImportResult topologyImport =
                repository.importPortableTopologyWithSnapshots(
                        project.id(), topology, bundle.checksum());
        Map<UUID, UUID> dataObjectUuidBySource = topologyImport.dataObjectUuidBySource();
        Map<UUID, UUID> schemaSnapshotUuidBySource = topologyImport.schemaSnapshotUuidBySource();
        Map<UUID, UUID> schemaSnapshotUuidByDataObjectSource = new HashMap<>();
        JsonNode importedDataObjects = topology == null ? null : topology.path("dataObjects");
        if (importedDataObjects != null && importedDataObjects.isArray()) {
            for (JsonNode item : importedDataObjects) {
                if (!item.hasNonNull("sourceUuid")
                        || !item.hasNonNull("sourceSchemaSnapshotUuid")) continue;
                UUID sourceDataObject = UUID.fromString(item.get("sourceUuid").asText());
                UUID sourceSnapshot = UUID.fromString(
                        item.get("sourceSchemaSnapshotUuid").asText());
                UUID targetSnapshot = schemaSnapshotUuidBySource.get(sourceSnapshot);
                if (targetSnapshot != null) {
                    schemaSnapshotUuidByDataObjectSource.put(sourceDataObject, targetSnapshot);
                }
            }
        }

        List<VersionEntry> allVersions = new ArrayList<>();
        for (DefinitionEntry definition : bundle.definitions()) {
            for (VersionEntry version : safeList(definition.versions())) {
                allVersions.add(version);
            }
        }

        Map<UUID, UUID> versionUuidBySource = new HashMap<>();
        for (VersionEntry version : allVersions) {
            if (version.sourceUuid() == null) continue;
            versionUuidBySource.put(version.sourceUuid(), UUID.randomUUID());
        }

        ReferenceContext ctx = new ReferenceContext(
                definitionUuidBySource,
                versionUuidBySource,
                logicalSchemaUuidBySource,
                environmentUuidBySource,
                dataObjectUuidBySource,
                schemaSnapshotUuidBySource,
                schemaSnapshotUuidByDataObjectSource,
                new ArrayList<>());

        Map<Long, List<RemappedVersion>> remappedByDefinition = new HashMap<>();
        for (DefinitionEntry definition : bundle.definitions()) {
            Map<String, Long> byCode = definitionIdsByCode.get(definition.type());
            Long definitionId = byCode == null ? null : byCode.get(definition.code());
            if (definitionId == null) continue;

            if (definition.draft() != null) {
                JsonNode remapped = referenceMapper.remapDefinitionContent(
                        definition.draft().content(),
                        definition.type(),
                        definition.draft().schemaVersion(),
                        ctx);
                repository.insertDraft(definitionId, definition.draft().schemaVersion(), remapped);
            }

            List<RemappedVersion> remappedVersions = new ArrayList<>();
            for (VersionEntry version : safeList(definition.versions())) {
                JsonNode remapped = referenceMapper.remapDefinitionContent(
                        version.content(), definition.type(), version.schemaVersion(), ctx);
                String contentHash = sha256(canonical(remapped));
                UUID assignedUuid = version.sourceUuid() != null
                        ? versionUuidBySource.get(version.sourceUuid()) : null;
                remappedVersions.add(new RemappedVersion(
                        assignedUuid, version.versionNumber(), version.schemaVersion(),
                        contentHash, remapped, version.description(), version.createdAt()));
            }
            remappedByDefinition.put(definitionId, remappedVersions);
        }

        if (!ctx.issues().isEmpty()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BUNDLE_IMPORT_REFERENCE_UNRESOLVED",
                    "Bundle içeriğinde çözülemeyen referanslar var.",
                    new ValidationReport(false, counts(bundle), List.copyOf(ctx.issues())));
        }

        for (Map.Entry<Long, List<RemappedVersion>> entry : remappedByDefinition.entrySet()) {
            for (RemappedVersion version : entry.getValue()) {
                repository.insertVersion(entry.getKey(), new VersionRow(
                        entry.getKey(), version.uuid(), version.versionNumber(),
                        version.schemaVersion(), version.contentHash(), version.content(),
                        version.description(), version.createdAt()));
            }
        }

        return new ImportContentResult(
                definitionIdsByCode, definitionUuidBySource, versionUuidBySource);
    }

    private record RemappedVersion(
            UUID uuid,
            int versionNumber,
            int schemaVersion,
            String contentHash,
            JsonNode content,
            String description,
            OffsetDateTime createdAt) {
    }

    private TargetImportResult receiptResult(
            UUID targetProjectUuid, JsonNode result, boolean replayed) {
        JsonNode counts = result.path("counts");
        return new TargetImportResult(
                true, replayed, targetProjectUuid,
                result.path("targetVersion").asLong(),
                result.path("bundleChecksum").asString(),
                result.path("planDigest").asString(),
                new BundleCounts(
                        counts.path("folders").asInt(),
                        counts.path("definitions").asInt(),
                        counts.path("drafts").asInt(),
                        counts.path("versions").asInt(),
                        counts.path("publications").asInt(),
                        counts.path("schedules").asInt()));
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ProjectBundleException(
                    HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key başlığı gereklidir.", null);
        }
        String normalized = value.trim();
        if (normalized.length() < 8 || normalized.length() > 200) {
            throw new ProjectBundleException(
                    HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 8-200 karakter aralığında olmalıdır.", null);
        }
        return normalized;
    }

    private DefinitionEntry exportDefinition(
            DefinitionRow definition,
            Map<Long, String> pathsByFolderId,
            Map<Long, DraftRow> draftsByDefinition,
            List<VersionRow> versions) {
        DraftRow sourceDraft = draftsByDefinition.get(definition.id());
        DraftEntry draft = sourceDraft == null ? null : new DraftEntry(
                sourceDraft.schemaVersion(), sourceDraft.content().deepCopy());
        List<VersionEntry> exportedVersions = versions.stream()
                .sorted(Comparator.comparingInt(VersionRow::versionNumber))
                .map(version -> {
                    JsonNode content = version.content().deepCopy();
                    return new VersionEntry(
                            version.versionNumber(), version.schemaVersion(), version.contentHash(),
                            content, version.description(), version.createdAt(), version.uuid());
                })
                .toList();
        return new DefinitionEntry(
                definition.type(), definition.code(),
                definition.folderId() == null ? null : pathsByFolderId.get(definition.folderId()),
                definition.status(), definition.name(), definition.description(),
                draft, exportedVersions, definition.uuid());
    }

    private String resolveFolderPath(
            FolderRow folder,
            Map<Long, FolderRow> foldersById,
            Map<Long, String> resolved) {
        String existing = resolved.get(folder.id());
        if (existing != null) {
            return existing;
        }
        ArrayDeque<FolderRow> chain = new ArrayDeque<>();
        Set<Long> visiting = new HashSet<>();
        FolderRow current = folder;
        String prefix = null;
        while (current != null) {
            String resolvedParent = resolved.get(current.id());
            if (resolvedParent != null) {
                prefix = resolvedParent;
                break;
            }
            if (!visiting.add(current.id())) {
                throw new IllegalStateException("Stored folder hierarchy contains a cycle.");
            }
            chain.push(current);
            if (current.parentId() == null) {
                break;
            }
            current = foldersById.get(current.parentId());
            if (current == null) {
                throw new IllegalStateException("Stored folder hierarchy references a missing parent.");
            }
        }
        while (!chain.isEmpty()) {
            FolderRow part = chain.pop();
            prefix = prefix == null ? part.code() : prefix + "/" + part.code();
            resolved.put(part.id(), prefix);
        }
        return resolved.get(folder.id());
    }

    private void validateDocument(ProjectBundle bundle, List<BundleIssue> issues) {
        if (bundle == null) {
            issue(issues, "$", "BUNDLE_REQUIRED", "Bundle body is required.");
            return;
        }
        if (!FORMAT.equals(bundle.format())) {
            issue(issues, "format", "UNSUPPORTED_FORMAT", "Bundle format must be " + FORMAT + ".");
        }
        if (bundle.formatVersion() != FORMAT_VERSION
                && bundle.formatVersion() != LEGACY_FORMAT_VERSION) {
            issue(issues, "formatVersion", "UNSUPPORTED_FORMAT_VERSION",
                    "Only format versions " + LEGACY_FORMAT_VERSION + " and "
                            + FORMAT_VERSION + " are supported.");
        }
        int expectedSchemaVersion = bundle.formatVersion() == LEGACY_FORMAT_VERSION
                ? LEGACY_SCHEMA_VERSION : SCHEMA_VERSION;
        if (bundle.schemaVersion() != expectedSchemaVersion) {
            issue(issues, "schemaVersion", "UNSUPPORTED_SCHEMA_VERSION",
                    "Format version " + bundle.formatVersion()
                            + " requires schema version " + expectedSchemaVersion + ".");
        }
        if (bundle.formatVersion() == FORMAT_VERSION) {
            validateProducer(bundle.producer(), issues);
            if (!INCLUDED_SECTIONS.equals(bundle.includedSections())) {
                issue(issues, "includedSections", "INVALID_INCLUDED_SECTIONS",
                        "Version 3 bundles must declare the canonical included sections.");
            }
            if (bundle.publications() == null) {
                issue(issues, "publications", "PUBLICATIONS_REQUIRED", "Publications array is required.");
            }
            if (bundle.schedules() == null) {
                issue(issues, "schedules", "SCHEDULES_REQUIRED", "Schedules array is required.");
            }
            validatePublications(safeList(bundle.publications()), safeList(bundle.definitions()), issues);
            validateSchedules(safeList(bundle.schedules()), safeList(bundle.publications()), issues);
        }
        else if (bundle.formatVersion() == LEGACY_FORMAT_VERSION) {
            if (bundle.producer() != null) {
                issue(issues, "producer", "PRODUCER_NOT_ALLOWED",
                        "Version 2 bundles must not declare a producer.");
            }
            if (bundle.includedSections() != null) {
                issue(issues, "includedSections", "INCLUDED_SECTIONS_NOT_ALLOWED",
                        "Version 2 bundles must not declare included sections.");
            }
            if (bundle.publications() != null) {
                issue(issues, "publications", "PUBLICATIONS_NOT_ALLOWED",
                        "Version 2 bundles must not declare publications.");
            }
            if (bundle.schedules() != null) {
                issue(issues, "schedules", "SCHEDULES_NOT_ALLOWED",
                        "Version 2 bundles must not declare schedules.");
            }
        }
        if (bundle.exportedAt() == null) {
            issue(issues, "exportedAt", "EXPORTED_AT_REQUIRED", "Export timestamp is required.");
        }
        validateProject(bundle.project(), issues);
        if (bundle.folders() == null) {
            issue(issues, "folders", "FOLDERS_REQUIRED", "Folders array is required.");
        }
        if (bundle.definitions() == null) {
            issue(issues, "definitions", "DEFINITIONS_REQUIRED", "Definitions array is required.");
        }
        validateFolders(safeList(bundle.folders()), issues);
        validateDefinitions(
                safeList(bundle.definitions()), safeList(bundle.folders()), issues);
        if (bundle.topology() == null) {
            issue(issues, "topology", "TOPOLOGY_REQUIRED", "Sanitized topology marker is required.");
        }
        else if (!bundle.topology().sanitized()) {
            issue(issues, "topology.sanitized", "UNSANITIZED_TOPOLOGY",
                    "Topology must be marked sanitized.");
        }
        else if (bundle.topology().definitions() == null
                || !bundle.topology().definitions().isObject()) {
            issue(issues, "topology.definitions", "TOPOLOGY_DEFINITIONS_REQUIRED",
                    "Portable topology definitions must be an object.");
        }
        else {
            JsonNode topology = bundle.topology().definitions();
            for (String name : List.of(
                    "connections", "physicalSchemas", "logicalSchemas", "environments",
                    "schemaBindings", "models", "submodels", "dataObjects")) {
                if (topology.get(name) == null || !topology.get(name).isArray()) {
                    issue(issues, "topology.definitions." + name, "TOPOLOGY_ARRAY_REQUIRED",
                            name + " must be an array.");
                }
            }
            validateSubmodelHierarchy(topology.path("submodels"), issues);
            for (String secretPath : secretSanitizer.sensitivePaths(topology)) {
                issue(issues, "topology.definitions" + secretPath.substring(1),
                        "SECRET_VALUE_FORBIDDEN", "Secret values are forbidden in project bundles.");
            }
            JsonLimits topologyLimits = jsonLimits(topology);
            if (topologyLimits.depth() > MAX_JSON_DEPTH
                    || topologyLimits.nodes() > MAX_BUNDLE_JSON_NODES) {
                issue(issues, "topology.definitions", "TOPOLOGY_SIZE_LIMIT_EXCEEDED",
                        "Portable topology exceeds the JSON safety limits.");
            }
        }
        if (!HASH.matcher(nullToEmpty(bundle.checksum())).matches()) {
            issue(issues, "checksum", "INVALID_BUNDLE_CHECKSUM",
                    "Bundle checksum must be a lowercase SHA-256 value.");
        }
        else if (safeToChecksum(issues) && !bundle.checksum().equals(checksum(bundle))) {
            issue(issues, "checksum", "BUNDLE_CHECKSUM_MISMATCH",
                    "Bundle checksum does not match canonical bundle content.");
        }
    }

    private void validateSubmodelHierarchy(JsonNode submodels, List<BundleIssue> issues) {
        if (!submodels.isArray()) return;
        Map<String, String> parents = new HashMap<>();
        Map<String, Integer> positions = new HashMap<>();
        for (int index = 0; index < submodels.size(); index++) {
            JsonNode item = submodels.get(index);
            if (!item.isObject()) continue;
            String modelCode = item.path("modelCode").asString();
            String code = item.path("code").asString();
            if (modelCode.isBlank() || code.isBlank()) continue;
            String key = modelCode + "\u0000" + code;
            String parentCode = item.path("parentCode").asString();
            if (positions.putIfAbsent(key, index) != null) {
                issue(issues, "topology.definitions.submodels[" + index + "].code",
                        "SUBMODEL_DUPLICATE_CODE", "Submodel code is duplicated in the same model.");
                continue;
            }
            parents.put(key, parentCode.isBlank() ? null : modelCode + "\u0000" + parentCode);
        }
        Set<String> checked = new HashSet<>();
        for (String start : parents.keySet()) {
            if (checked.contains(start)) continue;
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && parents.containsKey(current) && !checked.contains(current)) {
                if (!path.add(current)) {
                    int index = positions.getOrDefault(current, positions.get(start));
                    issue(issues, "topology.definitions.submodels[" + index + "].parentCode",
                            "SUBMODEL_HIERARCHY_CYCLE", "Submodel parent hierarchy contains a cycle.");
                    break;
                }
                current = parents.get(current);
            }
            if (current != null && !parents.containsKey(current)) {
                int index = positions.get(start);
                issue(issues, "topology.definitions.submodels[" + index + "].parentCode",
                        "SUBMODEL_PARENT_MISSING", "Submodel parent is missing from the same model.");
            }
            checked.addAll(path);
        }
    }

    private void validateProducer(ProducerEntry producer, List<BundleIssue> issues) {
        if (producer == null) {
            issue(issues, "producer", "PRODUCER_REQUIRED",
                    "Version 3 bundles must identify their producer.");
            return;
        }
        if (!"akis-backend".equals(producer.application())
                || producer.version() == null
                || !PRODUCER_VERSION.matcher(producer.version()).matches()
                || producer.commit() == null
                || !PRODUCER_COMMIT.matcher(producer.commit()).matches()) {
            issue(issues, "producer", "INVALID_PRODUCER",
                    "Producer akis-backend, semantic version ve commit bilgisiyle gönderilmelidir.");
        }
    }

    private void validateProject(ProjectEntry project, List<BundleIssue> issues) {
        if (project == null) {
            issue(issues, "project", "PROJECT_REQUIRED", "Project metadata is required.");
            return;
        }
        validateCode(project.code(), "project.code", issues);
        validateName(project.name(), "project.name", issues);
        if (!FOLDER_STATUSES.contains(project.status())) {
            issue(issues, "project.status", "INVALID_STATUS", "Project status must be AKTIF or ARSIV.");
        }
    }

    private void validateFolders(List<FolderEntry> folders, List<BundleIssue> issues) {
        if (folders.size() > MAX_FOLDERS) {
            issue(issues, "folders", "FOLDER_LIMIT_EXCEEDED",
                    "Bundle cannot contain more than " + MAX_FOLDERS + " folders.");
            return;
        }
        Map<String, FolderEntry> byPath = new HashMap<>();
        for (int index = 0; index < folders.size(); index++) {
            FolderEntry folder = folders.get(index);
            String base = "folders[" + index + "]";
            if (folder == null) {
                issue(issues, base, "FOLDER_REQUIRED", "Folder entry cannot be null.");
                continue;
            }
            validateCode(folder.code(), base + ".code", issues);
            validateName(folder.name(), base + ".name", issues);
            if (!FOLDER_STATUSES.contains(folder.status())) {
                issue(issues, base + ".status", "INVALID_STATUS", "Unsupported folder status.");
            }
            String expectedPath = folder.parentPath() == null
                    ? folder.code() : folder.parentPath() + "/" + folder.code();
            if (!expectedPath.equals(folder.path())) {
                issue(issues, base + ".path", "INVALID_FOLDER_PATH",
                        "Folder path must be derived from parentPath and code.");
            }
            if (folder.path() != null && pathDepth(folder.path()) > MAX_FOLDER_DEPTH) {
                issue(issues, base + ".path", "FOLDER_DEPTH_LIMIT_EXCEEDED",
                        "Folder path exceeds maximum depth " + MAX_FOLDER_DEPTH + ".");
            }
            if (folder.path() != null && byPath.putIfAbsent(folder.path(), folder) != null) {
                issue(issues, base + ".path", "DUPLICATE_FOLDER_PATH", "Folder path must be unique.");
            }
        }
        for (int index = 0; index < folders.size(); index++) {
            FolderEntry folder = folders.get(index);
            if (folder != null && folder.parentPath() != null && !byPath.containsKey(folder.parentPath())) {
                issue(issues, "folders[" + index + "].parentPath", "MISSING_PARENT_FOLDER",
                        "Parent folder path does not exist in this bundle.");
            }
        }
    }

    private void validateDefinitions(
            List<DefinitionEntry> definitions,
            List<FolderEntry> folders,
            List<BundleIssue> issues) {
        if (definitions.size() > MAX_DEFINITIONS) {
            issue(issues, "definitions", "DEFINITION_LIMIT_EXCEEDED",
                    "Bundle cannot contain more than " + MAX_DEFINITIONS + " definitions.");
            return;
        }
        long totalVersions = definitions.stream().filter(java.util.Objects::nonNull)
                .mapToLong(definition -> safeList(definition.versions()).size()).sum();
        if (totalVersions > MAX_VERSIONS) {
            issue(issues, "definitions", "VERSION_LIMIT_EXCEEDED",
                    "Bundle cannot contain more than " + MAX_VERSIONS + " versions.");
            return;
        }
        Set<String> folderPaths = folders.stream().filter(java.util.Objects::nonNull)
                .map(FolderEntry::path).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<String> definitionRefs = new HashSet<>();
        for (int index = 0; index < definitions.size(); index++) {
            DefinitionEntry definition = definitions.get(index);
            String base = "definitions[" + index + "]";
            if (definition == null) {
                issue(issues, base, "DEFINITION_REQUIRED", "Definition entry cannot be null.");
                continue;
            }
            validateCode(definition.code(), base + ".code", issues);
            validateName(definition.name(), base + ".name", issues);
            if (definition.type() == null) {
                issue(issues, base + ".type", "DEFINITION_TYPE_REQUIRED", "Definition type is required.");
            }
            else if (!definitionRefs.add(definition.type().name() + ":" + definition.code())) {
                issue(issues, base, "DUPLICATE_DEFINITION_REF",
                        "Definition type and code pair must be unique.");
            }
            if (!DEFINITION_STATUSES.contains(definition.status())) {
                issue(issues, base + ".status", "INVALID_STATUS", "Unsupported definition status.");
            }
            if (definition.type() != null && definition.type().folderRequired()
                    && definition.folderPath() == null) {
                issue(issues, base + ".folderPath", "FOLDER_REQUIRED",
                        "This definition type requires a folder.");
            }
            if (definition.folderPath() != null && !folderPaths.contains(definition.folderPath())) {
                issue(issues, base + ".folderPath", "MISSING_FOLDER",
                        "Definition folder path does not exist in this bundle.");
            }
            if (definition.versions() == null) {
                issue(issues, base + ".versions", "VERSIONS_REQUIRED", "Versions array is required.");
            }
            validateDraft(definition.draft(), base + ".draft", issues);
            validateVersions(definition, base + ".versions", issues);
        }
        validateBundleJsonNodeBudget(definitions, issues);
    }

    private void validatePublications(
            List<PublicationEntry> publications,
            List<DefinitionEntry> definitions,
            List<BundleIssue> issues) {
        if (publications.size() > MAX_PUBLICATIONS) {
            issue(issues, "publications", "PUBLICATION_LIMIT_EXCEEDED",
                    "Bundle cannot contain more than " + MAX_PUBLICATIONS + " publications.");
            return;
        }
        Map<String, Set<Integer>> versionNumbersByDefinitionRef = new HashMap<>();
        for (DefinitionEntry definition : definitions) {
            if (definition == null || definition.type() == null) {
                continue;
            }
            versionNumbersByDefinitionRef.put(
                    definition.type().name() + ":" + definition.code(),
                    safeList(definition.versions()).stream()
                            .filter(java.util.Objects::nonNull)
                            .map(VersionEntry::versionNumber)
                            .collect(Collectors.toSet()));
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < publications.size(); index++) {
            PublicationEntry publication = publications.get(index);
            String base = "publications[" + index + "]";
            if (publication == null) {
                issue(issues, base, "PUBLICATION_REQUIRED", "Publication entry cannot be null.");
                continue;
            }
            if (publication.definitionType() == null) {
                issue(issues, base + ".definitionType", "DEFINITION_TYPE_REQUIRED",
                        "Publication definition type is required.");
            }
            validateCode(publication.definitionCode(), base + ".definitionCode", issues);
            validateCode(publication.environmentCode(), base + ".environmentCode", issues);
            if (publication.definitionVersionNumber() <= 0) {
                issue(issues, base + ".definitionVersionNumber", "INVALID_VERSION_NUMBER",
                        "Publication definition version number must be positive.");
            }
            if (!seen.add(publicationKey(publication))) {
                issue(issues, base, "DUPLICATE_PUBLICATION", "Publication recipe must be unique.");
            }
            if (publication.definitionType() != null && publication.definitionCode() != null) {
                Set<Integer> versionNumbers = versionNumbersByDefinitionRef.get(
                        publication.definitionType().name() + ":" + publication.definitionCode());
                if (versionNumbers == null) {
                    issue(issues, base, "PUBLICATION_DEFINITION_UNRESOLVED",
                            "Publication references a definition that is not present in this bundle.");
                }
                else if (!versionNumbers.contains(publication.definitionVersionNumber())) {
                    issue(issues, base, "PUBLICATION_VERSION_UNRESOLVED",
                            "Publication references a definition version that is not present in this bundle.");
                }
            }
        }
    }

    private void validateSchedules(
            List<ScheduleEntry> schedules,
            List<PublicationEntry> publications,
            List<BundleIssue> issues) {
        if (schedules.size() > MAX_SCHEDULES) {
            issue(issues, "schedules", "SCHEDULE_LIMIT_EXCEEDED",
                    "Bundle cannot contain more than " + MAX_SCHEDULES + " schedules.");
            return;
        }
        Set<String> publicationKeys = publications.stream().filter(java.util.Objects::nonNull)
                .map(this::publicationKey).collect(Collectors.toSet());
        Set<String> seenCodes = new HashSet<>();
        for (int index = 0; index < schedules.size(); index++) {
            ScheduleEntry schedule = schedules.get(index);
            String base = "schedules[" + index + "]";
            if (schedule == null) {
                issue(issues, base, "SCHEDULE_REQUIRED", "Schedule entry cannot be null.");
                continue;
            }
            validateCode(schedule.code(), base + ".code", issues);
            validateName(schedule.name(), base + ".name", issues);
            if (validCode(schedule.code()) && !seenCodes.add(schedule.code())) {
                issue(issues, base + ".code", "DUPLICATE_SCHEDULE_CODE", "Schedule code must be unique.");
            }
            if (schedule.cronExpression() == null || schedule.cronExpression().isBlank()) {
                issue(issues, base + ".cronExpression", "CRON_EXPRESSION_REQUIRED",
                        "Cron expression is required.");
            }
            else {
                try {
                    org.springframework.scheduling.support.CronExpression.parse(
                            schedule.cronExpression().trim());
                }
                catch (IllegalArgumentException invalid) {
                    issue(issues, base + ".cronExpression", "INVALID_CRON_EXPRESSION",
                            "Cron expression could not be parsed.");
                }
            }
            if (schedule.timeZone() == null || schedule.timeZone().isBlank()) {
                issue(issues, base + ".timeZone", "TIME_ZONE_REQUIRED", "Time zone is required.");
            }
            else {
                try {
                    java.time.ZoneId.of(schedule.timeZone().trim());
                }
                catch (RuntimeException invalid) {
                    issue(issues, base + ".timeZone", "INVALID_TIME_ZONE", "Time zone id is unknown.");
                }
            }
            if (schedule.conflictPolicy() == null || !CONFLICT_POLICIES.contains(schedule.conflictPolicy())) {
                issue(issues, base + ".conflictPolicy", "INVALID_CONFLICT_POLICY",
                        "Conflict policy must be one of " + CONFLICT_POLICIES + ".");
            }
            if (schedule.misfirePolicy() == null || !MISFIRE_POLICIES.contains(schedule.misfirePolicy())) {
                issue(issues, base + ".misfirePolicy", "INVALID_MISFIRE_POLICY",
                        "Misfire policy must be one of " + MISFIRE_POLICIES + ".");
            }
            if (schedule.publicationPolicy() == null
                    || !PUBLICATION_POLICIES.contains(schedule.publicationPolicy())) {
                issue(issues, base + ".publicationPolicy", "INVALID_PUBLICATION_POLICY",
                        "Publication policy must be one of " + PUBLICATION_POLICIES + ".");
            }
            if (schedule.startsAt() != null && schedule.endsAt() != null
                    && !schedule.endsAt().isAfter(schedule.startsAt())) {
                issue(issues, base + ".endsAt", "INVALID_SCHEDULE_WINDOW",
                        "Schedule end time must be after start time.");
            }
            if (schedule.publicationSelection() == null) {
                issue(issues, base + ".publicationSelection", "PUBLICATION_SELECTION_REQUIRED",
                        "Schedule must select a publication recipe.");
            }
            else if (!publicationKeys.contains(publicationKey(schedule.publicationSelection()))) {
                issue(issues, base + ".publicationSelection", "PUBLICATION_SELECTION_UNRESOLVED",
                        "Schedule selects a publication recipe that is not present in this bundle.");
            }
        }
    }

    private String publicationKey(PublicationEntry publication) {
        return (publication.definitionType() == null ? "" : publication.definitionType().name())
                + ":" + publication.definitionCode() + ":" + publication.definitionVersionNumber()
                + ":" + publication.environmentCode();
    }

    private void validateDraft(DraftEntry draft, String path, List<BundleIssue> issues) {
        if (draft == null) {
            return;
        }
        if (draft.schemaVersion() <= 0) {
            issue(issues, path + ".schemaVersion", "INVALID_CONTENT_SCHEMA_VERSION",
                    "Content schema version must be positive.");
        }
        validateContentShapeAndSecrets(draft.content(), path + ".content", issues);
    }

    private void validateVersions(
            DefinitionEntry definition, String path, List<BundleIssue> issues) {
        List<VersionEntry> versions = safeList(definition.versions());
        if (versions.size() > MAX_VERSIONS) {
            issue(issues, path, "VERSION_LIMIT_EXCEEDED",
                    "A definition cannot contain more than " + MAX_VERSIONS + " versions.");
            return;
        }
        Set<Integer> numbers = new HashSet<>();
        int max = 0;
        for (int index = 0; index < versions.size(); index++) {
            VersionEntry version = versions.get(index);
            String base = path + "[" + index + "]";
            if (version == null) {
                issue(issues, base, "VERSION_REQUIRED", "Version entry cannot be null.");
                continue;
            }
            if (version.versionNumber() <= 0 || !numbers.add(version.versionNumber())) {
                issue(issues, base + ".versionNumber", "INVALID_VERSION_NUMBER",
                        "Version numbers must be positive and unique.");
            }
            max = Math.max(max, version.versionNumber());
            if (version.schemaVersion() <= 0) {
                issue(issues, base + ".schemaVersion", "INVALID_CONTENT_SCHEMA_VERSION",
                        "Content schema version must be positive.");
            }
            if (version.createdAt() == null) {
                issue(issues, base + ".createdAt", "VERSION_CREATED_AT_REQUIRED",
                        "Version creation timestamp is required.");
            }
            boolean safeShape = validateContentShapeAndSecrets(
                    version.content(), base + ".content", issues);
            if (!HASH.matcher(nullToEmpty(version.contentHash())).matches()) {
                issue(issues, base + ".contentHash", "INVALID_CONTENT_HASH",
                        "Content hash must be a lowercase SHA-256 value.");
            }
            else if (safeShape && !version.contentHash().equals(sha256(canonical(version.content())))) {
                issue(issues, base + ".contentHash", "CONTENT_HASH_MISMATCH",
                        "Content hash does not match canonical content.");
            }
            if (safeShape && definition.type() != null) {
                try {
                    contentValidator.validate(
                            definition.type(), version.schemaVersion(), version.content());
                }
                catch (ApiException exception) {
                    issue(issues, base + ".content", "SEMANTIC_VALIDATION_FAILED",
                            exception.getMessage());
                }
            }
        }
        if (!numbers.isEmpty() && (numbers.size() != max || !numbers.contains(1))) {
            issue(issues, path, "NON_CONTIGUOUS_VERSIONS",
                    "Immutable version numbers must be contiguous from 1.");
        }
    }

    private boolean validateContentShapeAndSecrets(
            JsonNode content, String path, List<BundleIssue> issues) {
        if (content == null || !content.isObject()) {
            issue(issues, path, "INVALID_CONTENT", "Definition content must be a JSON object.");
            return false;
        }
        List<String> secretPaths = secretSanitizer.sensitivePaths(content);
        for (String secretPath : secretPaths) {
            issue(issues, path + secretPath.substring(1), "SECRET_VALUE_FORBIDDEN",
                    "Secret values are forbidden in project bundles.");
        }
        JsonLimits limits = jsonLimits(content);
        if (limits.depth() > MAX_JSON_DEPTH) {
            issue(issues, path, "JSON_DEPTH_LIMIT_EXCEEDED",
                    "JSON content exceeds maximum depth " + MAX_JSON_DEPTH + ".");
        }
        if (limits.nodes() > MAX_JSON_NODES) {
            issue(issues, path, "JSON_NODE_LIMIT_EXCEEDED",
                    "JSON content exceeds maximum node count " + MAX_JSON_NODES + ".");
        }
        return secretPaths.isEmpty()
                && limits.depth() <= MAX_JSON_DEPTH && limits.nodes() <= MAX_JSON_NODES;
    }

    private void validateBundleJsonNodeBudget(
            List<DefinitionEntry> definitions, List<BundleIssue> issues) {
        long nodes = 0;
        for (DefinitionEntry definition : definitions) {
            if (definition == null) {
                continue;
            }
            if (definition.draft() != null && definition.draft().content() != null) {
                nodes += jsonLimits(definition.draft().content()).nodes();
                if (nodes > MAX_BUNDLE_JSON_NODES) {
                    issue(issues, "definitions", "BUNDLE_JSON_NODE_LIMIT_EXCEEDED",
                            "Bundle definition content exceeds maximum total node count "
                                    + MAX_BUNDLE_JSON_NODES + ".");
                    return;
                }
            }
            for (VersionEntry version : safeList(definition.versions())) {
                if (version != null && version.content() != null) {
                    nodes += jsonLimits(version.content()).nodes();
                }
                if (nodes > MAX_BUNDLE_JSON_NODES) {
                    issue(issues, "definitions", "BUNDLE_JSON_NODE_LIMIT_EXCEEDED",
                            "Bundle definition content exceeds maximum total node count "
                                    + MAX_BUNDLE_JSON_NODES + ".");
                    return;
                }
            }
        }
    }

    private ValidationReport report(ProjectBundle bundle, List<BundleIssue> issues) {
        return new ValidationReport(issues.isEmpty(), counts(bundle), List.copyOf(issues));
    }

    private BundleCounts counts(ProjectBundle bundle) {
        if (bundle == null) {
            return new BundleCounts(0, 0, 0, 0, 0, 0);
        }
        List<DefinitionEntry> definitions = safeList(bundle.definitions());
        int drafts = (int) definitions.stream().filter(java.util.Objects::nonNull)
                .filter(definition -> definition.draft() != null).count();
        int versions = definitions.stream().filter(java.util.Objects::nonNull)
                .mapToInt(definition -> safeList(definition.versions()).size()).sum();
        return new BundleCounts(
                safeList(bundle.folders()).size(), definitions.size(), drafts, versions,
                safeList(bundle.publications()).size(), safeList(bundle.schedules()).size());
    }

    JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
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

    String checksum(ProjectBundle bundle) {
        ObjectNode payload = objectMapper.valueToTree(bundle);
        payload.remove("checksum");
        payload.remove("exportedAt");
        return sha256(canonical(payload));
    }

    private ProjectBundle withChecksum(ProjectBundle bundle, String checksum) {
        return new ProjectBundle(
                bundle.format(), bundle.formatVersion(), bundle.schemaVersion(), checksum,
                bundle.exportedAt(), bundle.project(), bundle.folders(), bundle.definitions(),
                bundle.topology(), bundle.producer(), bundle.includedSections(),
                bundle.publications(), bundle.schedules());
    }

    private ProducerEntry producer() {
        Package bundlePackage = ProjectBundleService.class.getPackage();
        String version = bundlePackage.getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "0.1.0-SNAPSHOT";
        }
        String commit = System.getenv("AKIS_BUILD_COMMIT");
        if (commit == null || commit.isBlank()) {
            commit = "development";
        }
        return new ProducerEntry("akis-backend", version, commit);
    }

    private void assertNoSecretValues(ProjectBundle bundle) {
        List<BundleIssue> issues = new ArrayList<>();
        List<DefinitionEntry> definitions = safeList(bundle.definitions());
        for (int index = 0; index < definitions.size(); index++) {
            DefinitionEntry definition = definitions.get(index);
            if (definition.draft() != null) {
                addSecretIssues(
                        definition.draft().content(), "definitions[" + index + "].draft.content", issues);
            }
            List<VersionEntry> versions = safeList(definition.versions());
            for (int versionIndex = 0; versionIndex < versions.size(); versionIndex++) {
                addSecretIssues(versions.get(versionIndex).content(),
                        "definitions[" + index + "].versions[" + versionIndex + "].content", issues);
            }
        }
        if (!issues.isEmpty()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "SECRET_VALUE_FORBIDDEN",
                    "Project contains secret values and cannot be exported.",
                    new ValidationReport(false, counts(bundle), List.copyOf(issues)));
        }
    }

    private void addSecretIssues(JsonNode content, String path, List<BundleIssue> issues) {
        for (String secretPath : secretSanitizer.sensitivePaths(content)) {
            issue(issues, path + secretPath.substring(1), "SECRET_VALUE_FORBIDDEN",
                    "Secret values are forbidden in project bundles.");
        }
    }

    private String resolveTargetProjectCode(String sourceCode, ConflictPolicy policy) {
        if (!repository.projectCodeExists(sourceCode)) {
            return sourceCode;
        }
        if (policy == ConflictPolicy.FAIL) {
            throw new ProjectBundleException(
                    HttpStatus.CONFLICT, "BUNDLE_CONFLICT",
                    "A project with code " + sourceCode + " already exists.", null);
        }
        for (int sequence = 1; sequence < Integer.MAX_VALUE; sequence++) {
            String suffix = "_IMPORT_" + sequence;
            String prefix = sourceCode.substring(0, Math.min(sourceCode.length(), 100 - suffix.length()));
            String candidate = prefix + suffix;
            if (!repository.projectCodeExists(candidate)) {
                return candidate;
            }
        }
        throw new ProjectBundleException(
                HttpStatus.CONFLICT, "BUNDLE_RENAME_EXHAUSTED",
                "A free deterministic import code could not be found.", null);
    }

    private JsonLimits jsonLimits(JsonNode root) {
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.push(new NodeDepth(root, 1));
        int nodes = 0;
        int depth = 0;
        while (!queue.isEmpty() && nodes <= MAX_JSON_NODES && depth <= MAX_JSON_DEPTH) {
            NodeDepth current = queue.pop();
            nodes++;
            depth = Math.max(depth, current.depth());
            if (current.node().isObject()) {
                current.node().propertyNames().forEach(
                        name -> queue.push(new NodeDepth(current.node().get(name), current.depth() + 1)));
            }
            else if (current.node().isArray()) {
                current.node().forEach(
                        child -> queue.push(new NodeDepth(child, current.depth() + 1)));
            }
        }
        return new JsonLimits(nodes, depth);
    }

    private boolean safeToChecksum(List<BundleIssue> issues) {
        return issues.stream().map(BundleIssue::code).noneMatch(code ->
                code.endsWith("LIMIT_EXCEEDED"));
    }

    String sha256(JsonNode content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void validateCode(String code, String path, List<BundleIssue> issues) {
        if (!validCode(code)) {
            issue(issues, path, "INVALID_CODE",
                    "Code must start with A-Z and contain only A-Z, 0-9, or underscore.");
        }
    }

    private boolean validCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    private void validateName(String name, String path, List<BundleIssue> issues) {
        if (name == null || name.isBlank() || name.length() > 200) {
            issue(issues, path, "INVALID_NAME", "Name must contain 1-200 characters.");
        }
    }

    private void issue(List<BundleIssue> issues, String path, String code, String message) {
        issues.add(new BundleIssue(path, code, message));
    }

    private int pathDepth(String path) {
        return path == null ? 0 : path.split("/", -1).length;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private record NodeDepth(JsonNode node, int depth) {
    }

    private record JsonLimits(int nodes, int depth) {
    }
}

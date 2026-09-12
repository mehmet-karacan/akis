package tr.com.innova.akis.projectbundle;

import static tr.com.innova.akis.projectbundle.ProjectBundleModels.FORMAT;
import static tr.com.innova.akis.projectbundle.ProjectBundleModels.FORMAT_VERSION;
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

import org.springframework.http.HttpStatus;
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
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ValidationReport;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.VersionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DefinitionRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DraftRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ExportSnapshot;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.FolderRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.VersionRow;

@Service
public class ProjectBundleService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    static final int MAX_FOLDERS = 10_000;
    static final int MAX_DEFINITIONS = 20_000;
    static final int MAX_VERSIONS = 100_000;
    static final int MAX_FOLDER_DEPTH = 100;
    static final int MAX_JSON_DEPTH = 100;
    static final int MAX_JSON_NODES = 100_000;
    static final int MAX_BUNDLE_JSON_NODES = 1_000_000;
    private static final Set<String> FOLDER_STATUSES = Set.of("AKTIF", "ARSIV");
    private static final Set<String> DEFINITION_STATUSES = Set.of("TASLAK", "AKTIF", "ARSIV");

    private final ProjectBundleRepository repository;
    private final DefinitionContentValidator contentValidator;
    private final SecretValueSanitizer secretSanitizer;
    private final ObjectMapper objectMapper;

    public ProjectBundleService(
            ProjectBundleRepository repository,
            DefinitionContentValidator contentValidator,
            SecretValueSanitizer secretSanitizer,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.contentValidator = contentValidator;
        this.secretSanitizer = secretSanitizer;
        this.objectMapper = objectMapper;
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

        var project = snapshot.project();
        ProjectBundle withoutChecksum = new ProjectBundle(
                FORMAT, FORMAT_VERSION, SCHEMA_VERSION, null, OffsetDateTime.now(),
                new ProjectEntry(
                        project.code(), project.status(), project.name(), project.description()),
                folders, definitions, new TopologyEntry(
                        true, repository.loadPortableTopology(project.id())));
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
        ValidationReport validation = validate(bundle);
        if (!validation.valid()) {
            throw new ProjectBundleException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BUNDLE_VALIDATION_FAILED",
                    "Bundle validation failed.",
                    validation);
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

        repository.importPortableTopology(project.id(), bundle.topology().definitions());

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

        for (DefinitionEntry definition : bundle.definitions()) {
            Long folderId = definition.folderPath() == null
                    ? null : folderIdsByPath.get(definition.folderPath());
            long definitionId = repository.insertDefinition(
                    project.id(), folderId, definition.type(), definition.code(), definition.status(),
                    definition.name(), definition.description());
            if (definition.draft() != null) {
                repository.insertDraft(
                        definitionId, definition.draft().schemaVersion(),
                        definition.draft().content().deepCopy());
            }
            for (VersionEntry version : safeList(definition.versions())) {
                repository.insertVersion(definitionId, new VersionRow(
                        definitionId, version.versionNumber(), version.schemaVersion(),
                        version.contentHash(), version.content().deepCopy(),
                        version.description(), version.createdAt()));
            }
        }
        return new ImportResult(
                true, false, project.code(), projectUuid, validation.counts());
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
                            content, version.description(), version.createdAt());
                })
                .toList();
        return new DefinitionEntry(
                definition.type(), definition.code(),
                definition.folderId() == null ? null : pathsByFolderId.get(definition.folderId()),
                definition.status(), definition.name(), definition.description(), draft, exportedVersions);
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
        if (bundle.formatVersion() != FORMAT_VERSION) {
            issue(issues, "formatVersion", "UNSUPPORTED_FORMAT_VERSION",
                    "Only format version " + FORMAT_VERSION + " is supported.");
        }
        if (bundle.schemaVersion() != SCHEMA_VERSION) {
            issue(issues, "schemaVersion", "UNSUPPORTED_SCHEMA_VERSION",
                    "Only schema version " + SCHEMA_VERSION + " is supported.");
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
            return new BundleCounts(0, 0, 0, 0);
        }
        List<DefinitionEntry> definitions = safeList(bundle.definitions());
        int drafts = (int) definitions.stream().filter(java.util.Objects::nonNull)
                .filter(definition -> definition.draft() != null).count();
        int versions = definitions.stream().filter(java.util.Objects::nonNull)
                .mapToInt(definition -> safeList(definition.versions()).size()).sum();
        return new BundleCounts(safeList(bundle.folders()).size(), definitions.size(), drafts, versions);
    }

    private JsonNode canonical(JsonNode node) {
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
                bundle.topology());
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

    private String sha256(JsonNode content) {
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

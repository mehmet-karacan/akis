package tr.com.innova.akis.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.metadata.MetadataModels.DefinitionRow;
import tr.com.innova.akis.metadata.MetadataModels.DraftRow;
import tr.com.innova.akis.metadata.MetadataModels.FolderRow;
import tr.com.innova.akis.metadata.MetadataModels.ProjectRow;
import tr.com.innova.akis.metadata.MetadataModels.VersionRow;

@Service
public class MetadataService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    private final MetadataRepository repository;
    private final ObjectMapper objectMapper;

    public MetadataService(MetadataRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    List<DefinitionType> definitionTypes() {
        return List.of(DefinitionType.values());
    }

    @Transactional
    ProjectRow createProject(String code, String name, String description) {
        return repository.createProject(
                UUID.randomUUID(), normalizeCode(code), normalizeName(name), trimToNull(description));
    }

    List<ProjectRow> listProjects() {
        return repository.listProjects();
    }

    ProjectRow project(UUID projectUuid) {
        return repository.findProject(projectUuid).orElseThrow(() -> notFound("Proje bulunamadı."));
    }

    @Transactional
    FolderRow createFolder(
            UUID projectUuid,
            UUID parentUuid,
            String code,
            String type,
            String name,
            String description) {
        ProjectRow project = project(projectUuid);
        Long parentId = parentUuid == null
                ? null
                : repository.findFolder(project.id(), parentUuid)
                        .orElseThrow(() -> notFound("Üst klasör bulunamadı."))
                        .id();
        String normalizedType = type == null ? "GELISTIRME" : type.toUpperCase();
        if (!List.of("GELISTIRME", "MODEL", "YUKLEME_PLANI").contains(normalizedType)) {
            throw validation("Geçersiz klasör türü.");
        }
        return repository.createFolder(
                project.id(),
                UUID.randomUUID(),
                parentId,
                normalizeCode(code),
                normalizedType,
                normalizeName(name),
                trimToNull(description));
    }

    List<FolderRow> listFolders(UUID projectUuid) {
        return repository.listFolders(project(projectUuid).id());
    }

    @Transactional
    DefinitionRow createDefinition(
            UUID projectUuid,
            UUID folderUuid,
            DefinitionType type,
            String code,
            String name,
            String description) {
        ProjectRow project = project(projectUuid);
        Long folderId = null;
        if (folderUuid != null) {
            folderId = repository.findFolder(project.id(), folderUuid)
                    .orElseThrow(() -> notFound("Klasör bulunamadı."))
                    .id();
        }
        if (type.folderRequired() && folderId == null) {
            throw validation(type.label() + " için klasör zorunludur.");
        }
        return repository.createDefinition(
                project.id(),
                UUID.randomUUID(),
                folderId,
                type,
                normalizeCode(code),
                normalizeName(name),
                trimToNull(description));
    }

    List<DefinitionRow> listDefinitions(UUID projectUuid, DefinitionType type) {
        return repository.listDefinitions(project(projectUuid).id(), type);
    }

    DefinitionRow definition(UUID projectUuid, UUID definitionUuid) {
        ProjectRow project = project(projectUuid);
        return repository.findDefinition(project.id(), definitionUuid)
                .orElseThrow(() -> notFound("Tanım bulunamadı."));
    }

    @Transactional
    DefinitionRow createGlobalDefinition(
            DefinitionType type,
            String code,
            String name,
            String description) {
        if (!type.globalAllowed()) {
            throw validation(type.label() + " global kapsamda tanımlanamaz.");
        }
        return repository.createGlobalDefinition(
                UUID.randomUUID(),
                type,
                normalizeCode(code),
                normalizeName(name),
                trimToNull(description));
    }

    List<DefinitionRow> listGlobalDefinitions(DefinitionType type) {
        if (type != null && !type.globalAllowed()) {
            throw validation(type.label() + " global kapsamda tanımlanamaz.");
        }
        return repository.listGlobalDefinitions(type);
    }

    DefinitionRow globalDefinition(UUID definitionUuid) {
        return repository.findGlobalDefinition(definitionUuid)
                .orElseThrow(() -> notFound("Global tanım bulunamadı."));
    }

    DraftRow draft(UUID projectUuid, UUID definitionUuid) {
        return draft(definition(projectUuid, definitionUuid));
    }

    DraftRow globalDraft(UUID definitionUuid) {
        return draft(globalDefinition(definitionUuid));
    }

    private DraftRow draft(DefinitionRow definition) {
        return repository.findDraft(definition.id())
                .orElseThrow(() -> notFound("Tanım taslağı bulunamadı."));
    }

    @Transactional
    DraftRow saveDraft(
            UUID projectUuid,
            UUID definitionUuid,
            Long expectedVersion,
            int schemaVersion,
            JsonNode content) {
        return saveDraft(
                definition(projectUuid, definitionUuid),
                expectedVersion,
                schemaVersion,
                content);
    }

    @Transactional
    DraftRow saveGlobalDraft(
            UUID definitionUuid,
            Long expectedVersion,
            int schemaVersion,
            JsonNode content) {
        return saveDraft(
                globalDefinition(definitionUuid),
                expectedVersion,
                schemaVersion,
                content);
    }

    private DraftRow saveDraft(
            DefinitionRow definition,
            Long expectedVersion,
            int schemaVersion,
            JsonNode content) {
        if (schemaVersion <= 0) {
            throw validation("Şema sürümü sıfırdan büyük olmalıdır.");
        }
        requireObject(content);
        var existing = repository.findDraft(definition.id());
        if (existing.isEmpty()) {
            if (expectedVersion != null && expectedVersion != 0) {
                throw new ApiException(
                        HttpStatus.PRECONDITION_FAILED,
                        "STALE_VERSION",
                        "Taslak henüz oluşturulmamış; expectedVersion 0 olmalıdır.");
            }
            return repository.createDraft(definition.id(), schemaVersion, content);
        }
        if (expectedVersion == null) {
            throw new ApiException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "EXPECTED_VERSION_REQUIRED",
                    "Mevcut taslağı güncellemek için expectedVersion zorunludur.");
        }
        return repository.updateDraft(
                definition.id(), expectedVersion, schemaVersion, content);
    }

    @Transactional
    VersionRow createVersion(
            UUID projectUuid,
            UUID definitionUuid,
            Long expectedDraftVersion,
            String description) {
        return createVersion(
                definition(projectUuid, definitionUuid),
                expectedDraftVersion,
                description);
    }

    @Transactional
    VersionRow createGlobalVersion(
            UUID definitionUuid,
            Long expectedDraftVersion,
            String description) {
        return createVersion(
                globalDefinition(definitionUuid),
                expectedDraftVersion,
                description);
    }

    private VersionRow createVersion(
            DefinitionRow definition,
            Long expectedDraftVersion,
            String description) {
        repository.lockDefinition(definition.id());
        DraftRow draft = repository.findDraft(definition.id())
                .orElseThrow(() -> validation("Sürümlenecek taslak bulunamadı."));
        if (expectedDraftVersion == null || draft.version() != expectedDraftVersion) {
            throw new ApiException(
                    HttpStatus.PRECONDITION_FAILED,
                    "STALE_VERSION",
                    "Taslak sürümü istekle uyuşmuyor.");
        }
        validateContent(definition.type(), draft.content());
        validateOwnership(definition, draft.content());
        JsonNode canonical = canonicalize(draft.content());
        return repository.createVersion(
                definition.id(),
                draft.schemaVersion(),
                sha256(canonical.toString()),
                canonical,
                trimToNull(description));
    }

    List<VersionRow> listVersions(UUID projectUuid, UUID definitionUuid) {
        return repository.listVersions(definition(projectUuid, definitionUuid).id());
    }

    List<VersionRow> listGlobalVersions(UUID definitionUuid) {
        return repository.listVersions(globalDefinition(definitionUuid).id());
    }

    void validateContent(DefinitionType type, JsonNode content) {
        requireObject(content);
        List<String> missing = type.requiredContentFields().stream()
                .filter(field -> content.get(field) == null || content.get(field).isNull())
                .toList();
        if (!missing.isEmpty()) {
            throw validation(type.label() + " içeriğinde zorunlu alanlar eksik: "
                    + String.join(", ", missing));
        }
        switch (type) {
            case PACKAGE -> {
                requireArray(content, "steps");
                requireArray(content, "transitions");
                requireText(content, "firstStepId");
            }
            case PROCEDURE -> requireArray(content, "tasks");
            case VARIABLE -> {
                requireAllowed(content, "dataType", List.of(
                        "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP", "JSON"));
                requireAllowed(content, "scope", List.of(
                        "GLOBAL", "PROJECT", "PACKAGE_RUN", "STEP"));
                requireAllowed(content, "historyMode", List.of("NONE", "LATEST", "ALL"));
                requireAllowed(content, "valueSource", List.of(
                        "INPUT", "DEFAULT", "REFRESH_QUERY", "EXPRESSION", "STEP_OUTPUT"));
            }
            case SEQUENCE -> {
                requireAllowed(content, "implementation", List.of("NATIVE", "REPOSITORY", "TABLE"));
                if (!content.path("start").isNumber() || !content.path("increment").isNumber()) {
                    throw validation("Sequence start ve increment sayısal olmalıdır.");
                }
                if (content.path("increment").decimalValue().signum() == 0) {
                    throw validation("Sequence increment sıfır olamaz.");
                }
                if (!content.path("cycle").isBoolean()) {
                    throw validation("Sequence cycle boolean olmalıdır.");
                }
            }
            case MAPPING -> {
                requireArray(content, "datasets");
                requireArray(content, "columnMappings");
                if (!content.path("writeStrategy").isObject()) {
                    throw validation("Mapping writeStrategy nesne olmalıdır.");
                }
            }
            case REUSABLE_MAPPING -> {
                requireArray(content, "inputs");
                requireArray(content, "outputs");
                requireArray(content, "nodes");
            }
            case USER_FUNCTION -> {
                requireText(content, "returnType");
                requireArray(content, "parameters");
                requireArray(content, "implementations");
            }
            case KNOWLEDGE_MODULE -> {
                requireAllowed(content, "kmType", List.of("RKM", "CKM", "LKM", "IKM", "XKM", "JKM", "SKM"));
                requireArray(content, "tasks");
                requireArray(content, "options");
            }
            case LOAD_PLAN -> {
                requireArray(content, "steps");
                requireText(content, "restartPolicy");
            }
        }
    }

    private void validateOwnership(DefinitionRow definition, JsonNode content) {
        if (definition.type() != DefinitionType.VARIABLE) {
            return;
        }
        String scope = content.path("scope").stringValue();
        if (definition.projectId() == null && !"GLOBAL".equals(scope)) {
            throw validation("Global Değişken tanımının scope alanı GLOBAL olmalıdır.");
        }
        if (definition.projectId() != null && "GLOBAL".equals(scope)) {
            throw validation("Proje Değişken tanımının scope alanı GLOBAL olamaz.");
        }
    }

    JsonNode canonicalize(JsonNode node) {
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

    private void requireObject(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw validation("Tanım içeriği JSON nesnesi olmalıdır.");
        }
    }

    private void requireArray(JsonNode content, String field) {
        if (!content.path(field).isArray()) {
            throw validation(field + " alanı dizi olmalıdır.");
        }
    }

    private void requireText(JsonNode content, String field) {
        if (!content.path(field).isString() || content.path(field).stringValue().isBlank()) {
            throw validation(field + " alanı boş olmayan metin olmalıdır.");
        }
    }

    private void requireAllowed(JsonNode content, String field, List<String> allowed) {
        requireText(content, field);
        if (!allowed.contains(content.path(field).stringValue())) {
            throw validation(field + " alanı desteklenmeyen değer içeriyor.");
        }
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (!CODE.matcher(normalized).matches()) {
            throw validation("Kod A-Z ile başlamalı ve yalnız A-Z, 0-9, _ içermelidir.");
        }
        return normalized;
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw validation("Ad 1-200 karakter olmalıdır.");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

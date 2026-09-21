package tr.com.innova.akis.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.node.JsonNodeFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.catalog.CatalogModels.DataObjectRow;
import tr.com.innova.akis.catalog.CatalogModels.LogicalSchemaRef;
import tr.com.innova.akis.catalog.CatalogModels.ModelRow;
import tr.com.innova.akis.catalog.CatalogModels.ProjectRef;
import tr.com.innova.akis.catalog.CatalogModels.SubmodelRow;
import tr.com.innova.akis.metadata.ApiException;

@Service
public class CatalogService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern FORBIDDEN_QUERY_TOKEN = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|MERGE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|CALL|BEGIN|DECLARE|EXECUTE)\\b");
    private static final Set<String> DATA_OBJECT_TYPES = Set.of("TABLO", "VIEW", "SORGU");

    private final CatalogRepository repository;

    public CatalogService(CatalogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    ModelRow createModel(
            UUID projectUuid,
            UUID logicalSchemaUuid,
            String technologyCode,
            UUID reverseEnvironmentUuid,
            String reverseMode,
            UUID rkmDefinitionUuid,
            JsonNode reverseOptions,
            String code,
            String name,
            String description) {
        ProjectRef project = project(projectUuid);
        LogicalSchemaRef logicalSchema = repository.findLogicalSchema(
                        project.id(), logicalSchemaUuid)
                .orElseThrow(() -> notFound("Mantıksal şema bulunamadı."));
        String technology = validateTechnology(project.id(), logicalSchema.id(), defaultValue(technologyCode, "ORACLE"));
        String mode = allowed(defaultValue(reverseMode, "STANDARD"), Set.of("STANDARD", "CUSTOM_RKM"), "reverse engineering modu");
        Long environmentId = environmentId(project.id(), reverseEnvironmentUuid);
        Long rkmId = rkmId(project.id(), mode, rkmDefinitionUuid);
        return repository.createModel(
                project.id(), logicalSchema.id(), UUID.randomUUID(), technology,
                environmentId, mode, rkmId, options(reverseOptions), normalizeCode(code),
                normalizeName(name), trimToNull(description));
    }

    @Transactional
    ModelRow updateModel(
            UUID projectUuid, UUID modelUuid, UUID logicalSchemaUuid, String technologyCode,
            UUID reverseEnvironmentUuid, String reverseMode, UUID rkmDefinitionUuid,
            JsonNode reverseOptions, String name, String description, long expectedVersion) {
        ProjectRef project = project(projectUuid);
        model(project, modelUuid);
        LogicalSchemaRef logical = repository.findLogicalSchema(project.id(), logicalSchemaUuid)
                .orElseThrow(() -> notFound("Mantıksal şema bulunamadı."));
        String technology = validateTechnology(project.id(), logical.id(), defaultValue(technologyCode, "ORACLE"));
        String mode = allowed(defaultValue(reverseMode, "STANDARD"), Set.of("STANDARD", "CUSTOM_RKM"), "reverse engineering modu");
        try {
            return repository.updateModel(project.id(), modelUuid, logical.id(), technology,
                    environmentId(project.id(), reverseEnvironmentUuid), mode,
                    rkmId(project.id(), mode, rkmDefinitionUuid), options(reverseOptions),
                    normalizeName(name), trimToNull(description), expectedVersion);
        }
        catch (IllegalStateException conflict) {
            if ("MODEL_VERSION_CONFLICT".equals(conflict.getMessage())) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Model başka bir kullanıcı tarafından güncellendi.");
            }
            throw conflict;
        }
    }

    List<ModelRow> listModels(UUID projectUuid) {
        ProjectRef project = project(projectUuid);
        return repository.listModels(project.id());
    }

    ModelRow model(UUID projectUuid, UUID modelUuid) {
        ProjectRef project = project(projectUuid);
        return model(project, modelUuid);
    }

    @Transactional
    void deleteModel(UUID projectUuid, UUID modelUuid, long expectedVersion) {
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        if (model.dataObjectCount() > 0 || repository.modelHasSubmodels(model.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_IN_USE",
                    "Veri nesnesi veya klasörü bulunan model silinemez.");
        }
        if (!repository.archiveModel(project.id(), modelUuid, expectedVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "Model başka bir kullanıcı tarafından güncellendi.");
        }
    }

    @Transactional
    SubmodelRow createSubmodel(
            UUID projectUuid,
            UUID modelUuid,
            UUID parentUuid,
            String code,
            String name) {
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        Long parentId = null;
        if (parentUuid != null) {
            SubmodelRow parent = repository.findSubmodel(project.id(), parentUuid)
                    .orElseThrow(() -> notFound("Üst alt model bulunamadı."));
            if (parent.modelId() != model.id()) {
                throw validation("Üst alt model aynı modele ait olmalıdır.");
            }
            parentId = parent.id();
        }
        return repository.createSubmodel(
                project.id(), model.id(), parentId, UUID.randomUUID(), normalizeCode(code),
                normalizeName(name));
    }

    List<SubmodelRow> listSubmodels(UUID projectUuid, UUID modelUuid) {
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        return repository.listSubmodels(project.id(), model.id());
    }

    @Transactional
    DataObjectRow createDataObject(
            UUID projectUuid,
            UUID modelUuid,
            UUID submodelUuid,
            String code,
            String objectReference,
            String type,
            Integer querySchemaVersion,
            JsonNode queryDefinition,
            String name) {
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        Long submodelId = null;
        if (submodelUuid != null) {
            SubmodelRow submodel = repository.findSubmodel(project.id(), submodelUuid)
                    .orElseThrow(() -> notFound("Alt model bulunamadı."));
            if (submodel.modelId() != model.id()) {
                throw validation("Alt model veri nesnesiyle aynı modele ait olmalıdır.");
            }
            submodelId = submodel.id();
        }

        String normalizedType = allowed(type, DATA_OBJECT_TYPES, "veri nesnesi türü");
        validateQueryDefinition(normalizedType, querySchemaVersion, queryDefinition);
        return repository.createDataObject(
                project.id(), model.id(), submodelId, UUID.randomUUID(), normalizeCode(code),
                required(objectReference, "Nesne referansı", 500), normalizedType,
                querySchemaVersion, queryDefinition, normalizeName(name));
    }

    @Transactional
    DataObjectRow moveDataObject(UUID projectUuid, UUID modelUuid, UUID objectUuid, UUID folderUuid, long expectedVersion) {
        if (expectedVersion < 1) throw validation("Geçerli kayıt sürümü zorunludur.");
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        DataObjectRow object = repository.findDataObject(project.id(), objectUuid)
                .filter(row -> row.modelId() == model.id())
                .orElseThrow(() -> notFound("Data Store bu modelde bulunamadı."));
        if (!"AKTIF".equals(model.status()) || !"AKTIF".equals(object.status()))
            throw validation("Arşivlenmiş model veya Data Store taşınamaz.");
        Long folderId = null;
        if (folderUuid != null) {
            SubmodelRow folder = repository.findSubmodel(project.id(), folderUuid)
                    .orElseThrow(() -> notFound("Model klasörü bulunamadı."));
            if (folder.modelId() != model.id()) throw validation("Klasör aynı modele ait olmalıdır.");
            folderId = folder.id();
        }
        return repository.moveDataObject(project.id(), model.id(), object.uuid(), folderId, expectedVersion)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "Data Store veya model değişti. Kaydı yenileyip tekrar deneyin."));
    }

    /** Archive a data store. Refused while an active definition still binds it, so the explorer can explain what to detach first. */
    void deleteDataObject(UUID projectUuid, UUID modelUuid, UUID objectUuid, long expectedVersion) {
        if (expectedVersion < 1) throw validation("Geçerli kayıt sürümü zorunludur.");
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        DataObjectRow object = repository.findDataObject(project.id(), objectUuid)
                .filter(row -> row.modelId() == model.id())
                .orElseThrow(() -> notFound("Data Store bu modelde bulunamadı."));
        List<String> users = repository.definitionsReferencingDataObject(project.id(), object.id());
        if (!users.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_OBJECT_IN_USE",
                    "Data Store şu nesnelerde kullanılıyor: " + String.join(", ", users) + ". Önce oradan çıkarın.");
        }
        if (!repository.archiveDataObject(project.id(), object.uuid(), expectedVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Data Store başka bir kullanıcı tarafından güncellendi.");
        }
    }

    void deleteSubmodel(UUID projectUuid, UUID modelUuid, UUID submodelUuid, long expectedVersion) {
        if (expectedVersion < 1) throw validation("Geçerli kayıt sürümü zorunludur.");
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        SubmodelRow folder = repository.findSubmodel(project.id(), submodelUuid)
                .filter(row -> row.modelId() == model.id())
                .orElseThrow(() -> notFound("Model klasörü bulunamadı."));
        if (repository.submodelHasContent(folder.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBMODEL_IN_USE", "Data Store veya alt klasörü bulunan klasör silinemez.");
        }
        if (!repository.archiveSubmodel(project.id(), folder.uuid(), expectedVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Klasör başka bir kullanıcı tarafından güncellendi.");
        }
    }

    List<DataObjectRow> listDataObjects(UUID projectUuid, UUID modelUuid) {
        ProjectRef project = project(projectUuid);
        ModelRow model = model(project, modelUuid);
        return repository.listDataObjects(project.id(), model.id());
    }

    private void validateQueryDefinition(
            String type,
            Integer querySchemaVersion,
            JsonNode queryDefinition) {
        if (type.equals("SORGU")) {
            if (querySchemaVersion == null || querySchemaVersion < 1
                    || queryDefinition == null || !queryDefinition.isObject()) {
                throw validation("SORGU için pozitif querySchemaVersion ve JSON nesnesi zorunludur.");
            }
            JsonNode sql = queryDefinition.path("sql");
            if (!sql.isString() || sql.stringValue().isBlank()) {
                throw validation("SORGU tanımında boş olmayan sql alanı zorunludur.");
            }
            String normalizedSql = sql.stringValue().stripLeading().toUpperCase(Locale.ROOT);
            if (!normalizedSql.startsWith("SELECT ") && !normalizedSql.startsWith("WITH ")) {
                throw validation("Kontrollü sorgu yalnız SELECT veya WITH ile başlayabilir.");
            }
            if (normalizedSql.contains(";") || normalizedSql.contains("--")
                    || normalizedSql.contains("/*") || normalizedSql.contains("*/")
                    || FORBIDDEN_QUERY_TOKEN.matcher(normalizedSql).find()) {
                throw validation("Kontrollü sorgu tek ve salt okunur bir ifade olmalıdır.");
            }
        }
        else if (querySchemaVersion != null || queryDefinition != null) {
            throw validation("TABLO/VIEW için sorgu tanımı gönderilemez.");
        }
    }

    private ProjectRef project(UUID projectUuid) {
        return repository.findProject(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
    }

    private ModelRow model(ProjectRef project, UUID modelUuid) {
        return repository.findModel(project.id(), modelUuid)
                .orElseThrow(() -> notFound("Model bulunamadı."));
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw validation("Kod A-Z ile başlamalı ve yalnız A-Z, 0-9, _ içermelidir.");
        }
        return normalized;
    }

    private String validateTechnology(long projectId, long logicalSchemaId, String value) {
        String technology = allowed(value, Set.of("ORACLE"), "teknoloji");
        if (repository.logicalSchemaProviders(projectId, logicalSchemaId).stream()
                .anyMatch(provider -> !technology.equals(provider))) {
            throw validation("Mantıksal şema farklı bir teknoloji sağlayıcısına bağlı.");
        }
        return technology;
    }

    private Long environmentId(long projectId, UUID uuid) {
        if (uuid == null) return null;
        return repository.findEnvironmentId(projectId, uuid)
                .orElseThrow(() -> notFound("Reverse engineering ortamı bulunamadı."));
    }

    private Long rkmId(long projectId, String mode, UUID uuid) {
        if ("STANDARD".equals(mode)) return null;
        if (uuid == null) throw validation("Özel reverse engineering için RKM seçilmelidir.");
        return repository.findRkmDefinitionId(projectId, uuid)
                .orElseThrow(() -> notFound("RKM yürütme modülü bulunamadı."));
    }

    private JsonNode options(JsonNode value) {
        if (value == null || value.isNull()) return JsonNodeFactory.instance.objectNode();
        if (!value.isObject()) throw validation("Reverse engineering seçenekleri JSON nesnesi olmalıdır.");
        return value;
    }

    private String normalizeName(String name) {
        return required(name, "Ad", 200);
    }

    private String required(String value, String field, int maximumLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maximumLength) {
            throw validation(field + " 1-" + maximumLength + " karakter olmalıdır.");
        }
        return normalized;
    }

    private String allowed(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw validation("Geçersiz " + field + ".");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

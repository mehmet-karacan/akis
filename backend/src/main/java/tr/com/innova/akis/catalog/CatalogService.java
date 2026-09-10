package tr.com.innova.akis.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
            String code,
            String name,
            String description) {
        ProjectRef project = project(projectUuid);
        LogicalSchemaRef logicalSchema = repository.findLogicalSchema(
                        project.id(), logicalSchemaUuid)
                .orElseThrow(() -> notFound("Mantıksal şema bulunamadı."));
        return repository.createModel(
                project.id(), logicalSchema.id(), UUID.randomUUID(), normalizeCode(code),
                normalizeName(name), trimToNull(description));
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

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

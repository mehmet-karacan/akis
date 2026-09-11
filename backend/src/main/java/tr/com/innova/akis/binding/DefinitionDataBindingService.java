package tr.com.innova.akis.binding;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.binding.BindingModels.BindingRow;
import tr.com.innova.akis.binding.BindingModels.BindingCandidateRow;
import tr.com.innova.akis.binding.BindingModels.CreateBinding;
import tr.com.innova.akis.binding.BindingModels.DataObjectRef;
import tr.com.innova.akis.binding.BindingModels.DefinitionVersionRef;
import tr.com.innova.akis.binding.BindingModels.ProjectRef;
import tr.com.innova.akis.binding.BindingModels.SchemaSnapshotRef;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionType;

@Service
public class DefinitionDataBindingService {

    private static final Set<DefinitionType> SUPPORTED_TYPES = Set.of(
            DefinitionType.MAPPING, DefinitionType.REUSABLE_MAPPING,
            DefinitionType.PROCEDURE);

    private final DefinitionDataBindingStore store;

    public DefinitionDataBindingService(DefinitionDataBindingStore store) {
        this.store = store;
    }

    @Transactional
    public BindingRow create(
            UUID projectUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            String nodeCode,
            BindingRole role,
            UUID dataObjectUuid,
            UUID schemaSnapshotUuid) {
        ProjectRef project = project(projectUuid);
        DefinitionVersionRef version = version(
                project.id(), definitionUuid, definitionVersionUuid);
        requireSupportedType(version);
        String normalizedNodeCode = required(nodeCode, "Düğüm kodu", 200);
        if (role == null) {
            throw validation("Bağ rolü zorunludur.");
        }
        validateProcedureBinding(version, normalizedNodeCode, role);
        DataObjectRef dataObject = store.findDataObject(project.id(), dataObjectUuid)
                .orElseThrow(() -> notFound("Veri nesnesi bulunamadı."));
        SchemaSnapshotRef snapshot = store.findSchemaSnapshot(project.id(), schemaSnapshotUuid)
                .orElseThrow(() -> notFound("Şema görüntüsü bulunamadı."));
        if (snapshot.dataObjectId() != dataObject.id()) {
            throw validation("Şema görüntüsü seçilen veri nesnesine ait olmalıdır.");
        }
        if (store.nodeExists(version.id(), normalizedNodeCode)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "BINDING_ALREADY_EXISTS",
                    "Bu tanım sürümünde düğüm kodu zaten bağlıdır.");
        }
        return store.create(new CreateBinding(
                project.id(), version.id(), version.definitionUuid(), version.versionUuid(),
                dataObject.id(), dataObject.uuid(), snapshot.id(), snapshot.uuid(),
                normalizedNodeCode, role, UUID.randomUUID()));
    }

    public BindingRow get(
            UUID projectUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            UUID bindingUuid) {
        ProjectRef project = project(projectUuid);
        DefinitionVersionRef version = version(
                project.id(), definitionUuid, definitionVersionUuid);
        requireSupportedType(version);
        return store.find(project.id(), version.id(), bindingUuid)
                .orElseThrow(() -> notFound("Veri nesnesi bağı bulunamadı."));
    }

    public List<BindingRow> list(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
        ProjectRef project = project(projectUuid);
        DefinitionVersionRef version = version(
                project.id(), definitionUuid, definitionVersionUuid);
        requireSupportedType(version);
        return store.list(project.id(), version.id());
    }

    public List<BindingCandidateRow> candidates(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
        ProjectRef project = project(projectUuid);
        DefinitionVersionRef version = version(
                project.id(), definitionUuid, definitionVersionUuid);
        requireSupportedType(version);
        return store.listTrustedSnapshotCandidates(project.id());
    }

    private ProjectRef project(UUID projectUuid) {
        return store.findProject(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
    }

    private DefinitionVersionRef version(
            long projectId, UUID definitionUuid, UUID definitionVersionUuid) {
        return store.findDefinitionVersion(projectId, definitionUuid, definitionVersionUuid)
                .orElseThrow(() -> notFound("Tanım sürümü bulunamadı."));
    }

    private void requireSupportedType(DefinitionVersionRef version) {
        if (!SUPPORTED_TYPES.contains(version.definitionType())) {
            throw validation(
                    "Veri nesnesi bağı yalnızca Mapping, Reusable Mapping veya Procedure tanımlarında kullanılabilir.");
        }
    }

    private void validateProcedureBinding(
            DefinitionVersionRef version, String nodeCode, BindingRole role) {
        if (version.definitionType() != DefinitionType.PROCEDURE) {
            return;
        }
        JsonNode tasks = version.content().path("tasks");
        JsonNode task = null;
        for (JsonNode candidate : tasks) {
            if (nodeCode.equals(candidate.path("id").asText())) {
                task = candidate;
                break;
            }
        }
        if (task == null) {
            throw validation("Düğüm kodu Procedure içindeki bir görev kimliği olmalıdır.");
        }
        BindingRole expectedRole = switch (task.path("connectionRole").asText()) {
            case "SOURCE" -> BindingRole.KAYNAK;
            case "TARGET" -> BindingRole.HEDEF;
            default -> throw validation("Procedure görevinin bağlantı rolü geçersizdir.");
        };
        if (role != expectedRole) {
            throw validation("Veri bağı rolü Procedure görevinin bağlantı rolüyle eşleşmelidir.");
        }
    }

    private String required(String value, String field, int maximumLength) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized == null || normalized.length() > maximumLength) {
            throw validation(field + " 1-" + maximumLength + " karakter olmalıdır.");
        }
        return normalized;
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}

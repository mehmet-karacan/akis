package tr.com.innova.akis.discovery;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConnectionVersionRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.CreateSnapshot;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.DataObjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.PhysicalSchemaRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ProjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotProvenance;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.metadata.ApiException;

@Service
public class SchemaSnapshotService {

    private static final Set<String> CONSTRAINT_TYPES = Set.of("PK", "UK", "FK", "CHECK");

    private final SchemaSnapshotStore store;
    private final SchemaFingerprint fingerprint;
    public SchemaSnapshotService(SchemaSnapshotStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.fingerprint = new SchemaFingerprint(objectMapper);
    }

    @Transactional
    public SnapshotRow create(
            UUID projectUuid,
            UUID dataObjectUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            String engineVersion,
            OffsetDateTime discoveredAt,
            int propertyVersion,
            JsonNode properties,
            List<ColumnInput> columns,
            List<ConstraintInput> constraints) {
        return create(projectUuid, dataObjectUuid, physicalSchemaUuid, connectionVersionUuid, engineVersion, discoveredAt,
                propertyVersion, properties, columns, constraints, SnapshotProvenance.oracleWithoutEvidence());
    }

    @Transactional
    public SnapshotRow create(
            UUID projectUuid,
            UUID dataObjectUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            String engineVersion,
            OffsetDateTime discoveredAt,
            int propertyVersion,
            JsonNode properties,
            List<ColumnInput> columns,
            List<ConstraintInput> constraints,
            SnapshotProvenance provenance) {
        ProjectRef project = project(projectUuid);
        DataObjectRef dataObject = store.findDataObject(project.id(), dataObjectUuid)
                .orElseThrow(() -> notFound("Veri nesnesi bulunamadı."));
        PhysicalSchemaRef physicalSchema = store.findPhysicalSchema(
                        project.id(), physicalSchemaUuid)
                .orElseThrow(() -> notFound("Fiziksel şema bulunamadı."));
        ConnectionVersionRef connectionVersion = store.findConnectionVersion(
                        project.id(), connectionVersionUuid)
                .orElseThrow(() -> notFound("Bağlantı sürümü bulunamadı."));
        if (physicalSchema.connectionId() != connectionVersion.connectionId()) {
            throw validation("Fiziksel şema ve bağlantı sürümü aynı bağlantıya ait olmalıdır.");
        }

        String normalizedEngineVersion = required(engineVersion, "Motor sürümü", 200);
        if (discoveredAt == null) {
            throw validation("Keşif zamanı zorunludur.");
        }
        if (propertyVersion < 1) {
            throw validation("Özellik sürümü pozitif olmalıdır.");
        }
        JsonNode canonicalProperties = requireObject(properties, "Özellikler");
        List<ColumnInput> normalizedColumns = normalizeColumns(columns);
        List<ConstraintInput> normalizedConstraints = normalizeConstraints(
                constraints, normalizedColumns);
        String calculatedFingerprint = fingerprint.calculate(
                normalizedEngineVersion, propertyVersion, canonicalProperties,
                normalizedColumns, normalizedConstraints);

        return store.create(new CreateSnapshot(
                project.id(), dataObject.id(), dataObject.uuid(), physicalSchema.id(),
                physicalSchema.uuid(), connectionVersion.id(), connectionVersion.uuid(),
                UUID.randomUUID(), calculatedFingerprint, normalizedEngineVersion,
                discoveredAt, propertyVersion, canonicalProperties,
                normalizedColumns, normalizedConstraints, provenance));
    }

    public List<SnapshotRow> list(UUID projectUuid, UUID dataObjectUuid) {
        ProjectRef project = project(projectUuid);
        dataObject(project, dataObjectUuid);
        return store.list(project.id(), dataObjectUuid);
    }

    public SnapshotRow get(UUID projectUuid, UUID dataObjectUuid, UUID snapshotUuid) {
        ProjectRef project = project(projectUuid);
        dataObject(project, dataObjectUuid);
        SnapshotRow snapshot = store.find(project.id(), snapshotUuid)
                .orElseThrow(() -> notFound("Şema görüntüsü bulunamadı."));
        if (!snapshot.dataObjectUuid().equals(dataObjectUuid)) {
            throw notFound("Şema görüntüsü bulunamadı.");
        }
        return snapshot;
    }

    private List<ColumnInput> normalizeColumns(List<ColumnInput> columns) {
        if (columns == null || columns.isEmpty()) {
            throw validation("En az bir kolon zorunludur.");
        }
        Set<String> references = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        List<ColumnInput> normalized = new ArrayList<>();
        for (ColumnInput column : columns) {
            if (column == null || column.ordinal() < 1) {
                throw validation("Kolon sırası pozitif olmalıdır.");
            }
            String reference = required(column.reference(), "Kolon referansı", 500);
            if (!references.add(reference)) {
                throw validation("Kolon referansları benzersiz olmalıdır.");
            }
            if (!ordinals.add(column.ordinal())) {
                throw validation("Kolon sıraları benzersiz olmalıdır.");
            }
            if (column.precision() != null && column.precision() < 0
                    || column.length() != null && column.length() < 0
                    || column.timePrecision() != null && column.timePrecision() < 0) {
                throw validation("Kolon hassasiyet ve uzunlukları negatif olamaz.");
            }
            normalized.add(new ColumnInput(
                    reference,
                    upperRequired(column.producerType(), "Üretici tip kodu", 200),
                    upperRequired(column.canonicalType(), "Kanonik tip kodu", 100),
                    column.ordinal(), column.precision(), column.scale(), column.length(),
                    column.timePrecision(), column.nullable(), trimToNull(column.defaultExpression()),
                    required(column.name(), "Kolon adı", 200)));
        }
        return normalized;
    }

    private List<ConstraintInput> normalizeConstraints(
            List<ConstraintInput> constraints, List<ColumnInput> columns) {
        if (constraints == null) {
            throw validation("Kısıtlar dizisi zorunludur; boş olabilir.");
        }
        Set<String> availableColumns = new HashSet<>();
        columns.forEach(column -> availableColumns.add(column.reference()));
        Set<String> constraintReferences = new HashSet<>();
        List<ConstraintInput> normalized = new ArrayList<>();
        for (ConstraintInput constraint : constraints) {
            if (constraint == null) {
                throw validation("Kısıt boş olamaz.");
            }
            String externalReference = required(
                    constraint.externalReference(), "Kısıt referansı", 500);
            if (!constraintReferences.add(externalReference)) {
                throw validation("Kısıt referansları benzersiz olmalıdır.");
            }
            String type = upperRequired(constraint.type(), "Kısıt türü", 20);
            if (!CONSTRAINT_TYPES.contains(type)) {
                throw validation("Geçersiz kısıt türü.");
            }
            if (constraint.detailVersion() < 1) {
                throw validation("Kısıt ayrıntı sürümü pozitif olmalıdır.");
            }
            JsonNode details = requireObject(constraint.details(), "Kısıt ayrıntısı");
            List<String> columnReferences = normalizeConstraintColumns(
                    constraint.columnReferences(), availableColumns);
            if (!type.equals("CHECK") && columnReferences.isEmpty()) {
                throw validation("PK, UK ve FK kısıtları en az bir kolon içermelidir.");
            }
            normalized.add(new ConstraintInput(
                    externalReference, type, constraint.enabled(), constraint.detailVersion(),
                    details, required(constraint.name(), "Kısıt adı", 200), columnReferences));
        }
        return normalized;
    }

    private List<String> normalizeConstraintColumns(
            List<String> columnReferences, Set<String> availableColumns) {
        if (columnReferences == null) {
            throw validation("Kısıt kolonları dizisi zorunludur; boş olabilir.");
        }
        Set<String> unique = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String value : columnReferences) {
            String reference = required(value, "Kısıt kolon referansı", 500);
            if (!availableColumns.contains(reference)) {
                throw validation("Kısıt, görüntüye ait olmayan bir kolona başvuruyor.");
            }
            if (!unique.add(reference)) {
                throw validation("Bir kısıtta aynı kolon birden fazla kullanılamaz.");
            }
            normalized.add(reference);
        }
        return normalized;
    }

    private ProjectRef project(UUID projectUuid) {
        return store.findProject(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
    }

    private DataObjectRef dataObject(ProjectRef project, UUID dataObjectUuid) {
        return store.findDataObject(project.id(), dataObjectUuid)
                .orElseThrow(() -> notFound("Veri nesnesi bulunamadı."));
    }

    private JsonNode requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " JSON nesnesi olmalıdır.");
        }
        return fingerprint.canonicalize(value);
    }

    private String upperRequired(String value, String field, int maximumLength) {
        return required(value, field, maximumLength).toUpperCase(Locale.ROOT);
    }

    private String required(String value, String field, int maximumLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maximumLength) {
            throw validation(field + " 1-" + maximumLength + " karakter olmalıdır.");
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

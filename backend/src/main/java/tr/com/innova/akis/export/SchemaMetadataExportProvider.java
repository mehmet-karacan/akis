package tr.com.innova.akis.export;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportRequest;

/** Global, read-only export of the schema dictionary and its dependencies. */
@Component
class SchemaMetadataExportProvider implements ExportProvider {

    private final SchemaMetadataExportReader reader;
    private final ObjectMapper objectMapper;

    SchemaMetadataExportProvider(SchemaMetadataExportReader reader, ObjectMapper objectMapper) {
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerId, String resourceId) {
        return ExportProviderRegistry.DATASET_SCHEMA_METADATA.equals(providerId)
                && ExportProviderRegistry.RESOURCE_SCHEMA_METADATA.equals(resourceId);
    }

    @Override
    public void validate(ExportRequest request) {
        if (request.scope() == null) {
            throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPORT_SCOPE_REQUIRED", "Aktarım kapsamı zorunludur.");
        }
        if (request.filters() == null) return;
        for (ExportFilter filter : request.filters()) {
            if (filter != null && filter.field() != null && !"query".equals(filter.field())) {
                throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "EXPORT_SCHEMA_FILTER_INVALID", "Desteklenmeyen metadata filtresi: " + filter.field());
            }
        }
    }

    @Override
    public void streamRecords(ExportContext context, JsonExportWriter writer) throws IOException {
        writer.writeHeader(ExportProviderRegistry.DATASET_SCHEMA_METADATA,
                ExportProviderRegistry.RESOURCE_SCHEMA_METADATA, context.scope(), context.locale(),
                context.timeZone(), context.filters(), "Read-only schema metadata dictionary");
        reader.forEachSchema(schema -> writeIfIncluded(context, writer, "SCHEMA", schema.name(), () -> schemaNode(schema)));
        reader.forEachTable(table -> writeIfIncluded(context, writer, "TABLE", table.name(), () -> tableNode(table)));
        reader.forEachColumn(column -> writeIfIncluded(context, writer, "COLUMN", column.name(), () -> columnNode(column)));
        reader.forEachConstraint(constraint -> writeIfIncluded(context, writer, "CONSTRAINT", constraint.name(), () -> constraintNode(constraint)));
        reader.forEachIndex(index -> writeIfIncluded(context, writer, "INDEX", index.name(), () -> indexNode(index)));
        reader.forEachRelationship(relationship -> writeIfIncluded(context, writer, "RELATIONSHIP", relationship.sourceTable(), () -> relationshipNode(relationship)));
        reader.forEachSequence(sequence -> writeIfIncluded(context, writer, "SEQUENCE", sequence.name(), () -> sequenceNode(sequence)));
    }

    private void writeIfIncluded(ExportContext context, JsonExportWriter writer, String type, String name,
            java.util.function.Supplier<ObjectNode> record) {
        if (!matches(context, type, name)) return;
        try { writer.writeRecord(record.get()); }
        catch (IOException e) { throw new ExportException(HttpStatus.INTERNAL_SERVER_ERROR,
                "EXPORT_SCHEMA_STREAM_ERROR", "Şema metadata aktarılırken hata oluştu."); }
    }

    private boolean matches(ExportContext context, String type, String name) {
        if (context.filters() == null || context.filters().isEmpty()) return true;
        for (ExportFilter filter : context.filters()) {
            if (filter != null && "query".equals(filter.field()) && filter.value() != null && filter.value().isTextual()) {
                String needle = filter.value().asText().toLowerCase(Locale.ROOT);
                if (name == null || !name.toLowerCase(Locale.ROOT).contains(needle)) return false;
            }
        }
        return true;
    }

    private ObjectNode base(String type, java.util.UUID uuid, String name, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recordType", type); node.put("uuid", uuid.toString()); node.put("name", name);
        node.put("description", description); return node;
    }
    private ObjectNode schemaNode(SchemaMetadataExportReader.SchemaRef x) { return base("SCHEMA", x.uuid(), x.name(), x.description()); }
    private ObjectNode tableNode(SchemaMetadataExportReader.TableRef x) { ObjectNode n=base("TABLE",x.uuid(),x.name(),x.description()); n.put("schemaUuid",x.schemaUuid().toString()); return n; }
    private ObjectNode columnNode(SchemaMetadataExportReader.ColumnRef x) { ObjectNode n=base("COLUMN",x.uuid(),x.name(),x.description()); n.put("tableUuid",x.tableUuid().toString()); n.put("ordinal",x.ordinal()); n.put("dataType",x.dataType()); n.put("length",x.length()); n.put("required",x.required()); n.put("defaultValue",x.defaultValue()); return n; }
    private ObjectNode constraintNode(SchemaMetadataExportReader.ConstraintRef x) { ObjectNode n=base("CONSTRAINT",x.uuid(),x.name(),x.description()); n.put("tableUuid",x.tableUuid().toString()); n.put("type",x.type()); n.put("checkExpression",x.checkExpression()); return n; }
    private ObjectNode indexNode(SchemaMetadataExportReader.IndexRef x) { ObjectNode n=base("INDEX",x.uuid(),x.name(),x.description()); n.put("tableUuid",x.tableUuid().toString()); n.put("type",x.type()); n.put("unique",x.unique()); return n; }
    private ObjectNode relationshipNode(SchemaMetadataExportReader.RelationshipRef x) { ObjectNode n=base("RELATIONSHIP",x.uuid(),x.sourceTable(),null); n.put("sourceTableUuid",x.sourceTableUuid().toString()); n.put("targetTableUuid",x.targetTableUuid().toString()); n.put("targetTable",x.targetTable()); n.put("sourceConstraint",x.sourceConstraint()); n.put("targetConstraint",x.targetConstraint()); n.put("deleteRule",x.deleteRule()); n.put("updateRule",x.updateRule()); return n; }
    private ObjectNode sequenceNode(SchemaMetadataExportReader.SequenceRef x) { ObjectNode n=base("SEQUENCE",x.uuid(),x.name(),x.description()); n.put("schemaUuid",x.schemaUuid().toString()); n.put("startValue",x.startValue()); n.put("increment",x.increment()); n.put("minValue",x.minValue()); n.put("maxValue",x.maxValue()); n.put("cycle",x.cycle()); return n; }
}

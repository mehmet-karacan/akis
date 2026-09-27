package tr.com.innova.akis.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.export.ExportModels.ExportColumn;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportScope;

/**
 * Streams the akis.data-export v1 envelope incrementally using Jackson 3.
 *
 * <p>BIGINT / decimal / counter values are written as decimal strings.
 * Dates are ISO-8601. Unsafe SQL and credential fields are redacted.
 */
public final class JsonExportWriterImpl implements JsonExportWriter {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JsonGenerator generator;
    private final ObjectMapper objectMapper;
    private final Instant snapshotAt;
    private final List<ExportColumn> columns;
    private final boolean redactUnsafeSql;
    private boolean recordsStarted;
    private boolean recordsClosed;
    private boolean closed;
    private long recordCount;

    private JsonExportWriterImpl(Builder builder) throws IOException {
        this.objectMapper = Objects.requireNonNull(builder.objectMapper);
        // Jackson 3 has no JsonGenerator.setPrettyPrinter; pretty printing is
        // configured when the generator is created from the writer.
        this.generator = objectMapper.writerWithDefaultPrettyPrinter().createGenerator(builder.outputStream);
        this.snapshotAt = Objects.requireNonNullElse(builder.snapshotAt, Instant.now());
        this.columns = List.copyOf(Objects.requireNonNull(builder.columns));
        this.redactUnsafeSql = builder.redactUnsafeSql;
    }

    @Override
    public void writeHeader(
            String dataset,
            String resourceId,
            ExportScope scope,
            String locale,
            String timeZone,
            List<ExportFilter> filters,
            String providerDescription) throws IOException {
        generator.writeStartObject();
        writeStringField("format", JsonExportWriter.FORMAT);
        writeStringField("formatVersion", JsonExportWriter.FORMAT_VERSION);
        writeStringField("dataset", dataset);
        writeStringField("resourceId", resourceId);
        writeStringField("generatedAt", ISO.format(OffsetDateTime.now(ZoneOffset.UTC)));
        writeStringField("snapshotAt", ISO.format(snapshotAt.atOffset(ZoneOffset.UTC)));
        writeStringField("scope", scope.name());
        writeStringField("locale", locale == null ? "tr-TR" : locale);
        writeStringField("timeZone", timeZone == null ? "UTC" : timeZone);

        writeFieldName("query");
        generator.writeStartObject();
        writeArrayFieldStart("filters");
        if (filters != null) {
            for (ExportFilter filter : filters) {
                generator.writeStartObject();
                writeStringField("field", filter.field());
                writeStringField("operator", filter.operator());
                writeJsonNode("value", filter.value());
                generator.writeEndObject();
            }
        }
        generator.writeEndArray();
        generator.writeEndObject();

        writeArrayFieldStart("columns");
        for (ExportColumn column : columns) {
            generator.writeStartObject();
            writeStringField("key", column.key());
            writeStringField("label", column.label());
            generator.writeEndObject();
        }
        generator.writeEndArray();

        writeStringField("description", providerDescription);

        writeArrayFieldStart("records");
        recordsStarted = true;
    }

    @Override
    public void writeRecord(JsonNode record) throws IOException {
        ensureRecordsStarted();
        generator.writeTree(redact(record));
        recordCount++;
    }

    @Override
    public void writeRecord(java.util.function.Consumer<JsonExportWriter.JsonObjectBuilder> builder) throws IOException {
        ensureRecordsStarted();
        JsonExportWriter.JsonObjectBuilder object = new JsonExportWriter.JsonObjectBuilder(objectMapper);
        builder.accept(object);
        generator.writeTree(redact(object.build()));
        recordCount++;
    }

    @Override
    public void writeStreamingRecord(java.util.function.Consumer<JsonExportWriter.JsonRecordWriter> writer) throws IOException {
        ensureRecordsStarted();
        generator.writeStartObject();
        writer.accept(new GeneratorRecordWriter());
        generator.writeEndObject();
        recordCount++;
    }

    @Override
    public void writeSummary(long processedRows, Long selected, Long inserted, Long updated, Long deleted) throws IOException {
        ensureRecordsStarted();
        if (recordsClosed) {
            throw new IllegalStateException("Summary already written.");
        }
        generator.writeEndArray();
        recordsClosed = true;
        writeFieldName("summary");
        generator.writeStartObject();
        writeStringField("recordCount", Long.toString(recordCount));
        writeStringField("processedRows", Long.toString(processedRows));
        writeDecimalField("selectedRows", selected);
        writeDecimalField("insertedRows", inserted);
        writeDecimalField("updatedRows", updated);
        writeDecimalField("deletedRows", deleted);
        generator.writeEndObject();
    }

    @Override
    public void writeDetails(JsonNode details) throws IOException {
        ensureRecordsStarted();
        if (recordsClosed) {
            throw new IllegalStateException("Details must be written before the summary.");
        }
        writeFieldName("details");
        generator.writeTree(redact(details));
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (recordsStarted && !recordsClosed) {
            generator.writeEndArray();
        }
        generator.writeEndObject();
        generator.flush();
        generator.close();
    }

    @Override
    public long recordCount() {
        return recordCount;
    }

    private final class GeneratorRecordWriter implements JsonExportWriter.JsonRecordWriter {
        @Override
        public void put(String key, String value) throws IOException {
            generator.writeName(key);
            if (value == null) generator.writeNull(); else generator.writeString(value);
        }

        @Override
        public void put(String key, long value) throws IOException {
            generator.writeName(key);
            generator.writeNumber(value);
        }

        @Override
        public void put(String key, Long value) throws IOException {
            generator.writeName(key);
            if (value == null) generator.writeNull(); else generator.writeNumber(value);
        }

        @Override
        public void put(String key, JsonNode value) throws IOException {
            generator.writeName(key);
            generator.writeTree(redact(value));
        }

        @Override
        public void startArray(String key) throws IOException {
            generator.writeName(key);
            generator.writeStartArray();
        }

        @Override
        public void writeArrayValue(JsonNode value) throws IOException {
            generator.writeTree(redact(value));
        }

        @Override
        public void endArray() throws IOException {
            generator.writeEndArray();
        }

        @Override
        public void startObject(String key) throws IOException {
            generator.writeName(key);
            generator.writeStartObject();
        }

        @Override
        public void endObject() throws IOException {
            generator.writeEndObject();
        }
    }

    private void ensureRecordsStarted() {
        if (!recordsStarted) {
            throw new IllegalStateException("Header must be written before records.");
        }
    }

    private void writeStringField(String name, String value) throws IOException {
        generator.writeName(name);
        generator.writeString(value);
    }

    private void writeFieldName(String name) throws IOException {
        generator.writeName(name);
    }

    private void writeArrayFieldStart(String name) throws IOException {
        generator.writeName(name);
        generator.writeStartArray();
    }

    private void writeDecimalField(String name, Long value) throws IOException {
        generator.writeName(name);
        if (value == null) {
            generator.writeNull();
        } else {
            generator.writeString(Long.toString(value));
        }
    }

    private void writeJsonNode(String name, JsonNode value) throws IOException {
        generator.writeName(name);
        if (value == null) {
            generator.writeNull();
        } else {
            generator.writeTree(value);
        }
    }

    /**
     * Redacts credential-like fields and, when configured, replaces raw SQL
     * with an omission reason.
     */
    private JsonNode redact(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            var copy = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (isCredentialField(key)) {
                    copy.set(key, objectMapper.getNodeFactory().textNode("[REDACTED]"));
                } else if (redactUnsafeSql && isSqlField(key) && value.isTextual()) {
                    copy.set(key, objectMapper.getNodeFactory().textNode("[OMITTED: unsafe SQL redacted by export policy]"));
                } else {
                    copy.set(key, redact(value));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                copy.add(redact(child));
            }
            return copy;
        }
        return node;
    }

    private static boolean isCredentialField(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("parola")
                || lower.contains("secret") || lower.contains("credential")
                || lower.contains("token") || lower.contains("hash")
                || lower.contains("cipher") || lower.contains("privatekey")
                || lower.contains("apikey") || lower.contains("api_key");
    }

    private static boolean isSqlField(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("sql") || lower.equals("executedsql") || lower.equals("rawsql")
                || lower.equals("statement") || lower.endsWith("sql");
    }

    public static Builder builder(ObjectMapper objectMapper) {
        return new Builder(objectMapper);
    }

    public static final class Builder {
        private final ObjectMapper objectMapper;
        private OutputStream outputStream;
        private Instant snapshotAt;
        private List<ExportColumn> columns = List.of();
        private boolean redactUnsafeSql = true;

        private Builder(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        public Builder outputStream(OutputStream outputStream) {
            this.outputStream = outputStream;
            return this;
        }

        public Builder snapshotAt(Instant snapshotAt) {
            this.snapshotAt = snapshotAt;
            return this;
        }

        public Builder columns(List<ExportColumn> columns) {
            this.columns = columns == null ? List.of() : columns;
            return this;
        }

        public Builder redactUnsafeSql(boolean redact) {
            this.redactUnsafeSql = redact;
            return this;
        }

        public JsonExportWriter build() throws IOException {
            return new JsonExportWriterImpl(this);
        }
    }
}

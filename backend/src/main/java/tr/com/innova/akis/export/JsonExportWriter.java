package tr.com.innova.akis.export;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportScope;

/**
 * Abstraction for streaming the akis.data-export v1 envelope incrementally.
 */
public interface JsonExportWriter extends AutoCloseable {

    String FORMAT = "akis.data-export";
    String FORMAT_VERSION = "1";

    void writeHeader(
            String dataset,
            String resourceId,
            ExportScope scope,
            String locale,
            String timeZone,
            List<ExportFilter> filters,
            String providerDescription) throws IOException;

    void writeRecord(JsonNode record) throws IOException;

    void writeRecord(java.util.function.Consumer<JsonObjectBuilder> builder) throws IOException;

    /** Writes one record directly to the underlying JSON stream, including nested arrays. */
    void writeStreamingRecord(java.util.function.Consumer<JsonRecordWriter> writer) throws IOException;

    void writeSummary(long processedRows, Long selected, Long inserted, Long updated, Long deleted) throws IOException;

    void writeDetails(JsonNode details) throws IOException;

    @Override
    void close() throws IOException;

    long recordCount();

    interface JsonRecordWriter {
        void put(String key, String value) throws IOException;

        void put(String key, long value) throws IOException;

        void put(String key, Long value) throws IOException;

        void put(String key, JsonNode value) throws IOException;

        void startArray(String key) throws IOException;

        void writeArrayValue(JsonNode value) throws IOException;

        void endArray() throws IOException;

        void startObject(String key) throws IOException;

        void endObject() throws IOException;
    }

    final class JsonObjectBuilder {

        private final tools.jackson.databind.ObjectMapper objectMapper;
        private final tools.jackson.databind.node.ObjectNode node;

        JsonObjectBuilder(tools.jackson.databind.ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            this.node = objectMapper.createObjectNode();
        }

        public JsonObjectBuilder put(String key, String value) {
            node.put(key, value);
            return this;
        }

        public JsonObjectBuilder put(String key, Long value) {
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, value);
            }
            return this;
        }

        public JsonObjectBuilder put(String key, Integer value) {
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, value);
            }
            return this;
        }

        public JsonObjectBuilder put(String key, Boolean value) {
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, value);
            }
            return this;
        }

        public JsonObjectBuilder put(String key, BigDecimal value) {
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, value.toPlainString());
            }
            return this;
        }

        public JsonObjectBuilder put(String key, JsonNode value) {
            node.set(key, value == null ? objectMapper.nullNode() : value);
            return this;
        }

        public JsonObjectBuilder put(String key, Instant value) {
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .format(value.atOffset(java.time.ZoneOffset.UTC)));
            }
            return this;
        }

        public JsonObjectBuilder put(String key, OffsetDateTime value) {
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value));
            }
            return this;
        }

        public JsonNode build() {
            return node;
        }
    }
}

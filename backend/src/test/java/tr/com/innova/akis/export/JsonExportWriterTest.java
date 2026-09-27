package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.export.ExportModels.ExportColumn;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportScope;

class JsonExportWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void envelopeStructureIsValid() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonExportWriter writer = JsonExportWriterImpl.builder(objectMapper)
                .outputStream(out)
                .columns(List.of(new ExportColumn("id", "ID")))
                .build()) {
            writer.writeHeader("runs", "run-history", ExportScope.ALL,
                    "tr-TR", "Europe/Istanbul", List.of(), "desc");
            writer.writeRecord(b -> b.put("id", "1"));
            writer.writeSummary(1, null, null, null, null);
        }

        JsonNode root = objectMapper.readTree(out.toByteArray());
        assertEquals("akis.data-export", root.get("format").asText());
        assertEquals("1", root.get("formatVersion").asText());
        assertEquals("runs", root.get("dataset").asText());
        assertEquals("ALL", root.get("scope").asText());
        assertTrue(root.has("generatedAt"));
        assertTrue(root.has("snapshotAt"));
        assertEquals("desc", root.get("description").asText());
        assertTrue(root.get("records").isArray());
        assertEquals(1, root.get("records").size());
        assertTrue(root.has("summary"));
    }

    @Test
    void decimalsAndCountersAreDecimalStrings() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonExportWriter writer = JsonExportWriterImpl.builder(objectMapper)
                .outputStream(out)
                .columns(List.of())
                .build()) {
            writer.writeHeader("runs", "run-history", ExportScope.ALL,
                    "en-US", "UTC", List.of(), "desc");
            writer.writeRecord(b -> b
                    .put("big", Long.MAX_VALUE)
                    .put("dec", new BigDecimal("12345678901234567890.123"))
                    .put("count", 42L));
            writer.writeSummary(100, 10L, 20L, 30L, 40L);
        }

        JsonNode root = objectMapper.readTree(out.toByteArray());
        JsonNode record = root.get("records").get(0);
        assertEquals("9223372036854775807", record.get("big").asText());
        assertEquals("12345678901234567890.123", record.get("dec").asText());
        assertEquals("42", record.get("count").asText());

        JsonNode summary = root.get("summary");
        assertEquals("100", summary.get("processedRows").asText());
        assertEquals("10", summary.get("selectedRows").asText());
        assertEquals("20", summary.get("insertedRows").asText());
        assertEquals("30", summary.get("updatedRows").asText());
        assertEquals("40", summary.get("deletedRows").asText());
    }

    @Test
    void redactsCredentialsAndUnsafeSql() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonExportWriter writer = JsonExportWriterImpl.builder(objectMapper)
                .outputStream(out)
                .columns(List.of())
                .redactUnsafeSql(true)
                .build()) {
            writer.writeHeader("runs", "run-history", ExportScope.ALL,
                    "en-US", "UTC", List.of(), "desc");
            ObjectNode record = objectMapper.createObjectNode()
                    .put("password", "secret")
                    .put("apiKey", "key")
                    .put("executedSql", "select * from users")
                    .put("safe", "visible");
            writer.writeRecord(record);
            writer.writeSummary(1, null, null, null, null);
        }

        JsonNode root = objectMapper.readTree(out.toByteArray());
        JsonNode node = root.get("records").get(0);
        assertEquals("[REDACTED]", node.get("password").asText());
        assertEquals("[REDACTED]", node.get("apiKey").asText());
        assertTrue(node.get("executedSql").asText().contains("OMITTED"));
        assertEquals("visible", node.get("safe").asText());
    }

    @Test
    void filterSnapshotIsPreserved() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportFilter filter = new ExportFilter("status", "eq", objectMapper.valueToTree("BASARILI"));
        try (JsonExportWriter writer = JsonExportWriterImpl.builder(objectMapper)
                .outputStream(out)
                .columns(List.of())
                .build()) {
            writer.writeHeader("runs", "run-history", ExportScope.FILTERED,
                    "tr-TR", "UTC", List.of(filter), "desc");
            writer.writeSummary(0, null, null, null, null);
        }

        JsonNode root = objectMapper.readTree(out.toByteArray());
        JsonNode filters = root.get("query").get("filters");
        assertEquals(1, filters.size());
        assertEquals("status", filters.get(0).get("field").asText());
        assertEquals("BASARILI", filters.get(0).get("value").asText());
    }
}

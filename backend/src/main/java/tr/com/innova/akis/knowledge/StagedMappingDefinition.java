package tr.com.innova.akis.knowledge;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Strict additional schema-3 contract; legacy dataset/column structure remains compatible with the editor. */
public record StagedMappingDefinition(UUID logicalSchemaUuid, Map<String, Pin> modules, Options options) {
    public record Pin(UUID versionUuid, String contentHash) { }
    public record Options(int batchRows, int fetchRows, long maxRows, long maxBytes, boolean allowEmptySource) {
        public Options {
            if (batchRows < 1 || batchRows > 5000 || fetchRows < 1 || fetchRows > 5000
                    || maxRows < 1 || maxRows > 100_000_000 || maxBytes < 1 || maxBytes > 1_099_511_627_776L)
                throw invalid("Aktarım sınırları izin verilen aralığın dışında.");
        }
    }
    public StagedMappingDefinition { modules = Map.copyOf(modules); }
    public static StagedMappingDefinition parse(JsonNode root) {
        fields(root, Set.of("datasets", "columnMappings", "writeStrategy", "staging", "modules", "options", "ui", "layout"));
        var datasets = root.path("datasets");
        if (!datasets.isArray() || datasets.size() != 2) throw invalid("Tek kaynak ve tek hedef gerekir.");
        Set<String> roles = new HashSet<>();
        for (var dataset : datasets) {
            fields(dataset, Set.of("id", "role", "ui", "layout"));
            roles.add(dataset.path("role").asText());
        }
        if (!roles.equals(Set.of("SOURCE", "TARGET"))) throw invalid("SOURCE ve TARGET zorunludur.");
        var strategy = root.path("writeStrategy");
        fields(strategy, Set.of("kind"));
        if (!"ATOMIC_DELETE_INSERT".equals(strategy.path("kind").asText())) throw invalid("İlk KM hedefin tamamını atomik yeniler; diğer stratejiler desteklenmiyor.");
        var columns = root.path("columnMappings");
        if (!columns.isArray() || columns.isEmpty() || columns.size() > 256) throw invalid("1–256 doğrudan kolon eşlemesi gerekir.");
        for (var column : columns) {
            fields(column, Set.of("source", "target", "ui"));
            for (String side : List.of("source", "target")) {
                fields(column.path(side), Set.of("dataset", "column"));
                identifier(column.path(side).path("column").asText());
            }
        }
        fields(root.path("staging"), Set.of("logicalSchemaUuid"));
        UUID logical = uuid(root.path("staging").path("logicalSchemaUuid").asText());
        var moduleNodes = root.path("modules");
        fields(moduleNodes, Set.of("loading", "integration", "checking"));
        Map<String, Pin> pins = new LinkedHashMap<>();
        for (String role : List.of("loading", "checking", "integration")) {
            if (role.equals("checking") && !moduleNodes.has(role)) continue;
            var pin = moduleNodes.path(role);
            fields(pin, Set.of("versionUuid", "contentHash"));
            String hash = pin.path("contentHash").asText();
            if (!hash.matches("[0-9a-f]{64}")) throw invalid("KM içerik özeti zorunludur.");
            pins.put(role, new Pin(uuid(pin.path("versionUuid").asText()), hash));
        }
        var options = root.path("options");
        fields(options, Set.of("batchRows", "fetchRows", "maxRows", "maxBytes", "allowEmptySource"));
        if (!options.path("allowEmptySource").isBoolean()) throw invalid("Boş kaynak politikası açıkça seçilmelidir.");
        return new StagedMappingDefinition(logical, pins, new Options(
                (int) number(options, "batchRows", 1, 5000), (int) number(options, "fetchRows", 1, 5000),
                number(options, "maxRows", 1, 100_000_000), number(options, "maxBytes", 1, 1_099_511_627_776L),
                options.path("allowEmptySource").booleanValue()));
    }
    public static String identifier(String name) {
        if (name == null || !name.matches("[A-Z][A-Z0-9_$#]{0,127}")) throw invalid("Yalnız katalogdaki standart Oracle adları desteklenir.");
        return name;
    }
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw new IllegalArgumentException(); return id; }
        catch (IllegalArgumentException ex) { throw invalid("Geçerli sürüm/şema UUID'si gerekir."); }
    }
    private static long number(JsonNode node, String key, long min, long max) {
        var value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max)
            throw invalid("Geçersiz sınır: " + key);
        return value.longValue();
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject() || !allowed.containsAll(node.propertyNames())) throw invalid("Eksik nesne veya desteklenmeyen KM alanı.");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

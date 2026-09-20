package tr.com.innova.akis.knowledge;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Strict additional schema-3 contract; legacy dataset/column structure remains compatible with the editor. */
public record StagedMappingDefinition(UUID logicalSchemaUuid, Map<String, Pin> modules,
        Map<String, Map<String, Object>> moduleOptions, Options options,
        List<ObjectRef> sources, ObjectRef target, List<Join> joins, List<Filter> filters) {
    public record Pin(UUID versionUuid, String contentHash) { }
    public record ObjectRef(String id, String alias, UUID dataObjectUuid, UUID schemaSnapshotUuid) { }
    public record ColumnRef(String object, String column) { }
    public record Join(String type, ColumnRef left, ColumnRef right) { }
    public record Filter(String scope, String object, String column, String operator, String value, JsonNode predicate) {
        public Filter(String scope, String object, String column, String operator, String value) {
            this(scope, object, column, operator, value, null);
        }
        public Filter {
            if (predicate != null) {
                if (column != null || operator != null || value != null) throw invalid("Filtre SQL ifadesi ve eski operatör alanları birlikte kullanılamaz.");
                predicate = predicate.deepCopy();
            }
        }
        @Override public JsonNode predicate() { return predicate == null ? null : predicate.deepCopy(); }
    }
    public record Options(int batchRows, int fetchRows, long maxRows, long maxBytes, boolean allowEmptySource) {
        public Options {
            if (batchRows < 1 || batchRows > 5000 || fetchRows < 1 || fetchRows > 5000
                    || maxRows < 1 || maxRows > 100_000_000 || maxBytes < 1 || maxBytes > 1_099_511_627_776L)
                throw invalid("Aktarım sınırları izin verilen aralığın dışında.");
        }
    }
    public StagedMappingDefinition(UUID logicalSchemaUuid, Map<String, Pin> modules, Options options) {
        this(logicalSchemaUuid, modules, Map.of(), options, List.of(), null, List.of(), List.of());
    }
    public StagedMappingDefinition(UUID logicalSchemaUuid, Map<String, Pin> modules,
            Map<String, Map<String, Object>> moduleOptions, Options options) {
        this(logicalSchemaUuid, modules, moduleOptions, options, List.of(), null, List.of(), List.of());
    }
    public StagedMappingDefinition {
        modules = Map.copyOf(modules);
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        moduleOptions.forEach((role, values) -> copy.put(role, Map.copyOf(values)));
        moduleOptions = Map.copyOf(copy);
        sources = List.copyOf(sources);
        joins = List.copyOf(joins);
        filters = List.copyOf(filters);
    }
    public Map<String, Object> optionsFor(String role) { return moduleOptions.getOrDefault(role, Map.of()); }
    public boolean booleanOption(String role, String key) { return Boolean.TRUE.equals(optionsFor(role).get(key)); }
    public String stringOption(String role, String key) {
        Object value = optionsFor(role).get(key); return value instanceof String text ? text : "";
    }
    public static StagedMappingDefinition parse(JsonNode root) {
        boolean direct = root.has("sources");
        fields(root, direct
                ? Set.of("sources", "target", "joins", "filters", "columnMappings", "staging", "modules", "moduleOptions", "options", "ui", "layout")
                : Set.of("datasets", "columnMappings", "writeStrategy", "staging", "modules", "moduleOptions", "options", "ui", "layout"));
        List<ObjectRef> sources = new ArrayList<>(); ObjectRef target = null; List<Join> joins = new ArrayList<>(); List<Filter> filters = new ArrayList<>();
        if (direct) {
            var sourceNodes = root.path("sources");
            if (!sourceNodes.isArray() || sourceNodes.isEmpty() || sourceNodes.size() > 16) throw invalid("1-16 kaynak gerekir.");
            Set<String> ids = new HashSet<>(); Set<String> aliases = new HashSet<>();
            for (var node : sourceNodes) {
                ObjectRef ref = objectRef(node);
                if (!ids.add(ref.id())) throw invalid("Kaynak kimliği tekrar edemez.");
                if (!aliases.add(ref.alias())) throw invalid("Kaynak takma adları benzersiz olmalıdır.");
                sources.add(ref);
            }
            target = objectRef(root.path("target")); if (!ids.add(target.id())) throw invalid("Hedef kimliği kaynaklardan farklı olmalıdır.");
            var joinNodes = root.path("joins"); if (!joinNodes.isArray() || joinNodes.size()>256) throw invalid("En fazla 256 koşul içeren join listesi zorunludur.");
            for (var node : joinNodes) {
                fields(node, Set.of("id", "type", "left", "right"));
                String type=node.path("type").asText(); if(!Set.of("INNER","LEFT","RIGHT","FULL").contains(type)) throw invalid("Join türü geçersiz.");
                ColumnRef left=columnRef(node.path("left")),right=columnRef(node.path("right"));
                if(!ids.contains(left.object()) || !ids.contains(right.object()) || left.object().equals(target.id()) || right.object().equals(target.id()) || left.object().equals(right.object())) throw invalid("Join iki farklı kaynağı bağlamalıdır.");
                joins.add(new Join(type,left,right));
            }
            StagedQueryGraph.compile(sources.stream().map(ObjectRef::id).toList(),joins);
            var filterNodes=root.path("filters"); if(!filterNodes.isArray() || filterNodes.size()>256) throw invalid("En fazla 256 koşul içeren filtre listesi zorunludur.");
            for(var node:filterNodes){
                String scope=node.path("scope").asText(); String object=node.path("object").asText();
                if(!Set.of("SOURCE","GLOBAL").contains(scope) || !ids.contains(object) || object.equals(target.id())) throw invalid("Filtre kapsamı veya kaynağı geçersiz.");
                if(node.has("predicate")) {
                    fields(node,Set.of("id","scope","object","predicate"));
                    Map<String,String> catalog=new LinkedHashMap<>();
                    sources.stream().filter(source->"GLOBAL".equals(scope) || source.id().equals(object)).forEach(source->catalog.put(source.id(),source.alias()));
                    MappingSql.validate(node.path("predicate"),catalog,true);
                    filters.add(new Filter(scope,object,null,null,null,node.path("predicate")));
                    continue;
                }
                fields(node,Set.of("id","scope","object","column","operator","value")); String column=identifier(node.path("column").asText()); String operator=node.path("operator").asText(); String value=node.has("value")?node.path("value").asText():null;
                if(!Set.of("SOURCE","GLOBAL").contains(scope) || !ids.contains(object) || object.equals(target.id()) || !Set.of("EQUALS","NOT_EQUALS","GREATER_THAN","LESS_THAN","LIKE","IS_NULL","IS_NOT_NULL").contains(operator) || (value!=null && value.length()>1024) || (!Set.of("IS_NULL","IS_NOT_NULL").contains(operator) && (value==null || value.isBlank()))) throw invalid("Filtre sözleşmesi geçersiz.");
                filters.add(new Filter(scope,object,column,operator,value)); }
        } else {
            var datasets = root.path("datasets");
            if (!datasets.isArray() || datasets.size() != 2) throw invalid("Tek kaynak ve tek hedef gerekir.");
            Set<String> roles = new HashSet<>(); for (var dataset : datasets) { fields(dataset, Set.of("id", "role", "ui", "layout")); roles.add(dataset.path("role").asText()); }
            if (!roles.equals(Set.of("SOURCE", "TARGET"))) throw invalid("SOURCE ve TARGET zorunludur.");
            var strategy = root.path("writeStrategy"); fields(strategy, Set.of("kind"));
            if (!"ATOMIC_DELETE_INSERT".equals(strategy.path("kind").asText())) throw invalid("KM hedef yazma davranışı desteklenmiyor.");
        }
        var columns = root.path("columnMappings");
        if (!columns.isArray() || columns.isEmpty() || columns.size() > 256) throw invalid("1 ile 256 arasında kolon eşlemesi gerekir.");
        Map<String,String> sourceAliases=new LinkedHashMap<>();sources.forEach(source->sourceAliases.put(source.id(),source.alias()));
        for (var column : columns) {
            fields(column, direct?Set.of("source", "expression", "target", "ui"):Set.of("source", "target", "ui"));
            boolean expression=column.hasNonNull("expression");
            if(expression==column.hasNonNull("source")) throw invalid("Tam olarak bir kaynak veya ifade gerekir.");
            if(expression) MappingSql.validate(column.path("expression"),sourceAliases,false);
            for (String side : expression?List.of("target"):List.of("source", "target")) {
                fields(column.path(side), direct ? Set.of("object", "column") : Set.of("dataset", "column"));
                identifier(column.path(side).path("column").asText());
            }
        }
        UUID logical = null;
        if(!direct) { fields(root.path("staging"), Set.of("logicalSchemaUuid")); logical = uuid(root.path("staging").path("logicalSchemaUuid").asText()); }
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
        Map<String, Map<String, Object>> moduleOptions = new LinkedHashMap<>();
        var optionGroups = root.path("moduleOptions");
        if (!optionGroups.isMissingNode()) {
            fields(optionGroups, Set.of("loading", "integration", "checking"));
            optionGroups.properties().forEach(entry -> {
                if (!pins.containsKey(entry.getKey()) || !entry.getValue().isObject()) throw invalid("KM seçenek grubu seçili modülle uyuşmuyor.");
                Map<String, Object> values = new LinkedHashMap<>();
                entry.getValue().properties().forEach(option -> {
                    if (!option.getKey().matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid("KM seçenek anahtarı geçersiz.");
                    JsonNode value = option.getValue();
                    Object scalar;
                    if (value.isBoolean()) scalar = value.booleanValue();
                    else if (value.isIntegralNumber() && value.canConvertToLong()) scalar = value.longValue();
                    else if (value.isTextual() && value.asText().length() <= 1024) scalar = value.asText();
                    else throw invalid("KM seçenek değeri boolean, tamsayı veya kısa metin olmalıdır.");
                    values.put(option.getKey(), scalar);
                });
                moduleOptions.put(entry.getKey(), values);
            });
        }
        var options = root.path("options");
        fields(options, Set.of("batchRows", "fetchRows", "maxRows", "maxBytes", "allowEmptySource"));
        if (!options.path("allowEmptySource").isBoolean()) throw invalid("Boş kaynak politikası açıkça seçilmelidir.");
        return new StagedMappingDefinition(logical, pins, moduleOptions, new Options(
                (int) number(options, "batchRows", 1, 5000), (int) number(options, "fetchRows", 1, 5000),
                number(options, "maxRows", 1, 100_000_000), number(options, "maxBytes", 1, 1_099_511_627_776L),
                options.path("allowEmptySource").booleanValue()), sources, target, joins, filters);
    }
    private static ObjectRef objectRef(JsonNode node) { fields(node,Set.of("id","alias","dataObjectUuid","schemaSnapshotUuid")); String id=node.path("id").asText(); identifier(id); String alias=node.path("alias").asText(); identifier(alias); return new ObjectRef(id,alias,uuid(node.path("dataObjectUuid").asText()),uuid(node.path("schemaSnapshotUuid").asText())); }
    private static ColumnRef columnRef(JsonNode node) { fields(node,Set.of("object","column")); return new ColumnRef(identifier(node.path("object").asText()),identifier(node.path("column").asText())); }
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

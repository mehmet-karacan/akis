package tr.com.innova.akis.knowledge;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;

@Service
public class KnowledgeModuleRegistry {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final DefinitionContentValidator validator;
    public KnowledgeModuleRegistry(JdbcClient jdbc, ObjectMapper mapper, DefinitionContentValidator validator) {
        this.jdbc = jdbc; this.mapper = mapper; this.validator = validator;
    }
    public record Module(long id, UUID versionUuid, String contentHash, String source, String kind,
            String sourceTechnology, String targetTechnology,
            JsonNode optionSchema, Map<String, Object> options) {
        public Module(long id, UUID versionUuid, String contentHash, String source, String kind,
                JsonNode optionSchema, Map<String, Object> options) {
            this(id, versionUuid, contentHash, source, kind, null, null, optionSchema, options);
        }
        public Module { optionSchema = optionSchema.deepCopy(); options = Map.copyOf(options); }
        @Override public JsonNode optionSchema() { return optionSchema.deepCopy(); }
    }
    public record Bundle(Map<String, Module> modules, AkisKmInterpreter.Plan plan) {
        public Bundle { modules = Map.copyOf(modules); }
    }
    @Transactional(readOnly = true)
    public Bundle resolve(long projectId, StagedMappingDefinition definition) {
        Map<String, Module> modules = new LinkedHashMap<>();
        for (var entry : definition.modules().entrySet()) {
            String kind = switch (entry.getKey()) { case "loading" -> "LKM"; case "integration" -> "IKM"; case "checking" -> "CKM"; default -> throw rejected(); };
            var pin = entry.getValue();
            Module loaded = jdbc.sql("""
                select v.id,v.uuid,v.icerik_ozeti,v.icerik,v.sema_surumu from akis.tanim_surumu v
                join akis.tanim t on t.id=v.tanim_id and t.proje_id=v.proje_id
                where v.proje_id=:project and v.uuid=:version and t.tur='KNOWLEDGE_MODULE'
                  and t.arsivlenme_zamani is null
                """).param("project", projectId).param("version", pin.versionUuid()).query((rs, n) -> {
                    JsonNode content = mapper.readTree(rs.getString("icerik"));
                    String hash = rs.getString("icerik_ozeti");
                    if (rs.getInt("sema_surumu") != 2 || !hash.equals(pin.contentHash()) || !hash.equals(KmCanonical.hash(mapper, content))) throw rejected();
                    validator.validate(DefinitionType.KNOWLEDGE_MODULE, 2, content);
                    if (!kind.equals(content.path("kmType").asText())) throw rejected();
                    String source = content.path("source").asText();
                    JsonNode technology = content.path("technology");
                    return new Module(rs.getLong("id"), pin.versionUuid(), hash, source, kind,
                            technology.path("source").textValue(), technology.path("target").textValue(),
                            optionSchema(mapper, source, content.path("optionSchema")), Map.of());
                }).optional().orElseThrow(KnowledgeModuleRegistry::rejected);
            Map<String, Object> values = validateOptions(kind, loaded.optionSchema(), definition.optionsFor(entry.getKey()));
            Module module = new Module(loaded.id(), loaded.versionUuid(), loaded.contentHash(), loaded.source(), loaded.kind(),
                    loaded.sourceTechnology(), loaded.targetTechnology(), loaded.optionSchema(), values);
            modules.put(entry.getKey(), module);
        }
        var checking = modules.get("checking");
        Map<String, Map<String, Object>> resolvedOptions = new LinkedHashMap<>();
        modules.forEach((role, module) -> resolvedOptions.put(role, module.options()));
        var plan = AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(modules.get("loading").source(),
                checking == null ? null : checking.source(), modules.get("integration").source(), resolvedOptions));
        if (!plan.slots().equals(Set.of("WORK_SOURCE_1"))) throw rejected();
        return new Bundle(modules, plan);
    }

    public static void requireCompatible(Bundle bundle, Set<String> sourceTechnologies, String targetTechnology) {
        requireCompatible(bundle.modules().get("loading"), sourceTechnologies, targetTechnology);
        requireCompatible(bundle.modules().get("checking"), Set.of(targetTechnology), targetTechnology);
        requireCompatible(bundle.modules().get("integration"), Set.of(targetTechnology), targetTechnology);
    }

    private static void requireCompatible(Module module, Set<String> sourceTechnologies, String targetTechnology) {
        if (module == null) return;
        boolean sourceMatches = module.sourceTechnology() == null || sourceTechnologies.stream().allMatch(module.sourceTechnology()::equals);
        boolean targetMatches = module.targetTechnology() == null || module.targetTechnology().equals(targetTechnology);
        if (!sourceMatches || !targetMatches) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "KM_TECHNOLOGY_MISMATCH",
                "Seçilen " + module.kind() + " sürümü " + String.join(",", sourceTechnologies) + " → " + targetTechnology + " teknoloji akışıyla uyumlu değildir.");
    }
    @Transactional
    public void link(long projectId, UUID mappingVersionUuid, Bundle bundle) {
        for (var module : bundle.modules().values()) jdbc.sql("""
            insert into akis.tanim_bagimliligi(proje_id,kaynak_tanim_surumu_id,hedef_tanim_surumu_id,iliski_turu)
            select :project,id,:target,'MODUL_KULLANIR' from akis.tanim_surumu where proje_id=:project and uuid=:source
            on conflict(kaynak_tanim_surumu_id,hedef_tanim_surumu_id,iliski_turu) do nothing
            """).param("project", projectId).param("source", mappingVersionUuid).param("target", module.id()).update();
    }
    private static ApiException rejected() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "KM_VERSION_REJECTED",
                "KM sürümü, türü, proje kapsamı, slotu veya içerik özeti geçersiz. Aynı projede doğrulanmış AKIS_KM sürümünü seçin.");
    }

    private static JsonNode optionSchema(ObjectMapper mapper, String source, JsonNode legacy) {
        var program = AkisKmLanguage.parse(source);
        if (AkisKmLanguage.LEGACY_VERSION.equals(program.language())) return legacy;
        var result = mapper.createArrayNode();
        for (var option : program.options()) {
            var item = result.addObject();
            item.put("key", option.key());
            item.put("label", option.key().replace('_', ' '));
            item.put("type", option.type().name());
            item.put("required", option.required());
            if (option.defaultValue() != null) switch (option.type()) {
                case BOOLEAN -> item.put("defaultValue", Boolean.parseBoolean(option.defaultValue()));
                case INTEGER -> item.put("defaultValue", Long.parseLong(option.defaultValue()));
                default -> item.put("defaultValue", option.defaultValue());
            }
            if (!option.values().isEmpty()) {
                var values = item.putArray("values");
                option.values().forEach(values::add);
            }
        }
        return result;
    }

    /** Revalidate immutable runtime values without querying mutable registry metadata or adding defaults. */
    public static void validatePinnedOptions(ObjectMapper mapper, String kind, String source,
            JsonNode legacySchema, Map<String, Object> resolved) {
        var program = AkisKmLanguage.parse(source);
        if (!program.kind().name().equals(kind)) throw rejected();
        JsonNode schema = optionSchema(mapper, source, legacySchema);
        // Legacy plans without options remain executable. Configurable legacy plans
        // must carry their versioned declaration; guessing types would bypass validation.
        if (schema.isMissingNode() || schema.isNull()) {
            if (!resolved.isEmpty()) throw rejected();
            schema = mapper.createArrayNode();
        }
        if (!schema.isArray() || !validateOptions(kind, schema, resolved).equals(resolved)) throw rejected();
    }

    static Map<String, Object> validateOptions(String kind, JsonNode schema, Map<String, Object> supplied) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        if (!schema.isMissingNode()) for (JsonNode definition : schema) {
            String key = definition.path("key").asText();
            String type = definition.path("type").asText();
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || !known.add(key)) throw rejected();
            Object value = supplied.get(key);
            if (value == null && definition.has("defaultValue")) {
                JsonNode fallback = definition.path("defaultValue");
                if (fallback.isBoolean()) value = fallback.booleanValue();
                else if (fallback.isIntegralNumber()) {
                    if (!fallback.canConvertToLong()) throw rejected();
                    value = fallback.longValue();
                }
                else if (fallback.isTextual()) value = fallback.asText();
            }
            if (value == null) {
                if (definition.path("required").asBoolean(false)) throw rejected();
                continue;
            }
            boolean valid = switch (type) {
                case "BOOLEAN" -> value instanceof Boolean;
                case "INTEGER" -> KmIntegerValue.valid(value);
                case "STRING" -> value instanceof String text && text.length() <= 1024;
                case "SQL_HINT" -> value instanceof String text && validHint(text);
                case "IDENTIFIER" -> value instanceof String text && text.matches("[A-Za-z][A-Za-z0-9_$#]{0,127}");
                case "COLUMN_LIST" -> value instanceof String text && text.matches("[A-Za-z][A-Za-z0-9_$#]*(\\s*,\\s*[A-Za-z][A-Za-z0-9_$#]*){0,63}");
                case "ENUM" -> value instanceof String text && definition.path("values").valueStream().anyMatch(candidate -> candidate.asText().equals(text));
                default -> false;
            };
            if (!valid) throw rejected();
            resolved.put(key, value);
        }
        if (!known.containsAll(supplied.keySet())) throw rejected();
        if (resolved.containsKey("DISTINCT") && (!"LKM".equals(kind) || !(resolved.get("DISTINCT") instanceof Boolean))) throw rejected();
        if (resolved.containsKey("ORACLE_HINT") && (!Set.of("LKM", "IKM").contains(kind)
                || !(resolved.get("ORACLE_HINT") instanceof String hint) || !validHint(hint))) throw rejected();
        if (!"IKM".equals(kind) && Set.of("WRITE_MODE", "KEY_COLUMNS", "TRUNCATE_TARGET").stream().anyMatch(resolved::containsKey)) throw rejected();
        if (resolved.containsKey("TRUNCATE_TARGET") && !(resolved.get("TRUNCATE_TARGET") instanceof Boolean)) throw rejected();
        if (resolved.containsKey("KEY_COLUMNS") && !(resolved.get("KEY_COLUMNS") instanceof String keys
                && keys.matches("[A-Za-z][A-Za-z0-9_$#]*(\\s*,\\s*[A-Za-z][A-Za-z0-9_$#]*){0,63}"))) throw rejected();
        if ("IKM".equals(kind)) {
            String mode=String.valueOf(resolved.getOrDefault("WRITE_MODE","ATOMIC_DELETE_INSERT"));
            if (!Set.of("APPEND","MERGE","TRUNCATE_LOAD","ATOMIC_DELETE_INSERT").contains(mode)) throw rejected();
            if ("MERGE".equals(mode) && !(resolved.get("KEY_COLUMNS") instanceof String keys && !keys.isBlank())) throw rejected();
            if ("TRUNCATE_LOAD".equals(mode) && !Boolean.TRUE.equals(resolved.get("TRUNCATE_TARGET"))) throw rejected();
        }
        return Map.copyOf(resolved);
    }

    private static boolean validHint(String value) {
        return value.length() <= 256 && value.matches("[A-Za-z0-9_$#., ()+\\-]*") && !value.contains("--") && !value.contains("/*") && !value.contains("*/");
    }
}

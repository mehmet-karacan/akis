package tr.com.innova.akis.scenario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.scenario.ScenarioModels.CompiledPlan;
import tr.com.innova.akis.scenario.ScenarioModels.SourceVersion;

@Component
final class ScenarioPlanCompiler {

    static final int PLAN_VERSION = 2;
    private static final Set<DefinitionType> EXECUTABLE_TYPES = Set.of(
            DefinitionType.MAPPING,
            DefinitionType.PACKAGE,
            DefinitionType.PROCEDURE,
            DefinitionType.LOAD_PLAN);

    private final ObjectMapper objectMapper;
    private final DefinitionContentValidator definitionValidator;
    private final SecretValueSanitizer secretSanitizer;

    ScenarioPlanCompiler(
            ObjectMapper objectMapper,
            DefinitionContentValidator definitionValidator,
            SecretValueSanitizer secretSanitizer) {
        this.objectMapper = objectMapper;
        this.definitionValidator = definitionValidator;
        this.secretSanitizer = secretSanitizer;
    }

    CompiledPlan compile(SourceVersion source) {
        if (!EXECUTABLE_TYPES.contains(source.definitionType())) {
            throw validation("Bu tanım türü bağımsız bir Scenario olarak derlenemez.");
        }
        if (source.content() == null || !source.content().isObject()) {
            throw validation("Tanım sürümü içeriği JSON nesnesi olmalıdır.");
        }
        if (!secretSanitizer.sensitivePaths(source.content()).isEmpty()) {
            throw validation("Tanım içeriği secret değer taşıyamaz; yalnız güvenli referans kullanın.");
        }
        definitionValidator.validate(
                source.definitionType(), source.schemaVersion(), source.content());
        for (String field : source.definitionType().requiredContentFields()) {
            if (!source.content().has(field) || source.content().get(field).isNull()) {
                throw validation("Tanım sürümünde zorunlu alan eksik: " + field);
            }
        }

        JsonNode canonicalContent = canonicalize(source.content());
        String calculatedContentHash = sha256(canonicalContent.toString());
        if (!calculatedContentHash.equals(source.contentHash())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DEFINITION_VERSION_INTEGRITY_FAILED",
                    "Tanım sürümü özeti ile immutable içerik uyuşmuyor.");
        }

        ObjectNode sourceNode = objectMapper.createObjectNode();
        sourceNode.put("contentHash", source.contentHash());
        sourceNode.put("definitionType", source.definitionType().name());
        sourceNode.put("definitionUuid", source.definitionUuid().toString());
        sourceNode.put("definitionVersion", source.definitionVersion());
        sourceNode.put("definitionVersionUuid", source.versionUuid().toString());
        sourceNode.put("schemaVersion", source.schemaVersion());

        ObjectNode executable = objectMapper.createObjectNode();
        executable.put("kind", source.definitionType().name());
        executable.set("definition", canonicalContent);

        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("compiler", "AKIS");
        plan.put("compilerVersion", PLAN_VERSION);
        plan.set("executable", executable);
        plan.set("source", sourceNode);
        JsonNode canonicalPlan = canonicalize(plan);
        String planHash = sha256(canonicalPlan.toString());

        JsonNode parameterSchema = source.content().get("parameterSchema");
        if (parameterSchema == null) {
            parameterSchema = objectMapper.createObjectNode();
        }
        else if (!parameterSchema.isObject()) {
            throw validation("parameterSchema JSON nesnesi olmalıdır.");
        }
        parameterSchema = canonicalize(parameterSchema);

        ObjectNode validation = objectMapper.createObjectNode();
        validation.put("compiler", "AKIS");
        validation.put("contentHash", source.contentHash());
        validation.put("definitionType", source.definitionType().name());
        validation.put("planVersion", PLAN_VERSION);
        validation.put("result", "GECTI");

        return new CompiledPlan(
                PLAN_VERSION,
                planHash,
                canonicalPlan,
                parameterSchema,
                canonicalize(validation));
    }

    JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            var names = new ArrayList<String>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        return node.deepCopy();
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "SCENARIO_COMPILE_FAILED", message);
    }
}

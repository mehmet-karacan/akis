package tr.com.innova.akis.metadata;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** Bounded design-time AST contract. It neither executes SQL nor infers column types. */
final class MappingExpressionValidator {
    private enum Type { STRING, NUMBER, BOOLEAN, NULL, UNKNOWN }

    static void validate(JsonNode expression, Map<String, String> roles, String path) {
        inspect(expression, roles, path, 0, new int[] {1000});
    }

    private static Type inspect(JsonNode node, Map<String, String> roles, String path,
            int depth, int[] budget) {
        if (depth > 32 || --budget[0] < 0) throw invalid(path, "ifade boyutu sınırı aşıldı");
        if (node == null || !node.isObject()) throw invalid(path, "nesne olmalıdır");
        String kind = text(node, "kind", path);
        switch (kind) {
            case "COLUMN":
                keys(node, Set.of("kind", "dataset", "column"), path);
                String dataset = text(node, "dataset", path);
                text(node, "column", path);
                if (!"SOURCE".equals(roles.get(dataset))) {
                    throw invalid(path, "kolon SOURCE datasetine referans vermelidir");
                }
                return Type.UNKNOWN;
            case "LITERAL":
                keys(node, Set.of("kind", "value"), path);
                JsonNode value = node.get("value");
                if (value == null) throw invalid(path, "value alanı gereklidir");
                if (value.isNull()) return Type.NULL;
                if (value.isString()) return Type.STRING;
                if (value.isNumber()) return Type.NUMBER;
                if (value.isBoolean()) return Type.BOOLEAN;
                throw invalid(path, "literal yalnız metin, sayı, boolean veya null olabilir");
            case "CALL":
                keys(node, Set.of("kind", "function", "args"), path);
                String function = text(node, "function", path);
                boolean unary = Set.of("TRIM", "UPPER", "LOWER").contains(function);
                if (!unary && !"COALESCE".equals(function)) {
                    throw invalid(path, "desteklenmeyen fonksiyon: " + function);
                }
                JsonNode args = node.get("args");
                if (args == null || !args.isArray() || (unary ? args.size() != 1 : args.size() < 2)
                        || args.size() > 64) throw invalid(path, "geçersiz argüman sayısı");
                Type result = Type.NULL;
                boolean unknown = false;
                for (int i = 0; i < args.size(); i++) {
                    Type type = inspect(args.get(i), roles, path + ".args[" + i + "]", depth + 1, budget);
                    if (unary && type != Type.STRING && type != Type.NULL && type != Type.UNKNOWN) {
                        throw invalid(path, function + " metin argümanı gerektirir");
                    }
                    if (type == Type.UNKNOWN) { unknown = true; continue; }
                    if (type == Type.NULL) continue;
                    if (result != Type.NULL && result != type) {
                        throw invalid(path, "COALESCE literal tipleri uyumlu olmalıdır");
                    }
                    result = type;
                }
                return unary ? Type.STRING : unknown ? Type.UNKNOWN : result;
            default:
                throw invalid(path, "desteklenmeyen ifade türü: " + kind);
        }
    }

    private static String text(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalid(path + "." + field, "boş olmayan metin gereklidir");
        }
        return value.stringValue();
    }

    private static void keys(JsonNode node, Set<String> allowed, String path) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) throw invalid(path + "." + name, "desteklenmeyen alan");
        }
    }

    private static ApiException invalid(String path, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", path + ": " + message);
    }
}

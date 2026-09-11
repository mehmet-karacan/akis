package tr.com.innova.akis.projectbundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Defense in depth against credentials being smuggled inside definition JSON. */
@Component
public class SecretValueSanitizer {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "secretvalue", "token", "accesstoken",
            "refreshtoken", "apikey", "privatekey", "clientsecret", "credential",
            "credentials", "credentialvalue", "authorization");

    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?is)(-----BEGIN(?: [A-Z]+)? PRIVATE KEY-----"
                    + "|\\bIDENTIFIED\\s+BY\\b"
                    + "|\\bCONN(?:ECT)?\\s+(?!/)[^\\s/]+/[^\\s@]+@"
                    + "|\\bjdbc:oracle:thin:(?!@)[^:/\\s]+/[^@\\s]+@(?:/{2})?[^\\s]+"
                    + "|\\b[a-z][a-z0-9+.-]*://[^/\\s:@]+:[^@\\s/]+@"
                    + "|(?:^|[?&;\\s])(?:password|passwd|pwd|token|api[_-]?key|client[_-]?secret)\\s*[=:])");

    public List<String> sensitivePaths(JsonNode node) {
        List<String> paths = new ArrayList<>();
        collect(node, "$", paths, Collections.newSetFromMap(new IdentityHashMap<>()));
        return List.copyOf(paths);
    }

    private void collect(JsonNode node, String path, List<String> paths, Set<JsonNode> visited) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            if (SENSITIVE_TEXT.matcher(node.asString()).find()) {
                paths.add(path);
            }
            return;
        }
        if ((!node.isObject() && !node.isArray()) || !visited.add(node)) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String name = entry.getKey();
                String childPath = path + "." + name;
                if (isSensitive(name)) {
                    paths.add(childPath);
                }
                else {
                    collect(entry.getValue(), childPath, paths, visited);
                }
            });
            return;
        }
        for (int index = 0; index < node.size(); index++) {
            collect(node.get(index), path + "[" + index + "]", paths, visited);
        }
    }

    private boolean isSensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.contains(normalized)
                || normalized.equals("pwd")
                || normalized.endsWith("password")
                || normalized.endsWith("passwd")
                || normalized.endsWith("token")
                || normalized.endsWith("apikey")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("clientsecret");
    }
}

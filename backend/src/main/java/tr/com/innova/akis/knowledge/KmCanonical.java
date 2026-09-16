package tr.com.innova.akis.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class KmCanonical {
    private KmCanonical() { }
    public static JsonNode normalize(ObjectMapper mapper, JsonNode node) {
        if (node.isObject()) {
            var out = mapper.createObjectNode();
            node.propertyNames().stream().sorted().forEach(key -> out.set(key, normalize(mapper, node.get(key))));
            return out;
        }
        if (node.isArray()) {
            var out = mapper.createArrayNode();
            node.forEach(item -> out.add(normalize(mapper, item)));
            return out;
        }
        return node.deepCopy();
    }
    public static String hash(ObjectMapper mapper, JsonNode node) { return hash(normalize(mapper, node).toString()); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}

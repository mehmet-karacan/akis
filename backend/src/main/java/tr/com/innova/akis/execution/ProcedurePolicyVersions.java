package tr.com.innova.akis.execution;

import java.math.BigInteger;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.metadata.NamedBindParser;
import tr.com.innova.akis.publication.ApprovalPolicyEvaluator;

/** Explicit semantics for new releases; unknown versions must never fall back silently. */
public final class ProcedurePolicyVersions {
    private static final Map<String, Integer> SUPPORTED = Map.of(
        "bindCompiler", NamedBindParser.COMPILER_VERSION,
        "sqlPolicy", SqlStatementPolicy.POLICY_VERSION,
        "approvalPolicy", ApprovalPolicyEvaluator.POLICY_VERSION);

    private ProcedurePolicyVersions() { }

    public static ObjectNode current(ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        SUPPORTED.forEach(result::put);
        return result;
    }

    public static void requireSupported(JsonNode versions) {
        if (versions == null || !versions.isObject() || versions.size() != SUPPORTED.size()) {
            throw new IllegalArgumentException("Procedure policy versions are missing or malformed.");
        }
        SUPPORTED.forEach((name, expected) -> {
            JsonNode value = versions.get(name);
            if (value == null || !value.isIntegralNumber()
                    || !value.bigIntegerValue().equals(BigInteger.valueOf(expected))) {
                throw new IllegalArgumentException("Unsupported Procedure policy version: " + name);
            }
        });
    }
}

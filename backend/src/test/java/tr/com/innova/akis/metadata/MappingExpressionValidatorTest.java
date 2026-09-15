package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MappingExpressionValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> roles = Map.of("src", "SOURCE", "dst", "TARGET");

    @Test
    void preservesNestedTypedAstWithoutMutation() {
        var ast = mapper.readTree("""
            {"kind":"CALL","function":"COALESCE","args":[
              {"kind":"CALL","function":"UPPER","args":[
                {"kind":"COLUMN","dataset":"src","column":"NAME"}]},
              {"kind":"LITERAL","value":"fallback"}]}
            """);
        String before = ast.toString();
        assertDoesNotThrow(() -> MappingExpressionValidator.validate(ast, roles, "expression"));
        assertEquals(before, ast.toString());
    }

    @Test
    void acceptsAllScalarLiteralsIncludingExplicitNull() {
        for (String value : new String[] {"null", "true", "123.45", "\"text\""}) {
            validate("{\"kind\":\"LITERAL\",\"value\":" + value + "}");
        }
    }

    @Test
    void rejectsUnknownFunctionsWrongArityTypesAndReferences() {
        for (String ast : new String[] {
            "{\"kind\":\"SCRIPT\",\"code\":\"arbitrary()\"}",
            "{\"kind\":\"CALL\",\"function\":\"EVAL\",\"args\":[]}",
            "{\"kind\":\"CALL\",\"function\":\"TRIM\",\"args\":[]}",
            "{\"kind\":\"CALL\",\"function\":\"COALESCE\",\"args\":[{\"kind\":\"LITERAL\",\"value\":1}]}",
            "{\"kind\":\"CALL\",\"function\":\"UPPER\",\"args\":[{\"kind\":\"LITERAL\",\"value\":42}]}",
            "{\"kind\":\"COLUMN\",\"dataset\":\"dst\",\"column\":\"NAME\"}",
            "{\"kind\":\"COLUMN\",\"dataset\":\"missing\",\"column\":\"NAME\"}",
            "{\"kind\":\"LITERAL\"}",
            "{\"kind\":\"LITERAL\",\"value\":{},\"metadata\":true}",
            "{\"kind\":\"CALL\",\"function\":\"COALESCE\",\"args\":[{\"kind\":\"LITERAL\",\"value\":1},{\"kind\":\"LITERAL\",\"value\":\"x\"}]}"
        }) assertThrows(ApiException.class, () -> validate(ast), ast);
    }

    @Test
    void boundsDepthBeforeRecursingFurther() {
        ObjectNode node = mapper.createObjectNode().put("kind", "LITERAL").put("value", "x");
        for (int i = 0; i < 40; i++) {
            ObjectNode parent = mapper.createObjectNode().put("kind", "CALL").put("function", "TRIM");
            parent.putArray("args").add(node);
            node = parent;
        }
        var expression = node;
        assertThrows(ApiException.class, () -> MappingExpressionValidator.validate(expression, roles, "expression"));
    }

    @Test
    void boundsTotalNodesAndArgumentCount() {
        ObjectNode wide = mapper.createObjectNode().put("kind", "CALL").put("function", "COALESCE");
        var args = wide.putArray("args");
        for (int i = 0; i < 64; i++) args.addObject().put("kind", "LITERAL").putNull("value");
        assertDoesNotThrow(() -> MappingExpressionValidator.validate(wide, roles, "expression"));
        ObjectNode root = mapper.createObjectNode().put("kind", "CALL").put("function", "COALESCE");
        var branches = root.putArray("args");
        for (int i = 0; i < 20; i++) branches.add(wide.deepCopy());
        assertThrows(ApiException.class, () -> MappingExpressionValidator.validate(root, roles, "expression"));
        args.addObject().put("kind", "LITERAL").putNull("value");
        assertThrows(ApiException.class, () -> MappingExpressionValidator.validate(wide, roles, "expression"));
    }

    @Test
    void mappingValidationActuallyUsesTheContract() {
        var mapping = mapper.readTree("""
            {"datasets":[{"id":"src","role":"SOURCE"},{"id":"dst","role":"TARGET"}],
             "columnMappings":[{"target":{"dataset":"dst","column":"NAME"},
                "expression":{"kind":"SCRIPT","code":"unsafe"}}],
             "writeStrategy":{"kind":"INSERT"}}
            """);
        var error = assertThrows(ApiException.class,
            () -> new DefinitionContentValidator().validate(DefinitionType.MAPPING, 2, mapping));
        assertTrue(error.getMessage().contains("expression"));
    }

    private void validate(String json) {
        MappingExpressionValidator.validate(mapper.readTree(json), roles, "expression");
    }
}

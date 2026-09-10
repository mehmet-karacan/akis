package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MetadataServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetadataService service = new MetadataService(null, objectMapper);

    @Test
    void validatesEveryDefinitionTypeContract() {
        Map<DefinitionType, String> validContent = Map.of(
                DefinitionType.MAPPING,
                "{\"datasets\":[],\"columnMappings\":[],\"writeStrategy\":{}}",
                DefinitionType.REUSABLE_MAPPING,
                "{\"inputs\":[],\"outputs\":[],\"nodes\":[]}",
                DefinitionType.PACKAGE,
                "{\"firstStepId\":\"START\",\"steps\":[],\"transitions\":[]}",
                DefinitionType.PROCEDURE,
                "{\"tasks\":[]}",
                DefinitionType.VARIABLE,
                "{\"dataType\":\"STRING\",\"scope\":\"PROJECT\",\"historyMode\":\"NONE\",\"valueSource\":\"INPUT\"}",
                DefinitionType.SEQUENCE,
                "{\"implementation\":\"REPOSITORY\",\"start\":1,\"increment\":1,\"cycle\":false}",
                DefinitionType.USER_FUNCTION,
                "{\"returnType\":\"STRING\",\"parameters\":[],\"implementations\":[]}",
                DefinitionType.KNOWLEDGE_MODULE,
                "{\"kmType\":\"IKM\",\"tasks\":[],\"options\":[]}",
                DefinitionType.LOAD_PLAN,
                "{\"steps\":[],\"restartPolicy\":\"FAILED_STEP\"}"
        );

        validContent.forEach((type, content) -> assertDoesNotThrow(
                () -> service.validateContent(type, json(content)), type.name()));
        assertEquals(DefinitionType.values().length, validContent.size());
    }

    @Test
    void rejectsInvalidVariableScope() {
        ApiException exception = assertThrows(ApiException.class, () -> service.validateContent(
                DefinitionType.VARIABLE,
                json("{\"dataType\":\"STRING\",\"scope\":\"OTHER\",\"historyMode\":\"NONE\",\"valueSource\":\"INPUT\"}")));

        assertEquals("VALIDATION_FAILED", exception.code());
    }

    @Test
    void rejectsZeroSequenceIncrement() {
        assertThrows(ApiException.class, () -> service.validateContent(
                DefinitionType.SEQUENCE,
                json("{\"implementation\":\"REPOSITORY\",\"start\":1,\"increment\":0,\"cycle\":false}")));
    }

    @Test
    void canonicalizationIgnoresObjectPropertyOrderButPreservesArrayOrder() {
        JsonNode first = service.canonicalize(json("{\"b\":2,\"a\":1,\"items\":[1,2]}"));
        JsonNode reorderedProperties = service.canonicalize(
                json("{\"items\":[1,2],\"a\":1,\"b\":2}"));
        JsonNode reorderedArray = service.canonicalize(
                json("{\"items\":[2,1],\"a\":1,\"b\":2}"));

        assertEquals(first.toString(), reorderedProperties.toString());
        assertNotEquals(first.toString(), reorderedArray.toString());
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }
}

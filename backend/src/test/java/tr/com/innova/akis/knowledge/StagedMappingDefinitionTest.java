package tr.com.innova.akis.knowledge;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import static org.junit.jupiter.api.Assertions.*;

class StagedMappingDefinitionTest {
    private final JsonMapper mapper=new JsonMapper();
    private ObjectNode mapping() {
        return (ObjectNode)mapper.readTree("""
          {"datasets":[{"id":"S","role":"SOURCE"},{"id":"T","role":"TARGET"}],
           "columnMappings":[{"source":{"dataset":"S","column":"ID"},"target":{"dataset":"T","column":"ID"}}],
           "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"},
           "staging":{"logicalSchemaUuid":"11111111-1111-1111-1111-111111111111"},
           "modules":{"loading":{"versionUuid":"22222222-2222-2222-2222-222222222222","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                      "integration":{"versionUuid":"33333333-3333-3333-3333-333333333333","contentHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}},
           "options":{"batchRows":500,"fetchRows":500,"maxRows":100000,"maxBytes":268435456,"allowEmptySource":false}}
          """);
    }
    @Test void supportsExplicitMappingV3WithoutChangingV2() {
        assertDoesNotThrow(()->new DefinitionContentValidator().validate(DefinitionType.MAPPING,3,mapping()));
        assertEquals(100000,StagedMappingDefinition.parse(mapping()).options().maxRows());
    }
    @Test void rejectsUnknownOperationsAndNonAtomicStrategy() {
        var value=mapping(); value.put("sql","DROP TABLE X");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
        var append=mapping(); ((ObjectNode)append.path("writeStrategy")).put("kind","APPEND");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(append));
    }
    @Test void rejectsUnboundedAndFractionalLimits() {
        var value=mapping(); ((ObjectNode)value.path("options")).put("maxRows",0);
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
        ((ObjectNode)value.path("options")).put("maxRows",1.5);
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
    }
    @Test void hashIsStableForObjectOrderButNotStepOrder() {
        assertEquals(KmCanonical.hash(mapper,mapper.readTree("{\"a\":1,\"b\":2}")),KmCanonical.hash(mapper,mapper.readTree("{\"b\":2,\"a\":1}")));
        assertNotEquals(KmCanonical.hash(mapper,mapper.readTree("[1,2]")),KmCanonical.hash(mapper,mapper.readTree("[2,1]")));
    }
}

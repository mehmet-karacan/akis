package tr.com.innova.akis.knowledge;

import java.util.List;
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
    @Test void parsesTypedModuleOptionValuesWithoutMixingTransferLimits() {
        var value=mapping();
        var groups=value.putObject("moduleOptions");
        groups.putObject("loading").put("DISTINCT",true).put("ORACLE_HINT","PARALLEL(4)");
        groups.putObject("integration").put("ORACLE_HINT","APPEND");
        var parsed=StagedMappingDefinition.parse(value);
        assertTrue(parsed.booleanOption("loading","DISTINCT"));
        assertEquals("PARALLEL(4)",parsed.stringOption("loading","ORACLE_HINT"));
        assertEquals("APPEND",parsed.stringOption("integration","ORACLE_HINT"));
    }
    @Test void rejectsNestedOrUnpinnedModuleOptions() {
        var value=mapping(); value.putObject("moduleOptions").putObject("checking").put("DISTINCT",true);
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
    }
    private ObjectNode directMapping() {
        var value=mapping();
        value.remove(List.of("datasets","writeStrategy","staging"));
        var sources=value.putArray("sources");
        for(String id:List.of("S1","S2")) sources.addObject().put("id",id).put("alias",id)
                .put("dataObjectUuid","11111111-1111-1111-1111-111111111111")
                .put("schemaSnapshotUuid","22222222-2222-2222-2222-222222222222");
        value.putObject("target").put("id","T").put("alias","T")
                .put("dataObjectUuid","33333333-3333-3333-3333-333333333333")
                .put("schemaSnapshotUuid","44444444-4444-4444-4444-444444444444");
        var join=value.putArray("joins").addObject().put("id","J1").put("type","LEFT");
        join.putObject("left").put("object","S1").put("column","ID");
        join.putObject("right").put("object","S2").put("column","ID");
        value.putArray("filters");
        var column=value.putArray("columnMappings").addObject();
        column.putObject("source").put("object","S1").put("column","ID");
        column.putObject("target").put("object","T").put("column","ID");
        return value;
    }
    @Test void validatesTheExecutableJoinGraphBeforePublication() {
        var valid=directMapping();
        assertDoesNotThrow(()->new DefinitionContentValidator().validate(DefinitionType.MAPPING,4,valid));
        var disconnected=directMapping(); disconnected.putArray("joins");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(disconnected));
        var aliases=directMapping(); ((ObjectNode)aliases.path("sources").get(1)).put("alias","S1");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(aliases));
        var targetFilter=directMapping(); targetFilter.withArray("filters").addObject()
                .put("id","F1").put("scope","GLOBAL").put("object","T").put("column","ID").put("operator","IS_NULL");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(targetFilter));
    }
    @Test void validatesFreePredicateScopeAndRejectsMixedLegacyFields() {
        var value=directMapping();
        var catalog=List.of(new MappingSql.Source("S1","S1",java.util.Set.of("ID")),new MappingSql.Source("S2","S2",java.util.Set.of("ID")));
        var predicate=MappingSql.parse("S1.ID > 10 AND S2.ID IS NOT NULL",catalog,true);
        var filter=value.withArray("filters").addObject().put("id","F1").put("scope","GLOBAL").put("object","S1");
        filter.set("predicate",predicate);
        assertDoesNotThrow(()->new DefinitionContentValidator().validate(DefinitionType.MAPPING,4,value));
        var parsed=StagedMappingDefinition.parse(value).filters().getFirst();
        ((ObjectNode)parsed.predicate()).put("operator","OR");
        assertEquals("AND",parsed.predicate().path("operator").asText());
        filter.put("scope","SOURCE");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
        filter.put("scope","GLOBAL").put("operator","EQUALS");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
        filter.remove("operator");filter.set("predicate",MappingSql.parse("S1.ID + 10",catalog,false));
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
    }
    @Test void supportsPersistedScalarAstAndRejectsUnknownSourceOrStatementPayload() {
        var value=directMapping();var column=(ObjectNode)value.path("columnMappings").get(0);column.remove("source");
        var expression=MappingSql.parse("TO_CHAR(S1.ID, 'FM999')",List.of(new MappingSql.Source("S1","S1",java.util.Set.of("ID"))),false);
        column.set("expression",expression);
        assertDoesNotThrow(()->new DefinitionContentValidator().validate(DefinitionType.MAPPING,4,value));
        ((ObjectNode)expression.path("args").get(0)).put("dataset","MISSING");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
        ((ObjectNode)expression.path("args").get(0)).put("dataset","S1");
        ((ObjectNode)expression).put("rawSql","DELETE FROM TARGET");
        assertThrows(IllegalArgumentException.class,()->StagedMappingDefinition.parse(value));
    }
}

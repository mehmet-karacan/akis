package tr.com.innova.akis.execution;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.knowledge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StagedColumnLayoutTest {
    private final JsonMapper mapper=new JsonMapper();
    private StagedRuntimePlan plan(boolean checking) {
        var plan=mock(StagedRuntimePlan.class);
        when(plan.columnMappings()).thenReturn(List.of(new PilotRuntimePlan.DirectColumnMapping("SOURCE_ID","ID")));
        when(plan.program()).thenReturn(AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(AkisKmLanguage.example(AkisKmLanguage.Kind.LKM),
                checking?AkisKmLanguage.example(AkisKmLanguage.Kind.CKM)+"ADIM UNIQUE_KEY STAGING CHECK_UNIQUE WORK_SOURCE_1\n":null,AkisKmLanguage.example(AkisKmLanguage.Kind.IKM))));
        return plan;
    }
    private SchemaFingerprintInput snapshot(String type,List<String> key) {
        return new SchemaFingerprintInput("19",1,mapper.createObjectNode(),List.of(new SchemaFingerprintInput.Column("ID",type,"DECIMAL",1,18,0,null,null,false,null,"ID")),
                key.isEmpty()?List.of():List.of(new SchemaFingerprintInput.Constraint("PK_ITEMS","PK",true,1,mapper.createObjectNode(),"PK_ITEMS",key)));
    }
    @Test void buildsTypedNullableWorkColumnsAndSeparateQualityRules() {
        var result=StagedColumnLayout.create(plan(true),snapshot("NUMBER",List.of("ID")));
        assertEquals("NUMBER(18,0)",result.work().getFirst().oracleType());
        assertEquals(List.of("ID"),result.quality().requiredColumns());
        assertEquals(List.of(List.of("ID")),result.quality().uniqueKeys());
    }
    @Test void rejectsUnknownTypesInsteadOfPassingThemToDdl() {
        assertThrows(IllegalArgumentException.class,()->StagedColumnLayout.create(plan(false),snapshot("CLOB",List.of())));
    }
    @Test void rejectsIncompleteUniqueKeyWhenCkmIsSelected() {
        assertThrows(IllegalArgumentException.class,()->StagedColumnLayout.create(plan(true),snapshot("NUMBER",List.of("ID","TENANT_ID"))));
    }
    @Test void doesNotInventUniqueKeyChecksWithoutCkm() {
        assertTrue(StagedColumnLayout.create(plan(false),snapshot("NUMBER",List.of("ID","TENANT_ID"))).quality().uniqueKeys().isEmpty());
    }
}

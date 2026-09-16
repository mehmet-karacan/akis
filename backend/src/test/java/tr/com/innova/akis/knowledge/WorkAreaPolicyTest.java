package tr.com.innova.akis.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkAreaPolicyTest {
    private final StagedMappingDefinition.Options options=new StagedMappingDefinition.Options(500,500,100,10000,false);
    @Test void disabledOrUnversionedPoliciesNeverAuthorizeWork() {
        assertThrows(RuntimeException.class,()->WorkAreaPolicyService.requireAllowed(new WorkAreaPolicyService.View(
                new WorkAreaPolicyService.Policy(false,false,1,100,10000,24),1),options,false));
        assertThrows(RuntimeException.class,()->WorkAreaPolicyService.requireAllowed(new WorkAreaPolicyService.View(
                new WorkAreaPolicyService.Policy(true,false,1,100,10000,24),0),options,false));
    }
    @Test void quotasAndSameSchemaRequireExplicitPermission() {
        var view=new WorkAreaPolicyService.View(new WorkAreaPolicyService.Policy(true,false,1,100,10000,24),1);
        assertDoesNotThrow(()->WorkAreaPolicyService.requireAllowed(view,options,false));
        assertThrows(RuntimeException.class,()->WorkAreaPolicyService.requireAllowed(view,options,true));
        assertThrows(RuntimeException.class,()->WorkAreaPolicyService.requireAllowed(view,new StagedMappingDefinition.Options(1,1,101,10000,false),false));
        assertThrows(RuntimeException.class,()->WorkAreaPolicyService.requireAllowed(view,new StagedMappingDefinition.Options(1,1,100,10001,false),false));
    }
    @Test void directJavaOptionsCannotBypassJsonLimitChecks() {
        assertThrows(IllegalArgumentException.class,()->new StagedMappingDefinition.Options(5001,1,1,1,false));
        assertThrows(IllegalArgumentException.class,()->new StagedMappingDefinition.Options(1,1,Long.MAX_VALUE,1,false));
        assertThrows(IllegalArgumentException.class,()->new WorkAreaPolicyService.Policy(true,false,0,1,1,1));
    }
}

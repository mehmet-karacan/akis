package tr.com.innova.akis.metadata;

import java.util.*;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.security.AuthorizationService;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.security.PermissionCodes.DEFINITION_READ;

class MappingExpressionControllerTest {
    @Test void parsesWithoutExecutingAndChecksProjectPermission() {
        var authorization=mock(AuthorizationService.class);var project=UUID.randomUUID();
        var controller=new MappingExpressionController(authorization);
        var result=controller.compile(project,new MappingExpressionController.Request("TO_CHAR(SRC.DT, 'YYYY-MM-DD')",false,
                List.of(new MappingExpressionController.Source("SOURCE_1","SRC",Set.of("DT")))));
        verify(authorization).requireProjectPermission(project,DEFINITION_READ);
        assertEquals("TO_CHAR",result.expression().path("function").asText());
        assertEquals("SOURCE_1",result.expression().path("args").get(0).path("dataset").asText());
        assertEquals(1,result.references().size());
    }
    @Test void rejectsUnsupportedSqlAndDoesNotBypassDeniedAccess() {
        var authorization=mock(AuthorizationService.class);var project=UUID.randomUUID();
        var controller=new MappingExpressionController(authorization);
        var request=new MappingExpressionController.Request("SELECT * FROM DATA",false,
                List.of(new MappingExpressionController.Source("S","SRC",Set.of("ID"))));
        assertThrows(ApiException.class,()->controller.compile(project,request));
        var denied=new IllegalStateException("permission denied");
        doThrow(denied).when(authorization).requireProjectPermission(project,DEFINITION_READ);
        assertSame(denied,assertThrows(IllegalStateException.class,()->controller.compile(project,request)));
    }
}

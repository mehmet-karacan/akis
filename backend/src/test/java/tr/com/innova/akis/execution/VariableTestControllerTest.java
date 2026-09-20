package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

class VariableTestControllerTest {
    @Test void testRequiresBothEditingAndExecutionPermissionsBeforeAnyEffect() {
        var service = mock(VariableTestService.class); var auth = mock(AuthorizationService.class);
        var project = UUID.randomUUID(); var definition = UUID.randomUUID();
        var request = new VariableTestController.Request(UUID.randomUUID(), UUID.randomUUID(), "DATE", "SELECT SYSDATE FROM DUAL");
        var controller = new VariableTestController(service, auth);
        doThrow(new IllegalStateException("denied")).when(auth).requireProjectPermission(project,RUN_START);
        assertThrows(IllegalStateException.class, () -> controller.test(project,definition,request));
        verify(auth).requireProjectPermission(project,DEFINITION_WRITE); verifyNoInteractions(service);
    }
    @Test void historyIsReadOnlyAndProjectScoped() {
        var service = mock(VariableTestService.class); var auth = mock(AuthorizationService.class);
        var project = UUID.randomUUID(); var definition = UUID.randomUUID();
        new VariableTestController(service, auth).history(project, definition, 42);
        var order = inOrder(auth,service); order.verify(auth).requireProjectPermission(project,DEFINITION_READ);
        order.verify(service).history(project,definition,42); verifyNoMoreInteractions(service);
    }
}

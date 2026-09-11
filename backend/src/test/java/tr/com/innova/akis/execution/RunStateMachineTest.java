package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.metadata.ApiException;

class RunStateMachineTest {

    private final RunStateMachine stateMachine = new RunStateMachine();

    @Test
    void queuedCancellationIsTheOnlyMutatingControlPlaneTransition() {
        assertEquals(
                RunStateMachine.CancellationDecision.TRANSITION,
                stateMachine.queuedCancellation("BEKLIYOR"));
        assertEquals(
                RunStateMachine.CancellationDecision.ALREADY_CANCELLED,
                stateMachine.queuedCancellation("IPTAL"));
        assertThrows(ApiException.class,
                () -> stateMachine.queuedCancellation("HAZIRLANIYOR"));
    }

    @Test
    void recognizesTerminalProjectionStates() {
        assertTrue(stateMachine.terminal("BASARILI"));
        assertTrue(stateMachine.terminal("BASARISIZ"));
        assertTrue(stateMachine.terminal("IPTAL"));
        assertTrue(stateMachine.terminal("YENIDEN_DENENEBILIR"));
        assertTrue(stateMachine.terminal("MUDAHALE_GEREKLI"));
    }
}

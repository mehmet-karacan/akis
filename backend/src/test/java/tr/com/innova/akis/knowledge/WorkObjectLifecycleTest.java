package tr.com.innova.akis.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.knowledge.WorkObjectLifecycle.*;

class WorkObjectLifecycleTest {
    @Test void partialOrUnknownStagesCannotBeConsumed() {
        for(State state:new State[]{State.ALLOCATED,State.CREATING,State.READY,State.LOADING,State.REVIEW_REQUIRED})
            assertThrows(IllegalStateException.class,()->requireTransition(state,State.CONSUMED));
        assertDoesNotThrow(()->requireTransition(State.SEALED,State.CONSUMED));
    }
    @Test void cleanupRequiresPhysicalIdentityAndConfirmedSuccessfulRun() {
        String hash="a".repeat(64);
        assertDoesNotThrow(()->requireDrop(State.CLEANUP_PENDING,"DB:PDB","DB:PDB","WORK","WORK",12,12,hash,hash,true));
        assertThrows(IllegalStateException.class,()->requireDrop(State.CLEANUP_PENDING,"DB:PDB","DB:PDB","WORK","WORK",12,13,hash,hash,true));
        assertThrows(IllegalStateException.class,()->requireDrop(State.CLEANUP_PENDING,"DB:PDB","DB:OTHER","WORK","WORK",12,12,hash,hash,true));
        assertThrows(IllegalStateException.class,()->requireDrop(State.CLEANUP_PENDING,"DB:PDB","DB:PDB","WORK","WORK",12,12,hash,hash,false));
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdbcPinnedSchemaSnapshotStoreTest {

    @Test
    void rejectsInvalidPlanBeforeTouchingJdbcAndDoesNotLeakCause() {
        JdbcPinnedSchemaSnapshotStore store = new JdbcPinnedSchemaSnapshotStore(
                null, new ObjectMapper());
        PilotRuntimePlan invalid = new PilotRuntimePlan(
                1, "f".repeat(64), null, "b".repeat(64),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 100,
                null, null, List.of(),
                PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                new ObjectMapper().createObjectNode());

        PinnedSchemaSnapshotException exception = assertThrows(
                PinnedSchemaSnapshotException.class, () -> store.load(invalid));

        assertEquals(
                PinnedSchemaSnapshotException.Failure.INVALID_CONTRACT,
                exception.failure());
        assertEquals(
                "Pinned schema snapshot could not be loaded safely.",
                exception.getMessage());
        assertNull(exception.getCause());
    }
}

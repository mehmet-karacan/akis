package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PilotPublishKeyV1Test {

    private final PilotPublishKeyV1 keys = new PilotPublishKeyV1();

    @Test
    void isDeterministicAndIndependentFromRunAttemptIdentity() {
        UUID jobRequest = UUID.fromString("00000000-0000-0000-0000-000000000101");
        String runtimePlan = "a".repeat(64);
        String target = "b".repeat(64);

        String first = keys.create(
                jobRequest, runtimePlan, target, PilotPublishKeyV1.PILOT_STEP_CODE);
        String retried = keys.create(
                jobRequest, runtimePlan, target, "pilot_publish");

        assertEquals(first, retried);
        assertEquals(64, first.length());
        assertNotEquals(first, keys.create(
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                runtimePlan, target, PilotPublishKeyV1.PILOT_STEP_CODE));
        assertNotEquals(first, keys.create(
                jobRequest, "c".repeat(64), target, PilotPublishKeyV1.PILOT_STEP_CODE));
        assertNotEquals(first, keys.create(
                jobRequest, runtimePlan, "d".repeat(64),
                PilotPublishKeyV1.PILOT_STEP_CODE));
    }

    @Test
    void rejectsIncompleteOrNonCanonicalInputs() {
        assertThrows(IllegalArgumentException.class, () -> keys.create(
                null, "a".repeat(64), "b".repeat(64), "PILOT_PUBLISH"));
        assertThrows(IllegalArgumentException.class, () -> keys.create(
                UUID.randomUUID(), "A".repeat(64), "b".repeat(64), "PILOT_PUBLISH"));
        assertThrows(IllegalArgumentException.class, () -> keys.create(
                UUID.randomUUID(), "a".repeat(64), "b".repeat(64), "bad step"));
    }
}

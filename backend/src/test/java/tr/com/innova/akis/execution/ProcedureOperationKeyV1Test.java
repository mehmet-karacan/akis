package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.ProcedureExecutionJournalPort.TaskEvidence;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ConnectionRole;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RiskClass;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskType;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

class ProcedureOperationKeyV1Test {

    private final ProcedureOperationKeyV1 keys = new ProcedureOperationKeyV1();

    @Test
    void hasStableLengthPrefixedCanonicalVector() {
        ActiveExecutionToken token = token(3, 7, "f".repeat(64));
        TaskEvidence evidence = evidence("LOAD_TARGET", 2, "b".repeat(64));

        String first = keys.create(token, evidence);
        String retried = keys.create(token, evidence);

        assertEquals(first, retried);
        assertEquals(
                "c0a9acfe138cd1f605c02b5cc24e11a175f7490cf0f458d5c3db2c81aaf0e1c9",
                first);
    }

    @Test
    void changesWithAnyExecutionOrTaskIdentityComponent() {
        TaskEvidence evidence = evidence("LOAD_TARGET", 2, "b".repeat(64));
        String baseline = keys.create(token(3, 7, "f".repeat(64)), evidence);

        assertNotEquals(baseline, keys.create(
                token(4, 7, "f".repeat(64)), evidence));
        assertNotEquals(baseline, keys.create(
                token(3, 8, "f".repeat(64)), evidence));
        assertNotEquals(baseline, keys.create(
                token(3, 7, "e".repeat(64)), evidence));
        assertNotEquals(baseline, keys.create(
                token(3, 7, "f".repeat(64)),
                evidence("LOAD_TARGET", 3, "b".repeat(64))));
        assertNotEquals(baseline, keys.create(
                token(3, 7, "f".repeat(64)),
                evidence("LOAD_TARGET", 2, "c".repeat(64))));
    }

    private ActiveExecutionToken token(
            long runGeneration, long targetGeneration, String targetHash) {
        UUID runUuid = UUID.fromString("00000000-0000-0000-0000-000000000101");
        String worker = "procedure-worker-01";
        return new ActiveExecutionToken(
                new RunLeaseToken(runUuid, worker, runGeneration,
                        OffsetDateTime.parse("2030-01-02T03:04:05Z")),
                new TargetFenceToken(
                        runUuid, worker, runGeneration,
                        UUID.fromString("00000000-0000-0000-0000-000000000202"),
                        targetGeneration, targetHash, 1));
    }

    private TaskEvidence evidence(String id, int index, String commandHash) {
        UUID definitionObject = UUID.fromString(
                "00000000-0000-0000-0000-000000000301");
        Task task = new Task(
                id, "Load target", TaskType.SQL, ConnectionRole.TARGET,
                RiskClass.DML, "INSERT INTO TARGET_TABLE VALUES (:ID)", commandHash,
                false, ErrorPolicy.STOP, 60, null, null, List.of("ID"));
        TaskBinding binding = new TaskBinding(
                id, ConnectionRole.TARGET, definitionObject,
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                UUID.fromString("00000000-0000-0000-0000-000000000303"),
                UUID.fromString("00000000-0000-0000-0000-000000000304"),
                UUID.fromString("00000000-0000-0000-0000-000000000305"),
                UUID.fromString("00000000-0000-0000-0000-000000000306"),
                1, "d".repeat(64), "TARGET|OWNER|TABLE", "OWNER", "TARGET_TABLE", "TABLE");
        return new TaskEvidence("a".repeat(64), index, task, binding);
    }
}

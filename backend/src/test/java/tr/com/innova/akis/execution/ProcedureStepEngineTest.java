package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ProcedureExecutionJournalPort.TaskEvidence;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.BatchInput;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ConnectionRole;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RiskClass;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RowsetOutput;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskType;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.RowsetHandle;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskCommand;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskResult;

class ProcedureStepEngineTest {

    private static final String RUNTIME_HASH = "a".repeat(64);

    @Test
    void executesTasksInOrderAndPassesOnlyTheAdjacentRowsetHandle() {
        Fixture fixture = new Fixture();

        ProcedureStepEngine.Completed result = assertInstanceOf(
                ProcedureStepEngine.Completed.class, fixture.execute());

        assertEquals(4, result.completedTasks());
        assertEquals(0, result.warnings());
        assertEquals(List.of("PREPARE", "READ", "INSERT", "STATS"),
                fixture.commands.stream().map(command -> command.task().id()).toList());
        assertEquals(null, fixture.commands.get(1).input());
        assertEquals(fixture.rowset, fixture.commands.get(2).input());
        assertEquals(List.of(
                "START:PREPARE", "SUCCESS:PREPARE",
                "START:READ", "SUCCESS:READ",
                "START:INSERT", "SUCCESS:INSERT",
                "START:STATS", "SUCCESS:STATS"), fixture.events);
    }

    @Test
    void stopFailureNeverExecutesTheNextTask() {
        Fixture fixture = new Fixture();
        fixture.results.clear();
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        fixture.results.add(new ProcedureTaskExecutorPort.SafeFailure("SOURCE_FAILED", true));

        ProcedureStepEngine.FailedSafely result = assertInstanceOf(
                ProcedureStepEngine.FailedSafely.class, fixture.execute());

        assertEquals("READ", result.taskId());
        assertEquals(List.of("PREPARE", "READ"),
                fixture.commands.stream().map(command -> command.task().id()).toList());
        assertEquals("FAILED:READ:false", fixture.events.getLast());
    }

    @Test
    void continueProceedsOnlyAfterConfirmedSafeFailure() {
        Fixture fixture = new Fixture();
        fixture.results.removeFirst();
        fixture.results.addFirst(new ProcedureTaskExecutorPort.SafeFailure("PREPARE_FAILED", true));

        ProcedureStepEngine.Completed result = assertInstanceOf(
                ProcedureStepEngine.Completed.class, fixture.execute());

        assertEquals(3, result.completedTasks());
        assertEquals(1, result.warnings());
        assertEquals("FAILED:PREPARE:true", fixture.events.get(1));
        assertEquals(4, fixture.commands.size());
    }

    @Test
    void unconfirmedRollbackAndUnknownOutcomeAlwaysStop() {
        Fixture rollback = new Fixture();
        rollback.results.removeFirst();
        rollback.results.addFirst(
                new ProcedureTaskExecutorPort.SafeFailure("PREPARE_FAILED", false));
        ProcedureStepEngine.UnknownOutcome rollbackResult = assertInstanceOf(
                ProcedureStepEngine.UnknownOutcome.class, rollback.execute());
        assertEquals("ROLLBACK_UNCONFIRMED", rollbackResult.errorCode());
        assertEquals(1, rollback.commands.size());

        Fixture unknown = new Fixture();
        unknown.results.removeFirst();
        unknown.results.addFirst(
                new ProcedureTaskExecutorPort.OutcomeUnknown("DDL_OUTCOME_UNKNOWN"));
        ProcedureStepEngine.UnknownOutcome unknownResult = assertInstanceOf(
                ProcedureStepEngine.UnknownOutcome.class, unknown.execute());
        assertEquals("DDL_OUTCOME_UNKNOWN", unknownResult.errorCode());
        assertEquals(1, unknown.commands.size());
    }

    @Test
    void rejectedStartEvidencePreventsAnyExecutorCall() {
        Fixture fixture = new Fixture();
        fixture.rejectStart = true;

        ProcedureStepEngine.StoppedFailClosed result = assertInstanceOf(
                ProcedureStepEngine.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureStepEngine.FailureCode.CONTROL_PLANE_UNCONFIRMED,
                result.failure());
        assertEquals(List.of(), fixture.commands);
    }

    @Test
    void invalidExecutorRowsetReceiptBecomesUnknownAndDoesNotReachConsumer() {
        Fixture fixture = new Fixture();
        fixture.results.clear();
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(
                2, 8, new RowsetHandle(
                        UUID.randomUUID(), "b".repeat(64), "READ", 2, 8)));

        ProcedureStepEngine.UnknownOutcome result = assertInstanceOf(
                ProcedureStepEngine.UnknownOutcome.class, fixture.execute());

        assertEquals("INVALID_EXECUTOR_RECEIPT", result.errorCode());
        assertEquals(List.of("PREPARE", "READ"),
                fixture.commands.stream().map(command -> command.task().id()).toList());
    }

    private static final class Fixture {

        private final ProcedureRuntimePlan plan = plan();
        private final RowsetHandle rowset = new RowsetHandle(
                UUID.randomUUID(), RUNTIME_HASH, "READ", 2, 8);
        private final ArrayDeque<TaskResult> results = new ArrayDeque<>();
        private final List<TaskCommand> commands = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean rejectStart;

        private Fixture() {
            results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
            results.add(new ProcedureTaskExecutorPort.Succeeded(2, 8, rowset));
            results.add(new ProcedureTaskExecutorPort.Succeeded(2, 8, null));
            results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        }

        private ProcedureStepEngine.RunResult execute() {
            ProcedureTaskExecutorPort executor = command -> {
                commands.add(command);
                return results.removeFirst();
            };
            ProcedureExecutionJournalPort journal = new ProcedureExecutionJournalPort() {
                @Override
                public boolean started(TaskEvidence evidence) {
                    events.add("START:" + evidence.task().id());
                    return !rejectStart;
                }

                @Override
                public boolean succeeded(TaskEvidence evidence, long rowCount, long byteCount) {
                    events.add("SUCCESS:" + evidence.task().id());
                    return true;
                }

                @Override
                public boolean failed(
                        TaskEvidence evidence,
                        String errorCode,
                        boolean attempted,
                        boolean rollbackConfirmed,
                        boolean continued) {
                    events.add("FAILED:" + evidence.task().id() + ":" + continued);
                    return true;
                }

                @Override
                public boolean outcomeUnknown(TaskEvidence evidence, String errorCode) {
                    events.add("UNKNOWN:" + evidence.task().id() + ":" + errorCode);
                    return true;
                }
            };
            return new ProcedureStepEngine(executor, journal).execute(plan);
        }
    }

    private static ProcedureRuntimePlan plan() {
        List<Task> tasks = List.of(
                task("PREPARE", TaskType.SQL, ConnectionRole.TARGET,
                        RiskClass.DDL, ErrorPolicy.CONTINUE, null, null, List.of()),
                task("READ", TaskType.SQL, ConnectionRole.SOURCE,
                        RiskClass.READ_ONLY, ErrorPolicy.STOP,
                        new RowsetOutput(1000), null, List.of()),
                task("INSERT", TaskType.SQL, ConnectionRole.TARGET,
                        RiskClass.DML, ErrorPolicy.STOP, null,
                        new BatchInput("READ", 250), List.of("ID")),
                task("STATS", TaskType.PLSQL, ConnectionRole.TARGET,
                        RiskClass.DESTRUCTIVE, ErrorPolicy.STOP, null, null, List.of()));
        Map<String, TaskBinding> bindings = new LinkedHashMap<>();
        tasks.forEach(task -> bindings.put(task.id(), binding(task)));
        return new ProcedureRuntimePlan(
                1, RUNTIME_HASH, "b".repeat(64), "c".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), tasks, bindings,
                new ObjectMapper().createObjectNode());
    }

    private static Task task(
            String id,
            TaskType type,
            ConnectionRole role,
            RiskClass risk,
            ErrorPolicy error,
            RowsetOutput output,
            BatchInput input,
            List<String> binds) {
        return new Task(
                id, id, type, role, risk, "command", "d".repeat(64),
                risk == RiskClass.DDL || risk == RiskClass.DESTRUCTIVE,
                error, 30, output, input, binds);
    }

    private static TaskBinding binding(Task task) {
        return new TaskBinding(
                task.id(), task.connectionRole(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "e".repeat(64),
                task.connectionRole() == ConnectionRole.SOURCE
                        ? "TTBP.SOURCE_TABLE" : "INNOVA_ODI.TARGET_TABLE",
                task.connectionRole() == ConnectionRole.SOURCE ? "TTBP" : "INNOVA_ODI",
                task.connectionRole() == ConnectionRole.SOURCE ? "SOURCE_TABLE" : "TARGET_TABLE",
                "TABLO");
    }
}

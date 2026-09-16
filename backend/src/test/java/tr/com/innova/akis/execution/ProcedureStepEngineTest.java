package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void readOnlyExecutorBoundaryFailureIsRecordedAsSafeFailure() {
        Fixture fixture = new Fixture();
        fixture.executorFailureTaskId = "READ";

        ProcedureStepEngine.FailedSafely result = assertInstanceOf(
                ProcedureStepEngine.FailedSafely.class, fixture.execute());

        assertEquals("READ", result.taskId());
        assertEquals("EXECUTOR_BOUNDARY_FAILED", result.errorCode());
        assertEquals(List.of(new FailureEvent(
                "READ", "EXECUTOR_BOUNDARY_FAILED", true, true, false)),
                fixture.failures);
        assertFalse(fixture.events.stream().anyMatch(event -> event.startsWith("UNKNOWN:")));
    }

    @Test
    void mutatingExecutorBoundaryFailureIsRecordedAsUnknown() {
        Fixture fixture = new Fixture();
        fixture.executorFailureTaskId = "PREPARE";

        ProcedureStepEngine.UnknownOutcome result = assertInstanceOf(
                ProcedureStepEngine.UnknownOutcome.class, fixture.execute());

        assertEquals("PREPARE", result.taskId());
        assertEquals("EXECUTOR_BOUNDARY_FAILED", result.errorCode());
        assertEquals(List.of(), fixture.failures);
        assertEquals("UNKNOWN:PREPARE:EXECUTOR_BOUNDARY_FAILED", fixture.events.getLast());
    }

    @Test
    void readOnlyUnknownAndUnconfirmedRollbackAreStillSafeFailures() {
        Fixture reportedUnknown = new Fixture();
        reportedUnknown.results.clear();
        reportedUnknown.results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        reportedUnknown.results.add(
                new ProcedureTaskExecutorPort.OutcomeUnknown("SOURCE_OUTCOME_UNKNOWN"));

        ProcedureStepEngine.FailedSafely unknownResult = assertInstanceOf(
                ProcedureStepEngine.FailedSafely.class, reportedUnknown.execute());
        assertEquals("SOURCE_OUTCOME_UNKNOWN", unknownResult.errorCode());
        assertEquals(new FailureEvent(
                "READ", "SOURCE_OUTCOME_UNKNOWN", true, true, false),
                reportedUnknown.failures.getFirst());

        Fixture rollbackUnconfirmed = new Fixture();
        rollbackUnconfirmed.results.clear();
        rollbackUnconfirmed.results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        rollbackUnconfirmed.results.add(
                new ProcedureTaskExecutorPort.SafeFailure("SOURCE_FAILED", false));

        ProcedureStepEngine.FailedSafely rollbackResult = assertInstanceOf(
                ProcedureStepEngine.FailedSafely.class, rollbackUnconfirmed.execute());
        assertEquals("SOURCE_FAILED", rollbackResult.errorCode());
        assertEquals(new FailureEvent(
                "READ", "SOURCE_FAILED", true, true, false),
                rollbackUnconfirmed.failures.getFirst());
    }

    @Test
    void notAttemptedRemainsSafeAndHonorsErrorPolicy() {
        Fixture continued = new Fixture();
        continued.results.removeFirst();
        continued.results.addFirst(
                new ProcedureTaskExecutorPort.NotAttempted("PROCEDURE_EXECUTOR_DISABLED"));

        ProcedureStepEngine.Completed completed = assertInstanceOf(
                ProcedureStepEngine.Completed.class, continued.execute());
        assertEquals(3, completed.completedTasks());
        assertEquals(1, completed.warnings());
        assertEquals(new FailureEvent(
                "PREPARE", "PROCEDURE_EXECUTOR_DISABLED", false, true, true),
                continued.failures.getFirst());

        Fixture stopped = new Fixture();
        stopped.results.clear();
        stopped.results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        stopped.results.add(
                new ProcedureTaskExecutorPort.NotAttempted("PROCEDURE_EXECUTOR_DISABLED"));

        ProcedureStepEngine.FailedSafely failed = assertInstanceOf(
                ProcedureStepEngine.FailedSafely.class, stopped.execute());
        assertEquals("READ", failed.taskId());
        assertEquals(new FailureEvent(
                "READ", "PROCEDURE_EXECUTOR_DISABLED", false, true, false),
                stopped.failures.getFirst());
    }

    @Test
    void rowAggregateOverflowStopsAfterAcceptedSuccessWithoutDuplicatingOutcome() {
        Fixture fixture = new Fixture();
        fixture.results.clear();
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(
                Long.MAX_VALUE, 0, null));
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(2, 8, fixture.rowset));

        ProcedureStepEngine.StoppedFailClosed result = assertInstanceOf(
                ProcedureStepEngine.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureStepEngine.FailureCode.AGGREGATE_METRICS_OVERFLOW,
                result.failure());
        assertEquals(List.of("PREPARE", "READ"),
                fixture.commands.stream().map(command -> command.task().id()).toList());
        assertEquals(List.of(
                "START:PREPARE", "SUCCESS:PREPARE",
                "START:READ", "SUCCESS:READ"), fixture.events);
        assertEquals(List.of(), fixture.failures);
    }

    @Test
    void byteAggregateOverflowStopsAfterAcceptedSuccessWithoutDuplicatingOutcome() {
        Fixture fixture = new Fixture();
        fixture.results.clear();
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(
                0, Long.MAX_VALUE, null));
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(2, 8, fixture.rowset));

        ProcedureStepEngine.StoppedFailClosed result = assertInstanceOf(
                ProcedureStepEngine.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureStepEngine.FailureCode.AGGREGATE_METRICS_OVERFLOW,
                result.failure());
        assertEquals(List.of("PREPARE", "READ"),
                fixture.commands.stream().map(command -> command.task().id()).toList());
        assertEquals(List.of(
                "START:PREPARE", "SUCCESS:PREPARE",
                "START:READ", "SUCCESS:READ"), fixture.events);
        assertEquals(List.of(), fixture.failures);
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
    void invalidReadOnlyRowsetReceiptFailsSafelyAndDoesNotReachConsumer() {
        Fixture fixture = new Fixture();
        fixture.results.clear();
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        fixture.results.add(new ProcedureTaskExecutorPort.Succeeded(
                2, 8, new RowsetHandle(
                        UUID.randomUUID(), "b".repeat(64), "READ", 2, 8)));

        ProcedureStepEngine.FailedSafely result = assertInstanceOf(
                ProcedureStepEngine.FailedSafely.class, fixture.execute());

        assertEquals("INVALID_EXECUTOR_RECEIPT", result.errorCode());
        assertEquals(List.of("PREPARE", "READ"),
                fixture.commands.stream().map(command -> command.task().id()).toList());
    }

    @Test
    void managedNoCommitIsNotReportedSuccessfulBeforeGroupCommit() {
        Task task = new Task(
                "LOAD", "LOAD", TaskType.SQL, ConnectionRole.TARGET, RiskClass.DML,
                "command", "d".repeat(64), false, ErrorPolicy.STOP, 30,
                null, null, List.of(), ProcedureRuntimePlan.LogCounter.INSERT,
                ProcedureRuntimePlan.TransactionMode.TRANSACTION, 0,
                ProcedureRuntimePlan.TransactionIsolation.READ_COMMITTED,
                ProcedureRuntimePlan.CommitMode.NO_COMMIT);
        ProcedureRuntimePlan plan = new ProcedureRuntimePlan(
                1, RUNTIME_HASH, "b".repeat(64), "c".repeat(64), UUID.randomUUID(),
                UUID.randomUUID(), List.of(task), Map.of(task.id(), binding(task)),
                new ObjectMapper().createObjectNode());
        List<String> evidence = new ArrayList<>();
        ProcedureExecutionJournalPort journal = new ProcedureExecutionJournalPort() {
            public boolean started(TaskEvidence value) { evidence.add("START"); return true; }
            public boolean succeeded(TaskEvidence value, long rows, long bytes) {
                evidence.add("SUCCESS"); return true;
            }
            public boolean executedUncommitted(TaskEvidence value, long rows, long bytes) {
                evidence.add("EXECUTED_UNCOMMITTED"); return true;
            }
            public boolean commitConfirmed(
                    TaskEvidence value, long rows, long bytes, String reference) {
                evidence.add("COMMIT_CONFIRMED:" + reference); return true;
            }
            public boolean failed(TaskEvidence value, String code, boolean attempted,
                    boolean rollback, boolean continued) { return true; }
            public boolean outcomeUnknown(TaskEvidence value, String code) { return true; }
        };
        ProcedureStepEngine engine = new ProcedureStepEngine(
                command -> new ProcedureTaskExecutorPort.Succeeded(
                        3, 24, null,
                        ProcedureTaskExecutorPort.TransactionOutcome.EXECUTED_UNCOMMITTED),
                journal);

        assertInstanceOf(ProcedureStepEngine.Completed.class, engine.execute(plan));
        assertEquals(List.of("START", "EXECUTED_UNCOMMITTED"), evidence);

        assertEquals(true, engine.confirmPendingCommits("receipt-1"));
        assertEquals(List.of("START", "EXECUTED_UNCOMMITTED",
                "COMMIT_CONFIRMED:receipt-1"), evidence);
    }

    @Test
    void committedGroupIsFinalizedBeforeAUnrelatedLaterFailure() {
        UUID connection = UUID.randomUUID();
        Task first = managedTask("A", ProcedureRuntimePlan.CommitMode.NO_COMMIT);
        Task boundary = managedTask("B", ProcedureRuntimePlan.CommitMode.COMMIT);
        Task later = managedTask("C", ProcedureRuntimePlan.CommitMode.COMMIT);
        Map<String, TaskBinding> bindings = new LinkedHashMap<>();
        for (Task task : List.of(first, boundary, later)) {
            TaskBinding value = binding(task);
            bindings.put(task.id(), new TaskBinding(
                    value.taskId(), value.role(), value.definitionDataObjectUuid(),
                    value.dataObjectUuid(), value.environmentSchemaBindingUuid(),
                    value.physicalSchemaUuid(), connection, value.schemaSnapshotUuid(),
                    value.bindingVersion(), value.schemaSnapshotFingerprint(),
                    value.physicalIdentity(), value.owner(), value.objectName(),
                    value.dataObjectType()));
        }
        ProcedureRuntimePlan plan = new ProcedureRuntimePlan(
                1, RUNTIME_HASH, "b".repeat(64), "c".repeat(64), UUID.randomUUID(),
                UUID.randomUUID(), List.of(first, boundary, later), bindings,
                new ObjectMapper().createObjectNode());
        ArrayDeque<TaskResult> results = new ArrayDeque<>();
        results.add(new ProcedureTaskExecutorPort.Succeeded(
                1, 8, null,
                ProcedureTaskExecutorPort.TransactionOutcome.EXECUTED_UNCOMMITTED));
        results.add(new ProcedureTaskExecutorPort.Succeeded(
                1, 8, null,
                ProcedureTaskExecutorPort.TransactionOutcome.COMMIT_CONFIRMED));
        results.add(new ProcedureTaskExecutorPort.SafeFailure("C_FAILED", true));
        List<String> evidence = new ArrayList<>();
        ProcedureExecutionJournalPort journal = journal(evidence);
        ProcedureStepEngine engine = new ProcedureStepEngine(command -> results.removeFirst(), journal);

        ProcedureStepEngine.FailedSafely failed = assertInstanceOf(
                ProcedureStepEngine.FailedSafely.class, engine.execute(plan));

        assertEquals("C", failed.taskId());
        assertEquals(1, evidence.stream()
                .filter(value -> value.startsWith("COMMIT:A:")).count());
        assertEquals(true, engine.confirmPendingRollbacks("LATER_FAILURE"));
        assertFalse(evidence.stream().anyMatch(value -> value.startsWith("ROLLBACK:A")));
    }

    private static Task managedTask(String id, ProcedureRuntimePlan.CommitMode commitMode) {
        return new Task(
                id, id, TaskType.SQL, ConnectionRole.TARGET, RiskClass.DML,
                "command", "d".repeat(64), false, ErrorPolicy.STOP, 30,
                null, null, List.of(), ProcedureRuntimePlan.LogCounter.INSERT,
                ProcedureRuntimePlan.TransactionMode.TRANSACTION, 0,
                ProcedureRuntimePlan.TransactionIsolation.READ_COMMITTED, commitMode);
    }

    private static ProcedureExecutionJournalPort journal(List<String> evidence) {
        return new ProcedureExecutionJournalPort() {
            public boolean started(TaskEvidence value) {
                evidence.add("START:" + value.task().id()); return true;
            }
            public boolean succeeded(TaskEvidence value, long rows, long bytes) {
                evidence.add("SUCCESS:" + value.task().id()); return true;
            }
            public boolean executedUncommitted(TaskEvidence value, long rows, long bytes) {
                evidence.add("UNCOMMITTED:" + value.task().id()); return true;
            }
            public boolean commitConfirmed(TaskEvidence value, long rows, long bytes, String reference) {
                evidence.add("COMMIT:" + value.task().id() + ":" + reference); return true;
            }
            public boolean rollbackConfirmed(TaskEvidence value, String code) {
                evidence.add("ROLLBACK:" + value.task().id()); return true;
            }
            public boolean failed(TaskEvidence value, String code, boolean attempted,
                    boolean rollback, boolean continued) {
                evidence.add("FAILED:" + value.task().id()); return true;
            }
            public boolean outcomeUnknown(TaskEvidence value, String code) { return true; }
        };
    }

    private static final class Fixture {

        private final ProcedureRuntimePlan plan = plan();
        private final RowsetHandle rowset = new RowsetHandle(
                UUID.randomUUID(), RUNTIME_HASH, "READ", 2, 8);
        private final ArrayDeque<TaskResult> results = new ArrayDeque<>();
        private final List<TaskCommand> commands = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final List<FailureEvent> failures = new ArrayList<>();
        private boolean rejectStart;
        private String executorFailureTaskId;

        private Fixture() {
            results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
            results.add(new ProcedureTaskExecutorPort.Succeeded(2, 8, rowset));
            results.add(new ProcedureTaskExecutorPort.Succeeded(2, 8, null));
            results.add(new ProcedureTaskExecutorPort.Succeeded(0, 0, null));
        }

        private ProcedureStepEngine.RunResult execute() {
            ProcedureTaskExecutorPort executor = command -> {
                commands.add(command);
                if (command.task().id().equals(executorFailureTaskId)) {
                    throw new IllegalStateException("sensitive executor failure");
                }
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
                    failures.add(new FailureEvent(
                            evidence.task().id(), errorCode, attempted,
                            rollbackConfirmed, continued));
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

    private record FailureEvent(
            String taskId,
            String errorCode,
            boolean attempted,
            boolean rollbackConfirmed,
            boolean continued) {
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

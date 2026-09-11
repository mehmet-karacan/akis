package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ProcedureExecutionJournalPort.TaskEvidence;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ConnectionRole;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RiskClass;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskType;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.OutcomeUnknown;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.Succeeded;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskCommand;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskResult;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

class ProcedureRunHandlerTest {

    @Test
    void retriesOnlyExactRunAcknowledgementsAndExecutesEngineOnce() {
        Fixture fixture = new Fixture(command -> new Succeeded(3, 24, null));
        fixture.session.prepareFailures = 1;
        fixture.session.completeFailures = 1;

        ProcedureRunHandler.Completed result = assertInstanceOf(
                ProcedureRunHandler.Completed.class, fixture.execute());

        assertEquals(1, result.completedTasks());
        assertEquals(3, result.rowCount());
        assertEquals(24, result.byteCount());
        assertEquals(2, fixture.session.prepareCalls);
        assertEquals(1, fixture.executorCalls.get());
        assertEquals(2, fixture.session.completeCalls);
        assertEquals(List.of(
                "PREPARE", "PREPARE", "START:TRUNCATE_TARGET",
                "EXECUTE:TRUNCATE_TARGET", "SUCCESS:TRUNCATE_TARGET",
                "EXECUTOR_CLOSE", "COMPLETE", "COMPLETE"), fixture.events);
        assertSame(fixture.token, fixture.journalFactoryToken);
        assertSame(fixture.plan, fixture.journalFactoryPlan);
        assertSame(fixture.token, fixture.executorFactoryToken);
        assertSame(fixture.plan, fixture.executorFactoryPlan);
        assertEquals(1, fixture.executorFactoryCalls);
        assertEquals(1, fixture.executorCloseCalls);
    }

    @Test
    void confirmedPrepareRejectionDoesNotRetryOrEnterEngine() {
        Fixture fixture = new Fixture(command -> new Succeeded(0, 0, null));
        fixture.session.prepareAccepted = false;

        ProcedureRunHandler.StoppedFailClosed result = assertInstanceOf(
                ProcedureRunHandler.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureRunHandler.FailureCode.PREPARE_UNCONFIRMED,
                result.failure());
        assertEquals(1, fixture.session.prepareCalls);
        assertEquals(0, fixture.executorFactoryCalls);
        assertEquals(0, fixture.executorCalls.get());
        assertEquals(0, fixture.session.completeCalls);
        assertEquals(List.of("PREPARE"), fixture.events);
    }

    @Test
    void prepareAcknowledgementLossStopsAfterOneExactRetry() {
        Fixture fixture = new Fixture(command -> new Succeeded(0, 0, null));
        fixture.session.prepareFailures = 2;

        ProcedureRunHandler.StoppedFailClosed result = assertInstanceOf(
                ProcedureRunHandler.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureRunHandler.FailureCode.PREPARE_UNCONFIRMED,
                result.failure());
        assertEquals(2, fixture.session.prepareCalls);
        assertEquals(0, fixture.executorFactoryCalls);
        assertEquals(0, fixture.executorCalls.get());
        assertEquals(0, fixture.session.completeCalls);
        assertEquals(List.of("PREPARE", "PREPARE"), fixture.events);
    }

    @Test
    void disabledExecutorMapsToSafeFailureWithoutCompletingRun() {
        Fixture fixture = new Fixture(new DisabledProcedureTaskExecutor());

        ProcedureRunHandler.FailedSafely result = assertInstanceOf(
                ProcedureRunHandler.FailedSafely.class, fixture.execute());

        assertEquals("TRUNCATE_TARGET", result.taskId());
        assertEquals("PROCEDURE_EXECUTOR_DISABLED", result.errorCode());
        assertEquals(1, fixture.executorCalls.get());
        assertEquals(1, fixture.executorCloseCalls);
        assertEquals(0, fixture.session.completeCalls);
        assertEquals(List.of(
                "PREPARE", "START:TRUNCATE_TARGET",
                "EXECUTE:TRUNCATE_TARGET",
                "FAILED:TRUNCATE_TARGET:PROCEDURE_EXECUTOR_DISABLED",
                "EXECUTOR_CLOSE"), fixture.events);
    }

    @Test
    void unknownOutcomeIsMappedWithoutCompletingOrRetryingEngine() {
        Fixture fixture = new Fixture(command -> new OutcomeUnknown("DDL_OUTCOME_UNKNOWN"));

        ProcedureRunHandler.UnknownOutcome result = assertInstanceOf(
                ProcedureRunHandler.UnknownOutcome.class, fixture.execute());

        assertEquals("TRUNCATE_TARGET", result.taskId());
        assertEquals("DDL_OUTCOME_UNKNOWN", result.errorCode());
        assertEquals(1, fixture.executorCalls.get());
        assertEquals(1, fixture.executorCloseCalls);
        assertEquals(0, fixture.session.completeCalls);
        assertEquals(1, fixture.session.unknownCalls);
    }

    @Test
    void engineFailClosedResultIsMappedWithoutCompletingRun() {
        Fixture fixture = new Fixture(command -> new Succeeded(0, 0, null));
        fixture.session.startAccepted = false;

        ProcedureRunHandler.StoppedFailClosed result = assertInstanceOf(
                ProcedureRunHandler.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureRunHandler.FailureCode.ENGINE_STOPPED_FAIL_CLOSED,
                result.failure());
        assertEquals(ProcedureStepEngine.FailureCode.CONTROL_PLANE_UNCONFIRMED,
                result.engineFailure());
        assertEquals(0, fixture.executorCalls.get());
        assertEquals(1, fixture.executorCloseCalls);
        assertEquals(0, fixture.session.completeCalls);
    }

    @Test
    void completionAcknowledgementLossNeverReexecutesTheEngine() {
        Fixture fixture = new Fixture(command -> new Succeeded(1, 8, null));
        fixture.session.completeFailures = 2;

        ProcedureRunHandler.StoppedFailClosed result = assertInstanceOf(
                ProcedureRunHandler.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureRunHandler.FailureCode.COMPLETION_UNCONFIRMED,
                result.failure());
        assertEquals(1, fixture.executorCalls.get());
        assertEquals(1, fixture.executorCloseCalls);
        assertEquals(2, fixture.session.completeCalls);
        assertEquals(1, fixture.session.successCalls);
    }

    @Test
    void executorSessionCreationFailureStopsAfterPreparation() {
        Fixture fixture = new Fixture(command -> new Succeeded(0, 0, null));
        fixture.executorFactoryFailure = true;

        ProcedureRunHandler.StoppedFailClosed result = assertInstanceOf(
                ProcedureRunHandler.StoppedFailClosed.class, fixture.execute());

        assertEquals(ProcedureRunHandler.FailureCode.EXECUTOR_SESSION_UNAVAILABLE,
                result.failure());
        assertEquals(1, fixture.session.prepareCalls);
        assertEquals(1, fixture.executorFactoryCalls);
        assertEquals(0, fixture.executorCalls.get());
        assertEquals(0, fixture.executorCloseCalls);
        assertEquals(0, fixture.session.completeCalls);
    }

    @Test
    void executorSessionCloseFailureStopsWithoutCompletionOrTaskReplay() {
        Fixture fixture = new Fixture(command -> new Succeeded(1, 8, null));
        fixture.executorCloseFailure = true;

        ProcedureRunHandler.StoppedFailClosed result = assertInstanceOf(
                ProcedureRunHandler.StoppedFailClosed.class, fixture.execute());

        assertEquals(
                ProcedureRunHandler.FailureCode.EXECUTOR_SESSION_CLOSE_UNCONFIRMED,
                result.failure());
        assertEquals(1, fixture.executorCalls.get());
        assertEquals(1, fixture.executorCloseCalls);
        assertEquals(1, fixture.session.successCalls);
        assertEquals(0, fixture.session.completeCalls);
    }

    @Test
    void processLevelEngineFailureStillClosesTheExecutorSession() {
        Fixture fixture = new Fixture(command -> {
            throw new AssertionError("simulated process crash");
        });

        assertThrows(AssertionError.class, fixture::execute);

        assertEquals(1, fixture.executorCalls.get());
        assertEquals(1, fixture.executorCloseCalls);
        assertEquals(0, fixture.session.completeCalls);
    }

    private static final class Fixture {

        private final ActiveExecutionToken token = token();
        private final ProcedureRuntimePlan plan = plan();
        private final FakeJournalSession session = new FakeJournalSession();
        private final List<String> events = session.events;
        private final AtomicInteger executorCalls = new AtomicInteger();
        private int executorFactoryCalls;
        private int executorCloseCalls;
        private final ProcedureRunHandler handler;
        private ActiveExecutionToken journalFactoryToken;
        private ProcedureRuntimePlan journalFactoryPlan;
        private ActiveExecutionToken executorFactoryToken;
        private ProcedureRuntimePlan executorFactoryPlan;
        private boolean executorFactoryFailure;
        private boolean executorCloseFailure;

        private Fixture(ProcedureTaskExecutorPort executor) {
            handler = new ProcedureRunHandler((seenToken, seenPlan) -> {
                journalFactoryToken = seenToken;
                journalFactoryPlan = seenPlan;
                return session;
            }, (seenToken, seenPlan) -> {
                executorFactoryCalls++;
                executorFactoryToken = seenToken;
                executorFactoryPlan = seenPlan;
                if (executorFactoryFailure) {
                    throw new IllegalStateException("sensitive session creation failure");
                }
                return new ProcedureTaskExecutorSession() {
                    @Override
                    public TaskResult execute(TaskCommand command) {
                        executorCalls.incrementAndGet();
                        events.add("EXECUTE:" + command.task().id());
                        return executor.execute(command);
                    }

                    @Override
                    public void close() {
                        executorCloseCalls++;
                        events.add("EXECUTOR_CLOSE");
                        if (executorCloseFailure) {
                            throw new IllegalStateException(
                                    "sensitive session close failure");
                        }
                    }
                };
            });
        }

        private ProcedureRunHandler.RunResult execute() {
            return handler.execute(token, plan);
        }
    }

    private static final class FakeJournalSession
            implements ProcedureExecutionJournalSession {

        private final List<String> events = new ArrayList<>();
        private int prepareFailures;
        private int completeFailures;
        private int prepareCalls;
        private int completeCalls;
        private int successCalls;
        private int unknownCalls;
        private boolean prepareAccepted = true;
        private boolean startAccepted = true;

        @Override
        public boolean prepareRun() {
            prepareCalls++;
            events.add("PREPARE");
            if (prepareFailures-- > 0) {
                throw new ProcedureExecutionJournalException();
            }
            return prepareAccepted;
        }

        @Override
        public boolean completeRun() {
            completeCalls++;
            events.add("COMPLETE");
            if (completeFailures-- > 0) {
                throw new ProcedureExecutionJournalException();
            }
            return true;
        }

        @Override
        public boolean started(TaskEvidence evidence) {
            events.add("START:" + evidence.task().id());
            return startAccepted;
        }

        @Override
        public boolean succeeded(TaskEvidence evidence, long rowCount, long byteCount) {
            successCalls++;
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
            events.add("FAILED:" + evidence.task().id() + ":" + errorCode);
            return true;
        }

        @Override
        public boolean outcomeUnknown(TaskEvidence evidence, String errorCode) {
            unknownCalls++;
            events.add("UNKNOWN:" + evidence.task().id() + ":" + errorCode);
            return true;
        }
    }

    private static ProcedureRuntimePlan plan() {
        Task task = new Task(
                "TRUNCATE_TARGET", "Clear target", TaskType.SQL,
                ConnectionRole.TARGET, RiskClass.DESTRUCTIVE,
                "TRUNCATE TABLE INNOVA_ODI.STG_HAKEDIS_TIPI",
                "d".repeat(64), true, ErrorPolicy.STOP, 30,
                null, null, List.of());
        TaskBinding binding = new TaskBinding(
                task.id(), task.connectionRole(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "e".repeat(64), "INNOVA_ODI.STG_HAKEDIS_TIPI",
                "INNOVA_ODI", "STG_HAKEDIS_TIPI", "TABLO");
        return new ProcedureRuntimePlan(
                1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), List.of(task),
                Map.of(task.id(), binding), new ObjectMapper().createObjectNode());
    }

    private static ActiveExecutionToken token() {
        UUID runUuid = UUID.randomUUID();
        RunLeaseToken run = new RunLeaseToken(
                runUuid, "procedure-worker", 3,
                OffsetDateTime.parse("2026-09-11T20:00:00Z"));
        TargetFenceToken target = new TargetFenceToken(
                runUuid, run.workerReference(), run.generation(),
                UUID.randomUUID(), 7, "f".repeat(64), 1);
        return new ActiveExecutionToken(run, target);
    }
}

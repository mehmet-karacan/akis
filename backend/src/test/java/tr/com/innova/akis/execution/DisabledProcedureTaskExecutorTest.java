package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ProcedureRuntimePlan.ConnectionRole;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RiskClass;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskType;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.NotAttempted;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskCommand;

class DisabledProcedureTaskExecutorTest {

    @Test
    void alwaysReturnsDisabledWithoutInspectingOrExecutingTheCommand() {
        DisabledProcedureTaskExecutor executor = new DisabledProcedureTaskExecutor();
        TaskCommand command = validCommand();

        NotAttempted validResult = assertInstanceOf(
                NotAttempted.class,
                assertDoesNotThrow(() -> executor.execute(command)));
        NotAttempted uninspectedResult = assertInstanceOf(
                NotAttempted.class,
                assertDoesNotThrow(() -> executor.execute(null)));

        assertEquals("PROCEDURE_EXECUTOR_DISABLED", validResult.errorCode());
        assertEquals(validResult, uninspectedResult);
        assertEquals(0, DisabledProcedureTaskExecutor.class.getAnnotations().length);
    }

    private TaskCommand validCommand() {
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
        ProcedureRuntimePlan plan = new ProcedureRuntimePlan(
                1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), List.of(task),
                Map.of(task.id(), binding), new ObjectMapper().createObjectNode());
        return new TaskCommand(plan, 1, task, binding, null);
    }
}

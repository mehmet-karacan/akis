package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProcedureOracleSourceSqlContractTest {

    @Test
    void acceptsOnlyThePinnedBoundSourceSelectAndExtractsOrderedColumns() {
        ProcedureRuntimePlan.Task task = task(
                "SELECT ID, ACIKLAMA, TANIMLAMA_ZAMANI FROM TTBP.HAKEDIS_TIPI");
        ProcedureRuntimePlan plan = plan(task);

        var validated = ProcedureOracleSourceSqlContract.validate(
                plan, task, plan.bindings().get(task.id()));

        assertEquals(List.of("ID", "ACIKLAMA", "TANIMLAMA_ZAMANI"),
                validated.columns());
        assertEquals(1_000, validated.maximumRows());
    }

    @Test
    void rejectsPredicateBindObjectEscapeAndMultipleStatements() {
        for (String command : List.of(
                "SELECT ID FROM TTBP.HAKEDIS_TIPI WHERE ID = :ID",
                "SELECT ID FROM TTBP.OTHER_TABLE",
                "SELECT ID FROM TTBP.HAKEDIS_TIPI; DELETE FROM TTBP.HAKEDIS_TIPI")) {
            ProcedureRuntimePlan.Task task = task(command);
            ProcedureRuntimePlan plan = plan(task);
            assertThrows(IllegalArgumentException.class, () ->
                    ProcedureOracleSourceSqlContract.validate(
                            plan, task, plan.bindings().get(task.id())));
        }
    }

    @Test
    void acceptsTypedDateVariableAsAParameterizedPredicate() {
        String sql = "SELECT ID, TANIMLAMA_ZAMANI FROM TTBP.HAKEDIS_TIPI "
                + "WHERE TANIMLAMA_ZAMANI >= :KAYIT_TARIHI "
                + "AND TANIMLAMA_ZAMANI < :KAYIT_TARIHI + 1";
        ProcedureRuntimePlan.Task task = new ProcedureRuntimePlan.Task(
                "READ_SOURCE", "Read source", ProcedureRuntimePlan.TaskType.SQL,
                ProcedureRuntimePlan.ConnectionRole.SOURCE,
                ProcedureRuntimePlan.RiskClass.READ_ONLY, sql, "d".repeat(64), false,
                ProcedureRuntimePlan.ErrorPolicy.STOP, 30,
                new ProcedureRuntimePlan.RowsetOutput(1_000), null,
                List.of("KAYIT_TARIHI", "KAYIT_TARIHI"),
                Map.of("KAYIT_TARIHI", new ProcedureRuntimePlan.ParameterValue(
                        ProcedureRuntimePlan.ParameterType.DATE, "2026-09-14")),
                ProcedureRuntimePlan.LogCounter.NONE,
                ProcedureRuntimePlan.TransactionMode.AUTOCOMMIT, null,
                ProcedureRuntimePlan.TransactionIsolation.DRIVER_DEFAULT,
                ProcedureRuntimePlan.CommitMode.COMMIT);

        var validated = ProcedureOracleSourceSqlContract.validate(
                plan(task), task, plan(task).bindings().get(task.id()));

        assertEquals("SELECT ID, TANIMLAMA_ZAMANI FROM TTBP.HAKEDIS_TIPI "
                + "WHERE TANIMLAMA_ZAMANI >= ? AND TANIMLAMA_ZAMANI < ? + 1", validated.sql());
    }

    @Test
    void acceptsRownumLimitBoundToAnIntegerVariableWithOrderBy() {
        String sql = "SELECT ID, KOD FROM TTBP.HAKEDIS_TIPI WHERE ROWNUM <= :SATIR_LIMITI ORDER BY ID";
        ProcedureRuntimePlan.Task task = new ProcedureRuntimePlan.Task(
                "READ_SOURCE", "Read source", ProcedureRuntimePlan.TaskType.SQL,
                ProcedureRuntimePlan.ConnectionRole.SOURCE,
                ProcedureRuntimePlan.RiskClass.READ_ONLY, sql, "d".repeat(64), false,
                ProcedureRuntimePlan.ErrorPolicy.STOP, 30,
                new ProcedureRuntimePlan.RowsetOutput(1_000), null,
                List.of("SATIR_LIMITI"),
                Map.of("SATIR_LIMITI", new ProcedureRuntimePlan.ParameterValue(
                        ProcedureRuntimePlan.ParameterType.INTEGER, "1000")),
                ProcedureRuntimePlan.LogCounter.NONE,
                ProcedureRuntimePlan.TransactionMode.AUTOCOMMIT, null,
                ProcedureRuntimePlan.TransactionIsolation.DRIVER_DEFAULT,
                ProcedureRuntimePlan.CommitMode.COMMIT);
        var validated = ProcedureOracleSourceSqlContract.validate(plan(task), task, plan(task).bindings().get(task.id()));
        assertEquals("SELECT ID, KOD FROM TTBP.HAKEDIS_TIPI WHERE ROWNUM <= ? ORDER BY ID", validated.sql());
        for (String rejected : List.of("SELECT ID FROM TTBP.HAKEDIS_TIPI WHERE ROWNUM <= 1000",
                "SELECT ID FROM TTBP.HAKEDIS_TIPI ORDER BY LOWER(ID)",
                "SELECT ID FROM TTBP.HAKEDIS_TIPI WHERE ID IN (SELECT ID FROM TTBP.X)")) {
            ProcedureRuntimePlan.Task bad = task(rejected);
            ProcedureRuntimePlan plan = plan(bad);
            assertThrows(IllegalArgumentException.class, () -> ProcedureOracleSourceSqlContract.validate(plan, bad, plan.bindings().get(bad.id())));
        }
    }

    @Test
    void adapterRejectsSourceViewsUntilTheyHaveASeparateAttestationContract() {
        ProcedureRuntimePlan.TaskBinding view = binding("VIEW");

        assertThrows(IllegalArgumentException.class,
                () -> ProcedureOracleBindingAdapter.source(view));
    }

    private static ProcedureRuntimePlan plan(ProcedureRuntimePlan.Task task) {
        ProcedureRuntimePlan.TaskBinding binding = binding("TABLO");
        return new ProcedureRuntimePlan(
                1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), List.of(task),
                Map.of(task.id(), binding), new ObjectMapper().createObjectNode());
    }

    private static ProcedureRuntimePlan.Task task(String command) {
        return new ProcedureRuntimePlan.Task(
                "READ_SOURCE", "Read source",
                ProcedureRuntimePlan.TaskType.SQL,
                ProcedureRuntimePlan.ConnectionRole.SOURCE,
                ProcedureRuntimePlan.RiskClass.READ_ONLY,
                command, "d".repeat(64), false,
                ProcedureRuntimePlan.ErrorPolicy.STOP, 30,
                new ProcedureRuntimePlan.RowsetOutput(1_000), null, List.of());
    }

    private static ProcedureRuntimePlan.TaskBinding binding(String objectType) {
        return new ProcedureRuntimePlan.TaskBinding(
                "READ_SOURCE", ProcedureRuntimePlan.ConnectionRole.SOURCE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "e".repeat(64), "TTBP.HAKEDIS_TIPI", "TTBP",
                "HAKEDIS_TIPI", objectType);
    }
}

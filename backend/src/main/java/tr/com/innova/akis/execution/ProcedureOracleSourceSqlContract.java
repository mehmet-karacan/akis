package tr.com.innova.akis.execution;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Defense-in-depth validation immediately before a Procedure source SELECT. */
final class ProcedureOracleSourceSqlContract {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

    private ProcedureOracleSourceSqlContract() {
    }

    static ValidatedSource validate(
            ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task task,
            ProcedureRuntimePlan.TaskBinding binding) {
        if (plan == null || task == null || binding == null
                || task.type() != ProcedureRuntimePlan.TaskType.SQL
                || task.connectionRole() != ProcedureRuntimePlan.ConnectionRole.SOURCE
                || task.riskClass() != ProcedureRuntimePlan.RiskClass.READ_ONLY
                || task.requiresApproval() || task.input() != null || task.output() == null
                || task.output().maximumRows() < 1
                || task.output().maximumRows() > ProcedureRuntimePlan.MAXIMUM_ROWSET_ROWS
                || !task.namedBinds().isEmpty()
                || binding.role() != ProcedureRuntimePlan.ConnectionRole.SOURCE
                || !task.id().equals(binding.taskId())
                || !identifier(binding.owner()) || !identifier(binding.objectName())) {
            throw invalid();
        }
        Pattern select = Pattern.compile(
                "^SELECT\\s+(?<columns>[A-Z][A-Z0-9_$#]*(?:\\s*,\\s*[A-Z][A-Z0-9_$#]*)*)"
                        + "\\s+FROM\\s+" + Pattern.quote(binding.physicalIdentity()) + "$",
                Pattern.CASE_INSENSITIVE);
        var match = select.matcher(task.command().strip());
        if (!match.matches()) {
            throw invalid();
        }
        List<String> columns = Arrays.stream(match.group("columns").split(","))
                .map(String::strip)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList();
        if (columns.isEmpty() || columns.stream().anyMatch(column -> !identifier(column))
                || columns.stream().distinct().count() != columns.size()) {
            throw invalid();
        }
        return new ValidatedSource(
                plan.runtimePlanHash(), task.command().strip(),
                task.output().maximumRows(), columns);
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Procedure source SQL contract is invalid.");
    }

    record ValidatedSource(
            String runtimePlanHash,
            String sql,
            int maximumRows,
            List<String> columns) {
    }
}

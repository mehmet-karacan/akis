package tr.com.innova.akis.execution;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tr.com.innova.akis.metadata.NamedBindParser;

/** Defense-in-depth validation immediately before a Procedure source SELECT. */
final class ProcedureOracleSourceSqlContract {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

    private ProcedureOracleSourceSqlContract() {
    }

    static ValidatedSource validate(
            ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task task,
            ProcedureRuntimePlan.TaskBinding binding) {
        if (plan == null) throw invalid();
        List<String> columns = validateShape(task, binding);
        return new ValidatedSource(
                plan.runtimePlanHash(), ProcedureParameterBinder.positionalSql(task).strip(),
                task.output().maximumRows(), columns);
    }

    static List<String> validateShape(
            ProcedureRuntimePlan.Task task, ProcedureRuntimePlan.TaskBinding binding) {
        if (task == null || binding == null
                || task.type() != ProcedureRuntimePlan.TaskType.SQL
                || task.connectionRole() != ProcedureRuntimePlan.ConnectionRole.SOURCE
                || task.riskClass() != ProcedureRuntimePlan.RiskClass.READ_ONLY
                || task.requiresApproval() || task.input() != null || task.output() == null
                || task.output().maximumRows() < 1
                || task.output().maximumRows() > ProcedureRuntimePlan.MAXIMUM_ROWSET_ROWS
                || !NamedBindParser.parse(task.command()).equals(task.namedBinds())
                || !task.parameters().keySet().containsAll(task.namedBinds())
                || binding.role() != ProcedureRuntimePlan.ConnectionRole.SOURCE
                || !task.id().equals(binding.taskId())
                || !identifier(binding.owner()) || !identifier(binding.objectName())) {
            throw invalid();
        }
        // Filters: up to four AND-joined comparisons of a column (or ROWNUM) against a typed bind, the bind optionally
        // offset by "+ 1" (day-window idiom); an optional ORDER BY over plain columns. No literals, functions or subqueries.
        String comparison = "(?:ROWNUM|[A-Z][A-Z0-9_$#]*)\\s*(?:=|>=|<=|<|>)\\s*:[A-Z][A-Z0-9_]*(?:\\s*\\+\\s*1)?";
        Pattern select = Pattern.compile(
                "^SELECT\\s+(?<columns>[A-Z][A-Z0-9_$#]*(?:\\s*,\\s*[A-Z][A-Z0-9_$#]*)*)"
                        + "\\s+FROM\\s+" + Pattern.quote(binding.physicalIdentity())
                        + "(?:\\s+WHERE\\s+" + comparison + "(?:\\s+AND\\s+" + comparison + "){0,3})?"
                        + "(?:\\s+ORDER\\s+BY\\s+[A-Z][A-Z0-9_$#]*(?:\\s+(?:ASC|DESC))?(?:\\s*,\\s*[A-Z][A-Z0-9_$#]*(?:\\s+(?:ASC|DESC))?)*)?$",
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
        return columns;
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

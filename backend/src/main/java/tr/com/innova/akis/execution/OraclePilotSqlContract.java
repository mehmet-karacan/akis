package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;

/** Defense-in-depth checks used immediately before constructing pilot SQL. */
final class OraclePilotSqlContract {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private OraclePilotSqlContract() {
    }

    static ValidatedPlan validate(PilotRuntimePlan plan) {
        if (plan == null || plan.planVersion() != PilotRuntimePlan.CURRENT_VERSION
                || !isHash(plan.runtimePlanHash())
                || plan.maximumSourceRows() <= 0
                || plan.maximumSourceRows() > PilotRuntimePlan.MAXIMUM_SOURCE_ROWS
                || plan.writeStrategy() != WriteStrategy.ATOMIC_DELETE_INSERT) {
            throw invalidPlan();
        }
        DatasetBinding source = binding(plan.source(), DatasetRole.SOURCE);
        DatasetBinding target = binding(plan.target(), DatasetRole.TARGET);
        if (plan.columnMappings() == null || plan.columnMappings().isEmpty()) {
            throw invalidPlan();
        }

        List<String> sourceColumns = plan.columnMappings().stream()
                .map(DirectColumnMapping::sourceColumn)
                .toList();
        List<String> targetColumns = plan.columnMappings().stream()
                .map(DirectColumnMapping::targetColumn)
                .toList();
        validateColumns(sourceColumns);
        validateColumns(targetColumns);
        return new ValidatedPlan(
                plan.runtimePlanHash(), plan.maximumSourceRows(),
                source.owner(), source.objectName(),
                target.owner(), target.objectName(),
                sourceColumns, targetColumns);
    }

    private static DatasetBinding binding(DatasetBinding binding, DatasetRole role) {
        if (binding == null || binding.role() != role
                || binding.databaseType() != DatabaseType.ORACLE
                || binding.dataObjectType() != DataObjectType.TABLE
                || !identifier(binding.owner()) || !identifier(binding.objectName())) {
            throw invalidPlan();
        }
        return binding;
    }

    private static void validateColumns(List<String> columns) {
        Set<String> unique = new HashSet<>();
        if (columns.stream().anyMatch(column -> !identifier(column) || !unique.add(column))) {
            throw invalidPlan();
        }
    }

    private static boolean identifier(String value) {
        return value != null
                && value.getBytes(StandardCharsets.UTF_8).length <= 128
                && IDENTIFIER.matcher(value).matches();
    }

    private static boolean isHash(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static OraclePilotDataException invalidPlan() {
        return new OraclePilotDataException(
                OraclePilotDataException.Failure.INVALID_PLAN,
                "The Oracle pilot runtime plan is not executable.");
    }

    static String qualified(String owner, String objectName) {
        return quote(owner) + "." + quote(objectName);
    }

    static String quotedColumns(List<String> columns) {
        return columns.stream().map(OraclePilotSqlContract::quote)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(OraclePilotSqlContract::invalidPlan);
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    record ValidatedPlan(
            String runtimePlanHash,
            int maximumSourceRows,
            String sourceOwner,
            String sourceObject,
            String targetOwner,
            String targetObject,
            List<String> sourceColumns,
            List<String> targetColumns) {
    }
}

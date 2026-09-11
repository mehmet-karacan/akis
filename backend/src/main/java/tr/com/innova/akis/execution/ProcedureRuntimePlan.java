package tr.com.innova.akis.execution;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Immutable, secret-free execution contract for a bounded Oracle procedure. */
public record ProcedureRuntimePlan(
        int planVersion,
        String runtimePlanHash,
        String releaseHash,
        String scenarioPlanHash,
        UUID definitionUuid,
        UUID definitionVersionUuid,
        List<Task> tasks,
        Map<String, TaskBinding> bindings,
        JsonNode canonicalPlan) {

    public static final int CURRENT_VERSION = 1;
    public static final int MAXIMUM_TASKS = 1_000;
    public static final int MAXIMUM_ROWSET_ROWS = 1_000;
    public static final int MAXIMUM_TIMEOUT_SECONDS = 300;
    public static final int MAXIMUM_COMMAND_BYTES = 65_536;

    public ProcedureRuntimePlan {
        tasks = List.copyOf(tasks);
        bindings = Map.copyOf(bindings);
        canonicalPlan = canonicalPlan.deepCopy();
    }

    @Override
    public JsonNode canonicalPlan() {
        return canonicalPlan.deepCopy();
    }

    public record Task(
            String id,
            String name,
            TaskType type,
            ConnectionRole connectionRole,
            RiskClass riskClass,
            String command,
            String commandHash,
            boolean requiresApproval,
            ErrorPolicy onError,
            int timeoutSeconds,
            RowsetOutput output,
            BatchInput input,
            List<String> namedBinds) {

        public Task {
            namedBinds = List.copyOf(namedBinds);
        }
    }

    public record RowsetOutput(int maximumRows) {
    }

    public record BatchInput(String fromTask, int batchSize) {
    }

    public record TaskBinding(
            String taskId,
            ConnectionRole role,
            UUID definitionDataObjectUuid,
            UUID dataObjectUuid,
            UUID environmentSchemaBindingUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            UUID schemaSnapshotUuid,
            long bindingVersion,
            String schemaSnapshotFingerprint,
            String physicalIdentity,
            String owner,
            String objectName,
            String dataObjectType) {
    }

    public enum TaskType {
        SQL,
        PLSQL,
        STORED_PROCEDURE
    }

    public enum ConnectionRole {
        SOURCE,
        TARGET
    }

    public enum RiskClass {
        READ_ONLY,
        DML,
        DDL,
        DESTRUCTIVE
    }

    public enum ErrorPolicy {
        STOP,
        CONTINUE
    }
}

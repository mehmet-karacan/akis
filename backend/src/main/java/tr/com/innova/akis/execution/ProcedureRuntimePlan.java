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
            List<String> namedBinds,
            Map<String, ParameterValue> parameters,
            LogCounter logCounter,
            TransactionMode transactionMode,
            Integer transactionChannel,
            TransactionIsolation transactionIsolation,
            CommitMode commitMode) {

        public Task {
            namedBinds = List.copyOf(namedBinds);
            parameters = Map.copyOf(parameters);
            logCounter = logCounter == null ? LogCounter.NONE : logCounter;
            transactionMode = transactionMode == null ? TransactionMode.AUTOCOMMIT : transactionMode;
            transactionIsolation = transactionIsolation == null
                    ? TransactionIsolation.DRIVER_DEFAULT : transactionIsolation;
            commitMode = commitMode == null ? CommitMode.COMMIT : commitMode;
        }

        public Task(
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
            this(id, name, type, connectionRole, riskClass, command, commandHash,
                    requiresApproval, onError, timeoutSeconds, output, input,
                    namedBinds, Map.of(), LogCounter.NONE, TransactionMode.AUTOCOMMIT, null,
                    TransactionIsolation.DRIVER_DEFAULT, CommitMode.COMMIT);
        }

        public Task(String id, String name, TaskType type, ConnectionRole connectionRole,
                RiskClass riskClass, String command, String commandHash,
                boolean requiresApproval, ErrorPolicy onError, int timeoutSeconds,
                RowsetOutput output, BatchInput input, List<String> namedBinds,
                LogCounter logCounter, TransactionMode transactionMode,
                Integer transactionChannel, TransactionIsolation transactionIsolation,
                CommitMode commitMode) {
            this(id, name, type, connectionRole, riskClass, command, commandHash,
                    requiresApproval, onError, timeoutSeconds, output, input, namedBinds,
                    Map.of(), logCounter, transactionMode, transactionChannel,
                    transactionIsolation, commitMode);
        }
    }

    public record ParameterValue(
            ParameterType type, String value, ParameterSource source,
            String refreshQuery, UUID definitionUuid, UUID logicalSchemaUuid, String historyMode) {
        public ParameterValue(ParameterType type, String value, ParameterSource source, String query, UUID definitionUuid) {
            this(type, value, source, query, definitionUuid, null, "NONE");
        }
        public ParameterValue(ParameterType type, String value) {
            this(type, value, ParameterSource.VALUE, null, null);
        }
    }

    public enum ParameterType { STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP }
    public enum ParameterSource { VALUE, REFRESH_QUERY }

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

    public enum LogCounter {
        NONE,
        INSERT,
        UPDATE,
        DELETE,
        ERRORS
    }

    public enum TransactionMode {
        AUTOCOMMIT,
        TRANSACTION
    }

    public enum TransactionIsolation {
        DRIVER_DEFAULT,
        READ_COMMITTED,
        SERIALIZABLE
    }

    public enum CommitMode {
        NO_COMMIT,
        COMMIT
    }
}

package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.JdbcOracleSchemaPreflight.ExpectedSnapshot;
import tr.com.innova.akis.execution.JdbcPinnedSchemaSnapshotStore.PinnedProcedureSource;
import tr.com.innova.akis.execution.JdbcPinnedSchemaSnapshotStore.PinnedProcedureTarget;
import tr.com.innova.akis.execution.TargetIdentityPort.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Cell;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Column;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Handle;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Rowset;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.Scope;
import tr.com.innova.akis.execution.ProcedureRunScopedRowsetStore.ValueType;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.NotAttempted;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.OutcomeUnknown;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.RowsetHandle;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.SafeFailure;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.Succeeded;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskCommand;
import tr.com.innova.akis.execution.ProcedureTaskExecutorPort.TaskResult;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.oracle.OracleDatabaseIdentityFingerprintV1;

/** Execution-scoped, bounded Oracle Procedure adapter. Disabled unless explicitly enabled. */
@Component
@ConditionalOnProperty(name = "akis.execution.procedure-runtime-enabled", havingValue = "true")
final class JdbcOracleProcedureTaskExecutorSessionFactory
        implements ProcedureTaskExecutorSessionFactory {

    private final JdbcPinnedSchemaSnapshotStore snapshots;
    private final ProcedurePreflightContextPort contexts;
    private final RuntimeOracleConnectionProvider connections;
    private final JdbcOracleProcedureSourceReader sourceReader;
    private final ObjectMapper objectMapper;
    private JdbcProcedureVariableRuntime variableRuntime;

    @org.springframework.beans.factory.annotation.Autowired
    void setVariableRuntime(JdbcProcedureVariableRuntime variableRuntime) { this.variableRuntime = variableRuntime; }

    JdbcOracleProcedureTaskExecutorSessionFactory(
            JdbcPinnedSchemaSnapshotStore snapshots,
            ProcedurePreflightContextPort contexts,
            RuntimeOracleConnectionProvider connections,
            JdbcOracleProcedureSourceReader sourceReader,
            ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.contexts = contexts;
        this.connections = connections;
        this.sourceReader = sourceReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProcedureTaskExecutorSession forExecution(
            ActiveExecutionToken token, ProcedureRuntimePlan plan) {
        Objects.requireNonNull(token, "Active execution token is required.");
        Objects.requireNonNull(plan, "Procedure plan is required.");
        return new Session(token, plan);
    }

    static final class ProcedurePermit
            implements RuntimeOracleConnectionProvider.TargetDataPermit {
        private ProcedurePermit() {
        }
    }

    private final class Session implements ProcedureTaskExecutorSession {

        private final ActiveExecutionToken token;
        private final ProcedureRuntimePlan plan;
        private final ProcedureRunScopedRowsetStore rowsets;
        private final Map<UUID, Handle> handles = new LinkedHashMap<>();
        private final ProcedureVariableContext variables;
        private final Map<TransactionKey, ManagedTransaction> transactions = new LinkedHashMap<>();
        private boolean closed;

        private Session(ActiveExecutionToken token, ProcedureRuntimePlan plan) {
            this.token = token;
            this.plan = plan;
            this.variables = new ProcedureVariableContext(parameter -> {
                if (variableRuntime == null) throw new SQLException("Variable runtime is unavailable.");
                return variableRuntime.resolve(token.run().runUuid(), plan, parameter);
            });
            this.rowsets = new ProcedureRunScopedRowsetStore(new Scope(
                    token.run().runUuid(), token.run().generation(), plan.runtimePlanHash()));
        }

        @Override
        public TaskResult execute(TaskCommand command) {
            if (closed || command == null || command.plan() != plan) {
                return new NotAttempted("PROCEDURE_SESSION_INVALID");
            }
            return command.task().connectionRole() == ProcedureRuntimePlan.ConnectionRole.SOURCE
                    ? read(command) : mutate(command);
        }

        private TaskResult read(TaskCommand command) {
            RuntimeOracleSession session = null;
            try {
                PinnedProcedureSource pinned = snapshots.loadProcedureSource(
                        plan, command.task(), command.binding(), "AKTIF");
                PilotRuntimePlan.DatasetBinding binding =
                        ProcedureOracleBindingAdapter.source(command.binding());
                session = connections.openSource(binding);
                session.applyTransactionIsolation(command.task().transactionIsolation());
                new JdbcOracleSchemaPreflight(objectMapper).verifySource(
                        sourcePlan(command.task(), binding), session.connection(),
                        new ExpectedSnapshot(pinned.snapshot().schemaSnapshotUuid(),
                                pinned.snapshot().body()));
                OraclePilotBatch batch = sourceReader.read(
                        session, plan, command.task(), command.binding(), variables);
                String consumer = adjacentConsumer(command);
                // Pinned column protection: marked source columns are stored (and later inserted) encrypted.
                java.util.Set<String> protectedColumns = new java.util.HashSet<>();
                for (var name : plan.canonicalPlan().path("sensitiveColumns").path(command.task().id())) protectedColumns.add(name.asText().toUpperCase(java.util.Locale.ROOT));
                List<Boolean> encrypt = batch.columns().stream().map(column -> protectedColumns.contains(column.sourceColumn().toUpperCase(java.util.Locale.ROOT))).toList();
                Handle stored = rowsets.store(command.taskIndex(), command.task().id(), consumer,
                        batch.columns().stream().map(column -> new Column(
                                column.sourceColumn(), valueType(column.type()))).toList(),
                        batch.rows().stream().map(row -> {
                            List<Cell> cells = new java.util.ArrayList<>(row.size());
                            for (int i = 0; i < row.size(); i++) {
                                var cell = row.get(i);
                                cells.add(encrypt.get(i) && cell.canonicalValue() != null
                                        ? new Cell(ValueType.STRING, tr.com.innova.akis.security.DataProtectionCipher.encrypt(cell.canonicalValue()))
                                        : new Cell(valueType(cell.type()), cell.canonicalValue()));
                            }
                            return cells;
                        }).toList());
                handles.put(stored.uuid(), stored);
                return new Succeeded(stored.rowCount(), stored.byteCount(),
                        new RowsetHandle(stored.uuid(), plan.runtimePlanHash(),
                                command.task().id(), stored.rowCount(), stored.byteCount()),
                        ProcedureTaskExecutorPort.TransactionOutcome.NOT_APPLICABLE);
            }
            catch (RuntimeException exception) {
                return new SafeFailure("PROCEDURE_SOURCE_READ_FAILED", true);
            }
            finally {
                closeQuietly(session);
            }
        }

        private TaskResult mutate(TaskCommand command) {
            RuntimeOracleSession session = null;
            boolean statementEntered = false;
            boolean commitEntered = false;
            boolean managed = command.task().transactionMode()
                    == ProcedureRuntimePlan.TransactionMode.TRANSACTION;
            TransactionKey transactionKey = managed ? transactionKey(command) : null;
            try {
                PinnedProcedureTarget pinned = snapshots.loadProcedureTarget(
                        plan, command.task(), command.binding(), "AKTIF");
                PilotRuntimePlan.DatasetBinding binding =
                        ProcedureOracleBindingAdapter.target(command.binding());
                session = managed
                        ? managedTransaction(transactionKey, command, binding).session()
                        : connections.openTargetData(binding, new ProcedurePermit());
                if (!managed) {
                    session.applyTransactionIsolation(command.task().transactionIsolation());
                }
                verifyTarget(command, binding, pinned, session);
                statementEntered = true;
                long affected = command.task().input() == null
                        ? executeStandalone(command, session)
                        : executeBatch(command, session);
                if (!managed || command.task().commitMode()
                        == ProcedureRuntimePlan.CommitMode.COMMIT) {
                    commitEntered = true;
                    session.commitConfirmed();
                    if (managed) transactions.remove(transactionKey);
                    closeQuietly(session);
                    session = null;
                }
                return new Succeeded(affected, 0, null,
                        managed && command.task().commitMode()
                                == ProcedureRuntimePlan.CommitMode.NO_COMMIT
                                ? ProcedureTaskExecutorPort.TransactionOutcome.EXECUTED_UNCOMMITTED
                                : ProcedureTaskExecutorPort.TransactionOutcome.COMMIT_CONFIRMED);
            }
            catch (RuntimeException exception) {
                if (managed) transactions.remove(transactionKey);
                if (!statementEntered) {
                    rollbackQuietly(session);
                    closeQuietly(session);
                    return new NotAttempted("PROCEDURE_TARGET_PREFLIGHT_FAILED");
                }
                // Rollback cannot disprove a commit whose acknowledgement was lost.
                if (!commitEntered && command.task().riskClass() == ProcedureRuntimePlan.RiskClass.DML
                        && rollbackConfirmed(session)) {
                    closeQuietly(session);
                    return new SafeFailure("PROCEDURE_TARGET_DML_FAILED", true);
                }
                closeQuietly(session);
                return new OutcomeUnknown("PROCEDURE_TARGET_OUTCOME_UNKNOWN");
            }
            finally {
                if (!managed) closeQuietly(session);
            }
        }

        private TransactionKey transactionKey(TaskCommand command) {
            return new TransactionKey(
                    command.binding().connectionVersionUuid(),
                    command.task().transactionChannel());
        }

        private ManagedTransaction managedTransaction(
                TransactionKey key,
                TaskCommand command,
                PilotRuntimePlan.DatasetBinding binding) {
            ManagedTransaction current = transactions.get(key);
            if (current != null) {
                if (current.isolation() != command.task().transactionIsolation()) {
                    throw new IllegalArgumentException(
                            "A transaction channel cannot change isolation level.");
                }
                return current;
            }
            RuntimeOracleSession session = connections.openTargetData(binding, new ProcedurePermit());
            try {
                session.applyTransactionIsolation(command.task().transactionIsolation());
                ManagedTransaction created = new ManagedTransaction(
                        session, command.task().transactionIsolation());
                transactions.put(key, created);
                return created;
            }
            catch (RuntimeException exception) {
                closeQuietly(session);
                throw exception;
            }
        }

        private void verifyTarget(
                TaskCommand command,
                PilotRuntimePlan.DatasetBinding binding,
                PinnedProcedureTarget pinned,
                RuntimeOracleSession session) {
            new JdbcOracleSchemaPreflight(objectMapper).verifyTarget(
                    targetPlan(binding), session.connection(),
                    new ExpectedSnapshot(pinned.snapshot().schemaSnapshotUuid(),
                            pinned.snapshot().body()));
            CanonicalTargetIdentity identity = new JdbcOracleTargetIdentityReader().read(
                    session.connection(), binding.owner(), "TABLE", binding.objectName());
            if (!constantTimeEquals(token.target().canonicalTargetHash(),
                    identity.canonicalTargetHash())) {
                throw new IllegalStateException("Target fence identity changed.");
            }
            ProcedurePreflightContextPort.ConnectionEvidence evidence = contexts
                    .findConnectionEvidence(pinned.projectUuid(), binding.connectionVersionUuid())
                    .orElseThrow();
            var database = new OracleDatabaseIdentityFingerprintV1().canonicalize(
                    identity.site(), identity.container());
            if (evidence.identityVersion() != database.identityVersion()
                    || !constantTimeEquals(evidence.targetFingerprint(), database.fingerprint())) {
                throw new IllegalStateException("Target database identity changed.");
            }
            JdbcOracleTargetPrivilegeReader.Observation privileges =
                    new JdbcOracleTargetPrivilegeReader().read(session.connection(), binding.owner());
            if (!privileges.allRequired()) {
                throw new IllegalStateException("Target privileges are insufficient.");
            }
        }

        private long executeStandalone(TaskCommand command, RuntimeOracleSession session) {
            try (PreparedStatement statement = session.applyQueryTimeout(
                    session.connection().prepareStatement(ProcedureParameterBinder.positionalSql(command.task())))) {
                ProcedureParameterBinder.bind(statement, command.task(), variables,
                        command.binding().connectionVersionUuid());
                boolean resultSet = statement.execute();
                if (resultSet) {
                    throw new SQLException("Mutation unexpectedly returned a result set.");
                }
                int count = statement.getUpdateCount();
                return count < 0 ? 0 : count;
            }
            catch (SQLException exception) {
                throw new ProcedureOracleExecutionException();
            }
        }

        private long executeBatch(TaskCommand command, RuntimeOracleSession session) {
            RowsetHandle publicHandle = command.input();
            Handle handle = publicHandle == null ? null : handles.remove(publicHandle.uuid());
            if (handle == null) {
                throw new IllegalArgumentException("Rowset handle is unavailable.");
            }
            return rowsets.consume(handle, command.taskIndex(), command.task().id(),
                    rowset -> insertRows(command, session, rowset));
        }

        private long insertRows(
                TaskCommand command, RuntimeOracleSession session, Rowset rowset) {
            List<String> binds = command.task().namedBinds();
            if (binds.size() != rowset.columns().size()) {
                throw new IllegalArgumentException("Batch bind count does not match rowset.");
            }
            for (int index = 0; index < binds.size(); index++) {
                if (!binds.get(index).equals(rowset.columns().get(index).name())) {
                    throw new IllegalArgumentException("Batch bind order does not match rowset.");
                }
            }
            String sql = ProcedureParameterBinder.positionalSql(command.task());
            try (PreparedStatement statement = session.applyQueryTimeout(
                    session.connection().prepareStatement(sql))) {
                long affected = 0;
                int pending = 0;
                for (List<Cell> row : rowset.rows()) {
                    for (int index = 0; index < row.size(); index++) {
                        bind(statement, index + 1, row.get(index));
                    }
                    statement.addBatch();
                    pending++;
                    if (pending == command.task().input().batchSize()) {
                        affected += batchCount(statement.executeBatch());
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    affected += batchCount(statement.executeBatch());
                }
                if (affected != rowset.rows().size()) {
                    throw new SQLException("Oracle batch acknowledgement was incomplete.");
                }
                return affected;
            }
            catch (SQLException | NumberFormatException exception) {
                throw new ProcedureOracleExecutionException();
            }
        }

        private void bind(PreparedStatement statement, int index, Cell cell)
                throws SQLException {
            if (cell.value() == null) {
                statement.setObject(index, null);
                return;
            }
            switch (cell.type()) {
                case NUMBER -> statement.setBigDecimal(index, new BigDecimal(cell.value()));
                case STRING -> statement.setString(index, cell.value());
                case TIMESTAMP -> statement.setTimestamp(index,
                        Timestamp.valueOf(LocalDateTime.parse(cell.value())));
            }
        }

        private long batchCount(int[] counts) throws SQLException {
            long total = 0;
            for (int count : counts) {
                if (count == Statement.EXECUTE_FAILED) {
                    throw new SQLException("Oracle batch element failed.");
                }
                total += count == Statement.SUCCESS_NO_INFO ? 1 : count;
            }
            return total;
        }

        private String adjacentConsumer(TaskCommand command) {
            if (command.taskIndex() >= plan.tasks().size()) {
                throw new IllegalArgumentException("Source rowset has no adjacent consumer.");
            }
            ProcedureRuntimePlan.Task next = plan.tasks().get(command.taskIndex());
            if (next.input() == null || !command.task().id().equals(next.input().fromTask())) {
                throw new IllegalArgumentException("Source rowset consumer is invalid.");
            }
            return next.id();
        }

        private PilotRuntimePlan sourcePlan(
                ProcedureRuntimePlan.Task task, PilotRuntimePlan.DatasetBinding source) {
            return new PilotRuntimePlan(PilotRuntimePlan.CURRENT_VERSION,
                    plan.runtimePlanHash(), plan.releaseHash(), plan.scenarioPlanHash(),
                    plan.definitionUuid(), plan.definitionVersionUuid(),
                    task.output().maximumRows(), source, null, List.of(),
                    PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT, plan.canonicalPlan());
        }

        private PilotRuntimePlan targetPlan(PilotRuntimePlan.DatasetBinding target) {
            return new PilotRuntimePlan(PilotRuntimePlan.CURRENT_VERSION,
                    plan.runtimePlanHash(), plan.releaseHash(), plan.scenarioPlanHash(),
                    plan.definitionUuid(), plan.definitionVersionUuid(), 1, null, target,
                    List.of(), PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                    plan.canonicalPlan());
        }

        private ValueType valueType(OraclePilotColumnType type) {
            return switch (type) {
                case NUMBER -> ValueType.NUMBER;
                case VARCHAR2 -> ValueType.STRING;
                case TIMESTAMP -> ValueType.TIMESTAMP;
            };
        }

        private boolean rollbackConfirmed(RuntimeOracleSession session) {
            if (session == null) return false;
            try {
                session.rollbackConfirmed();
                return true;
            }
            catch (RuntimeException exception) {
                return false;
            }
        }

        private void rollbackQuietly(RuntimeOracleSession session) {
            if (session != null) {
                try { session.rollbackConfirmed(); } catch (RuntimeException ignored) { }
            }
        }

        private void closeQuietly(RuntimeOracleSession session) {
            if (session != null) {
                try { session.close(); } catch (RuntimeException ignored) { }
            }
        }

        private boolean constantTimeEquals(String left, String right) {
            if (left == null || right == null || left.length() != right.length()) return false;
            int difference = 0;
            for (int index = 0; index < left.length(); index++) {
                difference |= left.charAt(index) ^ right.charAt(index);
            }
            return difference == 0;
        }

        @Override
        public void complete() {
            ensureActive();
            RuntimeException failure = null;
            for (ManagedTransaction transaction : transactions.values()) {
                try {
                    if (failure == null) transaction.session().commitConfirmed();
                    else transaction.session().rollbackConfirmed();
                }
                catch (RuntimeException exception) {
                    if (failure == null) failure = exception;
                }
                finally {
                    closeQuietly(transaction.session());
                }
            }
            transactions.clear();
            if (failure != null) throw failure;
        }

        @Override
        public void abort() {
            if (closed) return;
            RuntimeException failure = null;
            for (ManagedTransaction transaction : transactions.values()) {
                try {
                    transaction.session().rollbackConfirmed();
                }
                catch (RuntimeException exception) {
                    failure = exception;
                }
                finally {
                    closeQuietly(transaction.session());
                }
            }
            transactions.clear();
            if (failure != null) throw failure;
        }

        private void ensureActive() {
            if (closed) throw new IllegalStateException("Procedure session is closed.");
        }

        @Override
        public void close() {
            if (!closed) {
                try { abort(); }
                finally {
                    closed = true;
                    handles.clear();
                    variables.clear();
                    rowsets.close();
                }
            }
        }

        private record TransactionKey(UUID connectionVersionUuid, int channel) {
        }

        private record ManagedTransaction(
                RuntimeOracleSession session,
                ProcedureRuntimePlan.TransactionIsolation isolation) {
        }
    }
}

final class ProcedureOracleExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}

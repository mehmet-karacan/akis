package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.OracleTargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.FenceEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.RecordedEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.TargetLedgerContext;

@Component
final class JdbcOracleTargetLedgerAdapter implements OracleTargetLedgerPort {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern TRANSACTION_GUARD = Pattern.compile("[0-9a-f]{32}");

    @Override
    public FenceSession bindFence(Connection connection, TargetLedgerContext context) {
        return new JdbcFenceSession(connection, valid(context));
    }

    @Override
    public DataLedgerSession bindData(Connection connection, TargetLedgerContext context) {
        return new JdbcDataLedgerSession(connection, valid(context));
    }

    @Override
    public ReconciliationSession bindReconciliation(
            Connection connection, TargetLedgerContext context) {
        return new JdbcReconciliationSession(connection, valid(context));
    }

    private static final class JdbcFenceSession implements FenceSession {

        private static final String ACQUIRE_FENCE =
                "{call ETL_KANIT_PKG.ACQUIRE_FENCE(?,?,?,?,?,?,?)}";

        private final Connection connection;
        private final TargetLedgerContext context;

        private JdbcFenceSession(Connection connection, TargetLedgerContext context) {
            this.connection = connection;
            this.context = context;
            ensureTransactionConnection();
        }

        @Override
        public void acquireFence() {
            call(ACQUIRE_FENCE, statement -> {
                statement.setString(1, context.canonicalTargetHash());
                statement.setLong(2, context.fenceToken());
                statement.setString(3, context.jobRequestUuid().toString());
                statement.setString(4, context.runUuid().toString());
                statement.setInt(5, context.attemptNumber());
                statement.setString(6, context.releaseHash());
                statement.setString(7, context.planHash());
                statement.execute();
                return null;
            });
        }

        private <T> T call(String sql, SqlCallable<T> callable) {
            ensureTransactionConnection();
            try (CallableStatement statement = connection.prepareCall(sql)) {
                return callable.execute(statement);
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        private void ensureTransactionConnection() {
            JdbcOracleTargetLedgerAdapter.ensureTransactionConnection(connection);
        }
    }

    private static final class JdbcDataLedgerSession implements DataLedgerSession {

        private static final String PREPARE_BATCH =
                "{call ETL_KANIT_PKG.PREPARE_BATCH(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)}";
        private static final String RECORD_BATCH =
                "{call ETL_KANIT_PKG.RECORD_BATCH(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)}";
        private static final String PREPARE_PUBLISH =
                "{call ETL_KANIT_PKG.PREPARE_PUBLISH(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)}";
        private static final String RECORD_PUBLISH =
                "{call ETL_KANIT_PKG.RECORD_PUBLISH(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)}";

        private final Connection connection;
        private final TargetLedgerContext context;
        private final UUID bindingId = UUID.randomUUID();

        private JdbcDataLedgerSession(
                Connection connection, TargetLedgerContext context) {
            this.connection = connection;
            this.context = context;
            ensureTransactionConnection();
        }

        @Override
        public BatchPreparation prepareBatch(BatchEvidence evidence) {
            BatchEvidence safeEvidence = valid(evidence);
            return call(PREPARE_BATCH, statement -> {
                batch(statement, 1, safeEvidence, false);
                statement.registerOutParameter(15, Types.VARCHAR);
                statement.registerOutParameter(16, Types.NUMERIC);
                statement.execute();
                boolean alreadyRecorded = statement.getInt(16) == 1;
                String guard = alreadyRecorded ? null : statement.getString(15);
                if (!alreadyRecorded && !isTransactionGuard(guard)) {
                    throw new OracleLedgerUnavailableException(new IllegalStateException(
                            "Oracle ledger returned an invalid batch transaction guard."));
                }
                return new BatchPreparation(
                        bindingId, safeEvidence, guard, alreadyRecorded);
            });
        }

        @Override
        public void recordBatch(BatchPreparation preparation) {
            BatchPreparation safePreparation = batchPreparation(preparation);
            call(RECORD_BATCH, statement -> {
                statement.setString(1, safePreparation.transactionGuard());
                batch(statement, 2, safePreparation.evidence(), false);
                statement.execute();
                return null;
            });
        }

        @Override
        public PublishPreparation preparePublish(PublishEvidence evidence) {
            PublishEvidence safeEvidence = valid(evidence);
            return call(PREPARE_PUBLISH, statement -> {
                publish(statement, 1, safeEvidence, false);
                statement.registerOutParameter(16, Types.VARCHAR);
                statement.registerOutParameter(17, Types.NUMERIC);
                statement.execute();
                boolean alreadyRecorded = statement.getInt(17) == 1;
                String guard = alreadyRecorded ? null : statement.getString(16);
                if (!alreadyRecorded && !isTransactionGuard(guard)) {
                    throw new OracleLedgerUnavailableException(new IllegalStateException(
                            "Oracle ledger returned an invalid publish transaction guard."));
                }
                return new PublishPreparation(
                        bindingId, safeEvidence, guard, alreadyRecorded);
            });
        }

        @Override
        public void recordPublish(PublishPreparation preparation) {
            PublishPreparation safePreparation = publishPreparation(preparation);
            call(RECORD_PUBLISH, statement -> {
                statement.setString(1, safePreparation.transactionGuard());
                publish(statement, 2, safePreparation.evidence(), false);
                statement.execute();
                return null;
            });
        }

        private void batch(
                CallableStatement statement,
                int first,
                BatchEvidence evidence,
                boolean verification) throws SQLException {
            statement.setString(first, context.canonicalTargetHash());
            statement.setString(first + 1, context.jobRequestUuid().toString());
            statement.setString(first + 2, evidence.stepCode());
            statement.setString(first + 3, evidence.partitionCode());
            statement.setString(first + 4, evidence.batchKeyHash());
            statement.setLong(first + 5, evidence.batchNumber());
            int next = first + 6;
            if (!verification) {
                statement.setString(next++, context.runUuid().toString());
                statement.setInt(next++, context.attemptNumber());
                statement.setLong(next++, context.fenceToken());
            }
            statement.setString(next++, context.releaseHash());
            statement.setString(next++, context.planHash());
            statement.setString(next++, evidence.payloadHash());
            statement.setLong(next++, evidence.rowCount());
            statement.setLong(next, evidence.byteCount());
        }

        private void publish(
                CallableStatement statement,
                int first,
                PublishEvidence evidence,
                boolean verification) throws SQLException {
            statement.setString(first, context.canonicalTargetHash());
            statement.setString(first + 1, context.jobRequestUuid().toString());
            statement.setString(first + 2, evidence.stepCode());
            statement.setString(first + 3, evidence.publishKeyHash());
            int next = first + 4;
            if (!verification) {
                statement.setString(next++, context.runUuid().toString());
                statement.setInt(next++, context.attemptNumber());
                statement.setLong(next++, context.fenceToken());
            }
            statement.setString(next++, context.releaseHash());
            statement.setString(next++, context.planHash());
            statement.setString(next++, evidence.stageHash());
            statement.setLong(next++, evidence.stageRowCount());
            statement.setLong(next++, evidence.publishedRowCount());
            statement.setLong(next++, evidence.rejectedRowCount());
            statement.setString(next++, evidence.lowerWatermark());
            statement.setString(next, evidence.upperWatermark());
        }

        private BatchPreparation batchPreparation(BatchPreparation preparation) {
            if (preparation == null || !bindingId.equals(preparation.sessionBindingId())
                    || preparation.alreadyRecorded()
                    || !isTransactionGuard(preparation.transactionGuard())) {
                throw new IllegalArgumentException(
                        "Batch preparation is not recordable by this bound session.");
            }
            valid(preparation.evidence());
            return preparation;
        }

        private PublishPreparation publishPreparation(PublishPreparation preparation) {
            if (preparation == null || !bindingId.equals(preparation.sessionBindingId())
                    || preparation.alreadyRecorded()
                    || !isTransactionGuard(preparation.transactionGuard())) {
                throw new IllegalArgumentException(
                        "Publish preparation is not recordable by this bound session.");
            }
            valid(preparation.evidence());
            return preparation;
        }

        private <T> T call(String sql, SqlCallable<T> callable) {
            ensureTransactionConnection();
            try (CallableStatement statement = connection.prepareCall(sql)) {
                return callable.execute(statement);
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        private void ensureTransactionConnection() {
            JdbcOracleTargetLedgerAdapter.ensureTransactionConnection(connection);
        }

    }

    private static final class JdbcReconciliationSession implements ReconciliationSession {

        private static final String READ_FENCE =
                "{call ETL_KANIT_PKG.READ_FENCE(?,?,?,?,?,?,?,?,?)}";
        private static final String VERIFY_BATCH =
                "{call ETL_KANIT_PKG.VERIFY_BATCH(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)}";
        private static final String VERIFY_PUBLISH =
                "{call ETL_KANIT_PKG.VERIFY_PUBLISH(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)}";

        private final Connection connection;
        private final TargetLedgerContext context;

        private JdbcReconciliationSession(
                Connection connection, TargetLedgerContext context) {
            this.connection = connection;
            this.context = context;
            ensureTransactionConnection();
        }

        @Override
        public Optional<FenceEvidence> readFence() {
            return call(READ_FENCE, statement -> {
                statement.setString(1, context.canonicalTargetHash());
                statement.registerOutParameter(2, Types.NUMERIC);
                statement.registerOutParameter(3, Types.NUMERIC);
                statement.registerOutParameter(4, Types.VARCHAR);
                statement.registerOutParameter(5, Types.VARCHAR);
                statement.registerOutParameter(6, Types.NUMERIC);
                statement.registerOutParameter(7, Types.VARCHAR);
                statement.registerOutParameter(8, Types.VARCHAR);
                statement.registerOutParameter(9, Types.TIMESTAMP_WITH_TIMEZONE);
                statement.execute();
                if (statement.getInt(2) != 1) {
                    return Optional.empty();
                }
                return Optional.of(new FenceEvidence(
                        statement.getLong(3), uuid(statement.getString(4)),
                        uuid(statement.getString(5)), statement.getInt(6),
                        statement.getString(7), statement.getString(8),
                        timestamp(statement, 9)));
            });
        }

        @Override
        public Optional<RecordedEvidence> verifyBatch(BatchEvidence evidence) {
            BatchEvidence safeEvidence = valid(evidence);
            return call(VERIFY_BATCH, statement -> {
                int next = batchKey(statement, 1, safeEvidence);
                statement.setString(next++, context.releaseHash());
                statement.setString(next++, context.planHash());
                statement.setString(next++, safeEvidence.payloadHash());
                statement.setLong(next++, safeEvidence.rowCount());
                statement.setLong(next, safeEvidence.byteCount());
                registerVerification(statement, 12);
                statement.execute();
                return verification(statement, 12);
            });
        }

        @Override
        public Optional<RecordedEvidence> verifyPublish(PublishEvidence evidence) {
            PublishEvidence safeEvidence = valid(evidence);
            return call(VERIFY_PUBLISH, statement -> {
                statement.setString(1, context.canonicalTargetHash());
                statement.setString(2, context.jobRequestUuid().toString());
                statement.setString(3, safeEvidence.stepCode());
                statement.setString(4, safeEvidence.publishKeyHash());
                statement.setString(5, context.releaseHash());
                statement.setString(6, context.planHash());
                statement.setString(7, safeEvidence.stageHash());
                statement.setLong(8, safeEvidence.stageRowCount());
                statement.setLong(9, safeEvidence.publishedRowCount());
                statement.setLong(10, safeEvidence.rejectedRowCount());
                statement.setString(11, safeEvidence.lowerWatermark());
                statement.setString(12, safeEvidence.upperWatermark());
                registerVerification(statement, 13);
                statement.execute();
                return verification(statement, 13);
            });
        }

        private int batchKey(
                CallableStatement statement,
                int first,
                BatchEvidence evidence) throws SQLException {
            statement.setString(first, context.canonicalTargetHash());
            statement.setString(first + 1, context.jobRequestUuid().toString());
            statement.setString(first + 2, evidence.stepCode());
            statement.setString(first + 3, evidence.partitionCode());
            statement.setString(first + 4, evidence.batchKeyHash());
            statement.setLong(first + 5, evidence.batchNumber());
            return first + 6;
        }

        private void registerVerification(
                CallableStatement statement, int first) throws SQLException {
            statement.registerOutParameter(first, Types.NUMERIC);
            statement.registerOutParameter(first + 1, Types.VARCHAR);
            statement.registerOutParameter(first + 2, Types.NUMERIC);
            statement.registerOutParameter(first + 3, Types.NUMERIC);
            statement.registerOutParameter(first + 4, Types.TIMESTAMP_WITH_TIMEZONE);
        }

        private Optional<RecordedEvidence> verification(
                CallableStatement statement, int first) throws SQLException {
            if (statement.getInt(first) != 1) {
                return Optional.empty();
            }
            return Optional.of(new RecordedEvidence(
                    uuid(statement.getString(first + 1)),
                    statement.getInt(first + 2), statement.getLong(first + 3),
                    timestamp(statement, first + 4)));
        }

        private <T> T call(String sql, SqlCallable<T> callable) {
            ensureTransactionConnection();
            try (CallableStatement statement = connection.prepareCall(sql)) {
                return callable.execute(statement);
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        private void ensureTransactionConnection() {
            JdbcOracleTargetLedgerAdapter.ensureTransactionConnection(connection);
        }
    }

    private static void ensureTransactionConnection(Connection connection) {
        try {
            if (connection == null || connection.isClosed()
                    || connection.getAutoCommit() || connection.isReadOnly()) {
                throw new OracleLedgerUnavailableException(new IllegalStateException(
                        "Oracle ledger requires an open, writable, auto-commit-disabled connection."));
            }
        }
        catch (SQLException exception) {
            throw new OracleLedgerUnavailableException(exception);
        }
    }

    private static TargetLedgerContext valid(TargetLedgerContext context) {
        if (context == null || !isHash(context.canonicalTargetHash())
                || context.fenceToken() <= 0 || context.jobRequestUuid() == null
                || context.runUuid() == null || context.attemptNumber() <= 0
                || !isHash(context.releaseHash()) || !isHash(context.planHash())) {
            throw new IllegalArgumentException("A complete canonical target ledger context is required.");
        }
        return context;
    }

    private static BatchEvidence valid(BatchEvidence evidence) {
        if (evidence == null || !isCode(evidence.stepCode())
                || !isCode(evidence.partitionCode()) || !isHash(evidence.batchKeyHash())
                || evidence.batchNumber() < 0 || !isHash(evidence.payloadHash())
                || evidence.rowCount() < 0 || evidence.byteCount() < 0) {
            throw new IllegalArgumentException("Batch evidence is not canonical.");
        }
        return evidence;
    }

    private static PublishEvidence valid(PublishEvidence evidence) {
        if (evidence == null || !isCode(evidence.stepCode())
                || !isHash(evidence.publishKeyHash()) || !isHash(evidence.stageHash())
                || evidence.stageRowCount() < 0 || evidence.publishedRowCount() < 0
                || evidence.rejectedRowCount() < 0
                || (evidence.lowerWatermark() == null) != (evidence.upperWatermark() == null)
                || tooLongWatermark(evidence.lowerWatermark())
                || tooLongWatermark(evidence.upperWatermark())) {
            throw new IllegalArgumentException("Publish evidence is not canonical.");
        }
        return evidence;
    }

    private static boolean isHash(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static boolean isCode(String value) {
        return value != null && value.length() <= 128 && CODE.matcher(value).matches();
    }

    private static boolean isTransactionGuard(String value) {
        return value != null && TRANSACTION_GUARD.matcher(value).matches();
    }

    private static boolean tooLongWatermark(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length > 1_000;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException exception) {
            throw new OracleLedgerUnavailableException(exception);
        }
    }

    private static OffsetDateTime timestamp(
            CallableStatement statement, int index) throws SQLException {
        return statement.getObject(index, OffsetDateTime.class);
    }

    private static OracleTargetLedgerException translate(SQLException exception) {
        int code = oracleErrorCode(exception);
        return switch (code) {
            case 20001, 20002, 20003, 20004, 20005, 20006, 20020 ->
                    new OracleLedgerInputException(code, exception);
            case 20010 -> new OracleFenceRejectedException(
                    OracleLedgerFailure.FENCE_NOT_FOUND, code, exception);
            case 20011 -> new OracleFenceRejectedException(
                    OracleLedgerFailure.FENCE_OWNERSHIP_MISMATCH, code, exception);
            case 20012 -> new OracleFenceRejectedException(
                    OracleLedgerFailure.STALE_FENCE_TOKEN, code, exception);
            case 20013 -> new OracleFenceRejectedException(
                    OracleLedgerFailure.FENCE_OWNER_CONFLICT, code, exception);
            case 20014, 20017, 20018, 20021, 20023, 20024, 20026 ->
                    new OracleLedgerTransactionException(code, exception);
            case 20015, 20016, 20019 -> new OracleLedgerConflictException(
                    OracleLedgerFailure.BATCH_EVIDENCE_CONFLICT, code, exception);
            case 20022, 20025 -> new OracleLedgerConflictException(
                    OracleLedgerFailure.PUBLISH_EVIDENCE_CONFLICT, code, exception);
            default -> new OracleLedgerUnavailableException(exception);
        };
    }

    private static int oracleErrorCode(SQLException exception) {
        SQLException current = exception;
        int firstNonZero = 0;
        while (current != null) {
            int code = Math.abs(current.getErrorCode());
            if (code >= 20001 && code <= 20026) {
                return code;
            }
            if (firstNonZero == 0 && code != 0) {
                firstNonZero = code;
            }
            current = current.getNextException();
        }
        return firstNonZero;
    }

    @FunctionalInterface
    private interface SqlCallable<T> {

        T execute(CallableStatement statement) throws SQLException;
    }
}

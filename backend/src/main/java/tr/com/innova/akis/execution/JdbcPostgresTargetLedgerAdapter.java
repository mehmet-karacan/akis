package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.TargetLedgerPort.RecordedEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;

/**
 * PostgreSQL adapter of {@link TargetLedgerPort} over the target-local {@code akis_yayin_defteri} functions
 * (database/postgres-target). Same protocol as the Oracle adapter: a bound session never commits, prepare and record
 * share one transaction (the guard is a transaction-local setting on the server), reconciliation reads a fresh
 * connection. Ledger errors arrive as SQLSTATE AK0nn and map onto the shared {@link OracleLedgerFailure} vocabulary.
 */
final class JdbcPostgresTargetLedgerAdapter implements TargetLedgerPort {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern TRANSACTION_GUARD = Pattern.compile("[0-9a-f]{32}");

    @Override
    public FenceSession bindFence(Connection connection, TargetLedgerContext context) {
        return new PgFenceSession(connection, valid(context));
    }

    @Override
    public DataLedgerSession bindData(Connection connection, TargetLedgerContext context) {
        return new PgDataLedgerSession(connection, valid(context));
    }

    @Override
    public ReconciliationSession bindReconciliation(Connection connection, TargetLedgerContext context) {
        return new PgReconciliationSession(connection, valid(context));
    }

    private static final class PgFenceSession implements FenceSession {
        private final Connection connection;
        private final TargetLedgerContext context;

        private PgFenceSession(Connection connection, TargetLedgerContext context) {
            this.connection = connection;
            this.context = context;
            ensureTransactionConnection(connection);
        }

        @Override
        public void acquireFence() {
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement("select akis_yayin_defteri.cit_al(?,?,?,?,?,?,?)")) {
                owner(statement, 1, context);
                statement.execute();
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }
    }

    private static final class PgDataLedgerSession implements DataLedgerSession {
        private static final String PREPARE_BATCH = "select koruma, zaten_kayitli from akis_yayin_defteri.parti_hazirla(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        private static final String RECORD_BATCH = "select akis_yayin_defteri.parti_kaydet(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        private static final String PREPARE_PUBLISH = "select koruma, zaten_kayitli from akis_yayin_defteri.yayin_hazirla(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        private static final String RECORD_PUBLISH = "select akis_yayin_defteri.yayin_kaydet(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        private final Connection connection;
        private final TargetLedgerContext context;
        private final UUID bindingId = UUID.randomUUID();

        private PgDataLedgerSession(Connection connection, TargetLedgerContext context) {
            this.connection = connection;
            this.context = context;
            ensureTransactionConnection(connection);
        }

        @Override
        public BatchPreparation prepareBatch(BatchEvidence evidence) {
            BatchEvidence safe = valid(evidence);
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement(PREPARE_BATCH)) {
                batch(statement, 1, safe);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new OracleLedgerUnavailableException(new IllegalStateException("PostgreSQL ledger returned no batch preparation."));
                    boolean alreadyRecorded = result.getBoolean("zaten_kayitli");
                    String guard = alreadyRecorded ? null : result.getString("koruma");
                    if (!alreadyRecorded && !isTransactionGuard(guard)) {
                        throw new OracleLedgerUnavailableException(new IllegalStateException("PostgreSQL ledger returned an invalid batch transaction guard."));
                    }
                    return new BatchPreparation(bindingId, safe, guard, alreadyRecorded);
                }
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        @Override
        public void recordBatch(BatchPreparation preparation) {
            BatchPreparation safe = batchPreparation(preparation);
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement(RECORD_BATCH)) {
                statement.setString(1, safe.transactionGuard());
                batch(statement, 2, safe.evidence());
                statement.execute();
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        @Override
        public PublishPreparation preparePublish(PublishEvidence evidence) {
            PublishEvidence safe = valid(evidence);
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement(PREPARE_PUBLISH)) {
                publish(statement, 1, safe);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new OracleLedgerUnavailableException(new IllegalStateException("PostgreSQL ledger returned no publish preparation."));
                    boolean alreadyRecorded = result.getBoolean("zaten_kayitli");
                    String guard = alreadyRecorded ? null : result.getString("koruma");
                    if (!alreadyRecorded && !isTransactionGuard(guard)) {
                        throw new OracleLedgerUnavailableException(new IllegalStateException("PostgreSQL ledger returned an invalid publish transaction guard."));
                    }
                    return new PublishPreparation(bindingId, safe, guard, alreadyRecorded);
                }
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        @Override
        public void recordPublish(PublishPreparation preparation) {
            PublishPreparation safe = publishPreparation(preparation);
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement(RECORD_PUBLISH)) {
                statement.setString(1, safe.transactionGuard());
                publish(statement, 2, safe.evidence());
                statement.execute();
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        /** hedef, is, adim, bolum, parti_anahtar, parti_no, calistirma, deneme, cit, surum, plan, yuk, satir, bayt */
        private void batch(PreparedStatement statement, int first, BatchEvidence evidence) throws SQLException {
            statement.setString(first, context.canonicalTargetHash());
            statement.setObject(first + 1, context.jobRequestUuid());
            statement.setString(first + 2, evidence.stepCode());
            statement.setString(first + 3, evidence.partitionCode());
            statement.setString(first + 4, evidence.batchKeyHash());
            statement.setLong(first + 5, evidence.batchNumber());
            statement.setObject(first + 6, context.runUuid());
            statement.setInt(first + 7, context.attemptNumber());
            statement.setLong(first + 8, context.fenceToken());
            statement.setString(first + 9, context.releaseHash());
            statement.setString(first + 10, context.planHash());
            statement.setString(first + 11, evidence.payloadHash());
            statement.setLong(first + 12, evidence.rowCount());
            statement.setLong(first + 13, evidence.byteCount());
        }

        /** hedef, is, adim, yayin_anahtar, calistirma, deneme, cit, surum, plan, asama, asama_satir, yayinlanan, reddedilen, alt, ust */
        private void publish(PreparedStatement statement, int first, PublishEvidence evidence) throws SQLException {
            statement.setString(first, context.canonicalTargetHash());
            statement.setObject(first + 1, context.jobRequestUuid());
            statement.setString(first + 2, evidence.stepCode());
            statement.setString(first + 3, evidence.publishKeyHash());
            statement.setObject(first + 4, context.runUuid());
            statement.setInt(first + 5, context.attemptNumber());
            statement.setLong(first + 6, context.fenceToken());
            statement.setString(first + 7, context.releaseHash());
            statement.setString(first + 8, context.planHash());
            statement.setString(first + 9, evidence.stageHash());
            statement.setLong(first + 10, evidence.stageRowCount());
            statement.setLong(first + 11, evidence.publishedRowCount());
            statement.setLong(first + 12, evidence.rejectedRowCount());
            nullableText(statement, first + 13, evidence.lowerWatermark());
            nullableText(statement, first + 14, evidence.upperWatermark());
        }

        private BatchPreparation batchPreparation(BatchPreparation preparation) {
            if (preparation == null || !bindingId.equals(preparation.sessionBindingId()) || preparation.alreadyRecorded()
                    || !isTransactionGuard(preparation.transactionGuard())) {
                throw new IllegalArgumentException("Batch preparation is not recordable by this bound session.");
            }
            valid(preparation.evidence());
            return preparation;
        }

        private PublishPreparation publishPreparation(PublishPreparation preparation) {
            if (preparation == null || !bindingId.equals(preparation.sessionBindingId()) || preparation.alreadyRecorded()
                    || !isTransactionGuard(preparation.transactionGuard())) {
                throw new IllegalArgumentException("Publish preparation is not recordable by this bound session.");
            }
            valid(preparation.evidence());
            return preparation;
        }
    }

    private static final class PgReconciliationSession implements ReconciliationSession {
        private static final String READ_FENCE = "select bulundu, cit_belirteci, is_uuid, calistirma_uuid, deneme_no, surum_ozeti, plan_ozeti, guncellenme_zamani from akis_yayin_defteri.cit_oku(?)";
        private static final String VERIFY_BATCH = "select eslesti, calistirma_uuid, deneme_no, cit_belirteci, kanit_zamani from akis_yayin_defteri.parti_dogrula(?,?,?,?,?,?,?,?,?,?,?)";
        private static final String VERIFY_PUBLISH = "select eslesti, calistirma_uuid, deneme_no, cit_belirteci, kanit_zamani from akis_yayin_defteri.yayin_dogrula(?,?,?,?,?,?,?,?,?,?,?,?)";

        private final Connection connection;
        private final TargetLedgerContext context;

        private PgReconciliationSession(Connection connection, TargetLedgerContext context) {
            this.connection = connection;
            this.context = context;
            ensureTransactionConnection(connection);
        }

        @Override
        public Optional<FenceEvidence> readFence() {
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement(READ_FENCE)) {
                statement.setString(1, context.canonicalTargetHash());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean("bulundu")) return Optional.empty();
                    return Optional.of(new FenceEvidence(result.getLong("cit_belirteci"), result.getObject("is_uuid", UUID.class),
                            result.getObject("calistirma_uuid", UUID.class), result.getInt("deneme_no"), result.getString("surum_ozeti"),
                            result.getString("plan_ozeti"), result.getObject("guncellenme_zamani", OffsetDateTime.class)));
                }
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        @Override
        public Optional<RecordedEvidence> verifyBatch(BatchEvidence evidence) {
            BatchEvidence safe = valid(evidence);
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement(VERIFY_BATCH)) {
                statement.setString(1, context.canonicalTargetHash());
                statement.setObject(2, context.jobRequestUuid());
                statement.setString(3, safe.stepCode());
                statement.setString(4, safe.partitionCode());
                statement.setString(5, safe.batchKeyHash());
                statement.setLong(6, safe.batchNumber());
                statement.setString(7, context.releaseHash());
                statement.setString(8, context.planHash());
                statement.setString(9, safe.payloadHash());
                statement.setLong(10, safe.rowCount());
                statement.setLong(11, safe.byteCount());
                return verification(statement);
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        @Override
        public Optional<RecordedEvidence> verifyPublish(PublishEvidence evidence) {
            PublishEvidence safe = valid(evidence);
            ensureTransactionConnection(connection);
            try (PreparedStatement statement = connection.prepareStatement(VERIFY_PUBLISH)) {
                statement.setString(1, context.canonicalTargetHash());
                statement.setObject(2, context.jobRequestUuid());
                statement.setString(3, safe.stepCode());
                statement.setString(4, safe.publishKeyHash());
                statement.setString(5, context.releaseHash());
                statement.setString(6, context.planHash());
                statement.setString(7, safe.stageHash());
                statement.setLong(8, safe.stageRowCount());
                statement.setLong(9, safe.publishedRowCount());
                statement.setLong(10, safe.rejectedRowCount());
                nullableText(statement, 11, safe.lowerWatermark());
                nullableText(statement, 12, safe.upperWatermark());
                return verification(statement);
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        }

        private static Optional<RecordedEvidence> verification(PreparedStatement statement) throws SQLException {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean("eslesti")) return Optional.empty();
                return Optional.of(new RecordedEvidence(result.getObject("calistirma_uuid", UUID.class), result.getInt("deneme_no"),
                        result.getLong("cit_belirteci"), result.getObject("kanit_zamani", OffsetDateTime.class)));
            }
        }
    }

    private static void owner(PreparedStatement statement, int first, TargetLedgerContext context) throws SQLException {
        statement.setString(first, context.canonicalTargetHash());
        statement.setLong(first + 1, context.fenceToken());
        statement.setObject(first + 2, context.jobRequestUuid());
        statement.setObject(first + 3, context.runUuid());
        statement.setInt(first + 4, context.attemptNumber());
        statement.setString(first + 5, context.releaseHash());
        statement.setString(first + 6, context.planHash());
    }

    private static void nullableText(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static void ensureTransactionConnection(Connection connection) {
        try {
            if (connection == null || connection.isClosed() || connection.getAutoCommit() || connection.isReadOnly()) {
                throw new OracleLedgerUnavailableException(new IllegalStateException(
                        "PostgreSQL ledger requires an open, writable, auto-commit-disabled connection."));
            }
        }
        catch (SQLException exception) {
            throw new OracleLedgerUnavailableException(exception);
        }
    }

    private static TargetLedgerContext valid(TargetLedgerContext context) {
        if (context == null || !isHash(context.canonicalTargetHash()) || context.fenceToken() <= 0 || context.jobRequestUuid() == null
                || context.runUuid() == null || context.attemptNumber() <= 0 || !isHash(context.releaseHash()) || !isHash(context.planHash())) {
            throw new IllegalArgumentException("A complete canonical target ledger context is required.");
        }
        return context;
    }

    private static BatchEvidence valid(BatchEvidence evidence) {
        if (evidence == null || !isCode(evidence.stepCode()) || !isCode(evidence.partitionCode()) || !isHash(evidence.batchKeyHash())
                || evidence.batchNumber() < 0 || !isHash(evidence.payloadHash()) || evidence.rowCount() < 0 || evidence.byteCount() < 0) {
            throw new IllegalArgumentException("Batch evidence is not canonical.");
        }
        return evidence;
    }

    private static PublishEvidence valid(PublishEvidence evidence) {
        if (evidence == null || !isCode(evidence.stepCode()) || !isHash(evidence.publishKeyHash()) || !isHash(evidence.stageHash())
                || evidence.stageRowCount() < 0 || evidence.publishedRowCount() < 0 || evidence.rejectedRowCount() < 0
                || (evidence.lowerWatermark() == null) != (evidence.upperWatermark() == null)
                || tooLongWatermark(evidence.lowerWatermark()) || tooLongWatermark(evidence.upperWatermark())) {
            throw new IllegalArgumentException("Publish evidence is not canonical.");
        }
        return evidence;
    }

    private static boolean isHash(String value) { return value != null && SHA_256.matcher(value).matches(); }

    private static boolean isCode(String value) { return value != null && value.length() <= 128 && CODE.matcher(value).matches(); }

    private static boolean isTransactionGuard(String value) { return value != null && TRANSACTION_GUARD.matcher(value).matches(); }

    private static boolean tooLongWatermark(String value) { return value != null && value.getBytes(StandardCharsets.UTF_8).length > 1_000; }

    /** SQLSTATE AK0nn carries the same nn as the Oracle -200nn codes. */
    static OracleTargetLedgerException translate(SQLException exception) {
        String state = ledgerState(exception);
        if (state == null) return new OracleLedgerUnavailableException(exception);
        int code = 20000 + Integer.parseInt(state.substring(2));
        return switch (code) {
            case 20001, 20002, 20003, 20004, 20005, 20006, 20020 -> new OracleLedgerInputException(code, exception);
            case 20010 -> new OracleFenceRejectedException(OracleLedgerFailure.FENCE_NOT_FOUND, code, exception);
            case 20011 -> new OracleFenceRejectedException(OracleLedgerFailure.FENCE_OWNERSHIP_MISMATCH, code, exception);
            case 20012 -> new OracleFenceRejectedException(OracleLedgerFailure.STALE_FENCE_TOKEN, code, exception);
            case 20013 -> new OracleFenceRejectedException(OracleLedgerFailure.FENCE_OWNER_CONFLICT, code, exception);
            case 20014, 20017, 20018, 20021, 20023, 20024, 20026 -> new OracleLedgerTransactionException(code, exception);
            case 20015, 20016, 20019 -> new OracleLedgerConflictException(OracleLedgerFailure.BATCH_EVIDENCE_CONFLICT, code, exception);
            case 20022, 20025 -> new OracleLedgerConflictException(OracleLedgerFailure.PUBLISH_EVIDENCE_CONFLICT, code, exception);
            default -> new OracleLedgerUnavailableException(exception);
        };
    }

    private static String ledgerState(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            String state = current.getSQLState();
            if (state != null && state.matches("AK0[0-9]{2}")) return state;
            current = current.getNextException();
        }
        return null;
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.OracleTargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.RecordedEvidence;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

class JdbcOracleTargetLedgerAdapterTest {

    private static final String TARGET_HASH = "a".repeat(64);
    private static final String RELEASE_HASH = "b".repeat(64);
    private static final String PLAN_HASH = "c".repeat(64);
    private static final String BATCH_HASH = "d".repeat(64);
    private static final String PAYLOAD_HASH = "e".repeat(64);
    private static final String PUBLISH_HASH = "f".repeat(64);
    private static final String STAGE_HASH = "1".repeat(64);
    private static final String GUARD = "2".repeat(32);
    private static final UUID JOB_UUID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_UUID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime EVIDENCE_AT = OffsetDateTime.of(
            2026, 9, 11, 10, 0, 0, 0, ZoneOffset.UTC);

    private final JdbcOracleTargetLedgerAdapter adapter =
            new JdbcOracleTargetLedgerAdapter();

    @Test
    void invokesMutationsAndReconciliationThroughSeparatedConnections()
            throws Exception {
        FakeConnection writer = new FakeConnection();
        FakeConnection reader = new FakeConnection();
        Connection writerConnection = writer.proxy();
        FenceSession fenceSession = adapter.bindFence(writerConnection, context());
        BatchEvidence batch = batch();
        PublishEvidence publish = publish();

        fenceSession.acquireFence();
        writerConnection.commit();
        ReconciliationSession reconciliation = adapter.bindReconciliation(
                reader.proxy(), context());
        assertEquals(RUN_UUID, reconciliation.readFence().orElseThrow().runUuid());
        DataLedgerSession session = adapter.bindData(writerConnection, context());

        BatchPreparation batchPreparation = session.prepareBatch(batch);
        assertFalse(batchPreparation.alreadyRecorded());
        session.recordBatch(batchPreparation);
        RecordedEvidence batchRecorded = reconciliation.verifyBatch(batch).orElseThrow();
        writerConnection.commit();

        var publishPreparation = session.preparePublish(publish);
        assertFalse(publishPreparation.alreadyRecorded());
        session.recordPublish(publishPreparation);
        writerConnection.commit();
        RecordedEvidence publishRecorded = reconciliation.verifyPublish(publish).orElseThrow();

        assertEquals(List.of(
                "ACQUIRE_FENCE", "PREPARE_BATCH", "RECORD_BATCH",
                "PREPARE_PUBLISH", "RECORD_PUBLISH"), writer.procedures());
        assertEquals(List.of("READ_FENCE", "VERIFY_BATCH", "VERIFY_PUBLISH"),
                reader.procedures());
        assertEquals(7L, batchRecorded.fenceToken());
        assertEquals(EVIDENCE_AT, publishRecorded.evidenceAt());
        assertEquals(TARGET_HASH, writer.calls.getFirst().parameters.get(1));
        assertEquals(GUARD, writer.calls.get(2).parameters.get(1));
        assertSame(batch, batchPreparation.evidence());
    }

    @Test
    void preparationCannotBeRecordedOnAnotherBoundConnection() {
        DataLedgerSession first = adapter.bindData(new FakeConnection().proxy(), context());
        DataLedgerSession second = adapter.bindData(new FakeConnection().proxy(), context());
        BatchPreparation preparation = first.prepareBatch(batch());

        assertThrows(IllegalArgumentException.class, () -> second.recordBatch(preparation));
    }

    @Test
    void autoCommitConnectionIsRejectedFailClosed() {
        FakeConnection fake = new FakeConnection();
        fake.autoCommit = true;

        OracleLedgerUnavailableException error = assertThrows(
                OracleLedgerUnavailableException.class,
                () -> adapter.bindFence(fake.proxy(), context()));

        assertEquals(OracleLedgerFailure.LEDGER_UNAVAILABLE, error.failure());
    }

    @Test
    void oracleApplicationErrorsMapToFailClosedDomainFailures() {
        assertFailure(20012, OracleFenceRejectedException.class,
                OracleLedgerFailure.STALE_FENCE_TOKEN);
        assertFailure(20018, OracleLedgerTransactionException.class,
                OracleLedgerFailure.TRANSACTION_PROTOCOL_REJECTED);
        assertFailure(20026, OracleLedgerTransactionException.class,
                OracleLedgerFailure.TRANSACTION_PROTOCOL_REJECTED);
        assertFailure(20015, OracleLedgerConflictException.class,
                OracleLedgerFailure.BATCH_EVIDENCE_CONFLICT);
        assertFailure(20022, OracleLedgerConflictException.class,
                OracleLedgerFailure.PUBLISH_EVIDENCE_CONFLICT);
        assertFailure(17002, OracleLedgerUnavailableException.class,
                OracleLedgerFailure.LEDGER_UNAVAILABLE);
    }

    @Test
    void replayPreparationCannotBeRecordedAgain() {
        FakeConnection fake = new FakeConnection();
        fake.alreadyRecorded = true;
        DataLedgerSession session = adapter.bindData(fake.proxy(), context());

        BatchPreparation preparation = session.prepareBatch(batch());

        assertTrue(preparation.alreadyRecorded());
        assertThrows(IllegalArgumentException.class, () -> session.recordBatch(preparation));
    }

    @Test
    void ledgerContextCombinesPinnedRunWithCanonicalTargetFence() {
        TargetFenceToken target = new TargetFenceToken(
                RUN_UUID, "worker-1", 3, UUID.randomUUID(), 7, TARGET_HASH, 1);
        PinnedExecutionContext pinned = new PinnedExecutionContext(
                JOB_UUID, RUN_UUID, UUID.randomUUID(), 1,
                RELEASE_HASH, PLAN_HASH, null);

        TargetLedgerContext combined = TargetLedgerContext.from(target, pinned);

        assertEquals(TARGET_HASH, combined.canonicalTargetHash());
        assertEquals(7, combined.fenceToken());
        assertEquals(RELEASE_HASH, combined.releaseHash());
        assertEquals(1, target.targetIdentityVersion());
    }

    @Test
    void packageRejectsPreparationWhenFenceTransactionWasNotCommitted() throws Exception {
        FakeConnection fake = new FakeConnection();
        Connection connection = fake.proxy();
        DataLedgerSession preCommitData = adapter.bindData(connection, context());
        FenceSession fence = adapter.bindFence(connection, context());
        fence.acquireFence();

        OracleLedgerTransactionException error = assertThrows(
                OracleLedgerTransactionException.class,
                () -> preCommitData.prepareBatch(batch()));
        assertEquals(OracleLedgerFailure.TRANSACTION_PROTOCOL_REJECTED, error.failure());
        assertEquals(20026, error.oracleErrorCode());

        connection.commit();
        ReconciliationSession reconciliation = adapter.bindReconciliation(
                new FakeConnection().proxy(), context());
        assertTrue(reconciliation.verifyBatch(batch()).isPresent());
    }

    @Test
    void mutationSessionsExposeNoReadOrVerifyOperations() {
        List<String> fenceMethods = java.util.Arrays.stream(FenceSession.class.getMethods())
                .map(Method::getName)
                .toList();
        List<String> dataMethods = java.util.Arrays.stream(DataLedgerSession.class.getMethods())
                .map(Method::getName)
                .toList();

        assertEquals(List.of("acquireFence"), fenceMethods);
        assertFalse(dataMethods.stream().anyMatch(
                name -> name.startsWith("read") || name.startsWith("verify")));
    }

    @Test
    void chainedKnownOracleApplicationErrorTakesPriority() {
        FakeConnection fake = new FakeConnection();
        SQLException transport = new SQLException("transport", "08006", 17002);
        transport.setNextException(new SQLException("stale", "72000", 20012));
        fake.failure = transport;

        OracleFenceRejectedException error = assertThrows(
                OracleFenceRejectedException.class,
                () -> adapter.bindFence(fake.proxy(), context()).acquireFence());

        assertEquals(OracleLedgerFailure.STALE_FENCE_TOKEN, error.failure());
        assertEquals(20012, error.oracleErrorCode());
    }

    private void assertFailure(
            int oracleCode,
            Class<? extends OracleTargetLedgerException> type,
            OracleLedgerFailure failure) {
        FakeConnection fake = new FakeConnection();
        fake.failureCode = oracleCode;
        FenceSession session = adapter.bindFence(fake.proxy(), context());

        OracleTargetLedgerException error = assertThrows(type, session::acquireFence);

        assertEquals(failure, error.failure());
        if (oracleCode >= 20000) {
            assertEquals(oracleCode, error.oracleErrorCode());
        }
    }

    private TargetLedgerContext context() {
        return new TargetLedgerContext(
                TARGET_HASH, 7, JOB_UUID, RUN_UUID, 1, RELEASE_HASH, PLAN_HASH);
    }

    private BatchEvidence batch() {
        return new BatchEvidence(
                "COPY_SOURCE", "P0", BATCH_HASH, 0, PAYLOAD_HASH, 4, 128);
    }

    private PublishEvidence publish() {
        return new PublishEvidence(
                "PUBLISH", PUBLISH_HASH, STAGE_HASH, 4, 4, 0, "1", "4");
    }

    private static final class FakeConnection implements InvocationHandler {

        private final List<FakeCall> calls = new ArrayList<>();
        private boolean autoCommit;
        private boolean alreadyRecorded;
        private boolean localTransactionActive;
        private int failureCode;
        private SQLException failure;

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class}, this);
        }

        List<String> procedures() {
            return calls.stream().map(call -> call.procedure).toList();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isClosed", "isReadOnly" -> false;
                case "getAutoCommit" -> autoCommit;
                case "prepareCall" -> statement((String) arguments[0]);
                case "commit", "rollback" -> {
                    localTransactionActive = false;
                    yield null;
                }
                case "close" -> null;
                case "unwrap" -> proxy;
                case "isWrapperFor" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private CallableStatement statement(String sql) {
            FakeCall call = new FakeCall(procedure(sql));
            calls.add(call);
            return (CallableStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {CallableStatement.class},
                    (proxy, method, arguments) -> statementCall(call, method, arguments));
        }

        private Object statementCall(FakeCall call, Method method, Object[] arguments)
                throws SQLException {
            String name = method.getName();
            if (name.startsWith("set")) {
                call.parameters.put((Integer) arguments[0], arguments[1]);
                return null;
            }
            if ("registerOutParameter".equals(name) || "close".equals(name)) {
                return null;
            }
            if ("execute".equals(name)) {
                if (failure != null) {
                    throw failure;
                }
                if (failureCode != 0) {
                    throw new SQLException("safe-test-error", "72000", failureCode);
                }
                if (localTransactionActive && ("ACQUIRE_FENCE".equals(call.procedure)
                        || "PREPARE_BATCH".equals(call.procedure)
                        || "PREPARE_PUBLISH".equals(call.procedure))) {
                    throw new SQLException("dirty transaction", "72000", 20026);
                }
                outputs(call);
                if ("ACQUIRE_FENCE".equals(call.procedure)
                        || "PREPARE_BATCH".equals(call.procedure)
                        || "PREPARE_PUBLISH".equals(call.procedure)) {
                    localTransactionActive = true;
                }
                return false;
            }
            if (name.startsWith("get")) {
                Object value = call.outputs.get((Integer) arguments[0]);
                return switch (name) {
                    case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                    case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                    case "getString" -> value == null ? null : value.toString();
                    case "getObject" -> value;
                    default -> defaultValue(method.getReturnType());
                };
            }
            return defaultValue(method.getReturnType());
        }

        private void outputs(FakeCall call) {
            switch (call.procedure) {
                case "READ_FENCE" -> {
                    call.outputs.put(2, 1);
                    call.outputs.put(3, 7L);
                    call.outputs.put(4, JOB_UUID.toString());
                    call.outputs.put(5, RUN_UUID.toString());
                    call.outputs.put(6, 1);
                    call.outputs.put(7, RELEASE_HASH);
                    call.outputs.put(8, PLAN_HASH);
                    call.outputs.put(9, EVIDENCE_AT);
                }
                case "PREPARE_BATCH" -> {
                    call.outputs.put(15, alreadyRecorded ? null : GUARD);
                    call.outputs.put(16, alreadyRecorded ? 1 : 0);
                }
                case "VERIFY_BATCH" -> verification(call, 12);
                case "PREPARE_PUBLISH" -> {
                    call.outputs.put(16, alreadyRecorded ? null : GUARD);
                    call.outputs.put(17, alreadyRecorded ? 1 : 0);
                }
                case "VERIFY_PUBLISH" -> verification(call, 13);
                default -> {
                }
            }
        }

        private void verification(FakeCall call, int first) {
            call.outputs.put(first, 1);
            call.outputs.put(first + 1, RUN_UUID.toString());
            call.outputs.put(first + 2, 1);
            call.outputs.put(first + 3, 7L);
            call.outputs.put(first + 4, EVIDENCE_AT);
        }

        private String procedure(String sql) {
            int start = sql.indexOf('.') + 1;
            int end = sql.indexOf('(', start);
            return sql.substring(start, end);
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            return null;
        }
    }

    private static final class FakeCall {

        private final String procedure;
        private final Map<Integer, Object> parameters = new HashMap<>();
        private final Map<Integer, Object> outputs = new HashMap<>();

        private FakeCall(String procedure) {
            this.procedure = procedure;
        }
    }
}

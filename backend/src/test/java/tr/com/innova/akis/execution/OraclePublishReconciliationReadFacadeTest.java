package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.OraclePublishReconciliationReadFacade.ReconciliationSessionHandle;
import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Failure;
import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Outcome;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.VerifiedDatabaseIdentity;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.TargetLedgerPort.RecordedEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.BarrierEvidence;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.PinnedReconciliation;

class OraclePublishReconciliationReadFacadeTest {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_HASH = "c".repeat(64);
    private static final String PAYLOAD_HASH = "d".repeat(64);
    private static final String OWNER = "INNOVA_ODI";
    private static final String TABLE = "STG_HAKEDIS_TIPI";
    private static final String TARGET_HASH = new OracleTargetIdentityV1().canonicalize(
            new VerifiedDatabaseIdentity("AKISDB", "AKISPDB"),
            OWNER, "TABLE", TABLE).canonicalTargetHash();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void derivesExactReadEvidenceAndUsesOriginalPublishTokenAfterHigherBarrier() {
        Fixture fixture = fixture();
        fixture.ledger.recorded = Optional.of(new RecordedEvidence(
                fixture.intent.runUuid(), fixture.intent.attemptNumber(),
                fixture.intent.targetGeneration(), OffsetDateTime.now(ZoneOffset.UTC)));

        var result = fixture.facade.reconcile(fixture.intent.runUuid());

        assertEquals(Outcome.PUBLISHED, result.outcome());
        assertNull(result.failure());
        assertEquals(fixture.intent.targetGeneration() + 1,
                fixture.ledger.context.fenceToken());
        assertEquals(fixture.intent.jobRequestUuid(), fixture.ledger.context.jobRequestUuid());
        assertEquals(PilotPublishKeyV1.PILOT_STEP_CODE,
                fixture.ledger.evidence.stepCode());
        assertEquals(fixture.intent.publishKeyHash(),
                fixture.ledger.evidence.publishKeyHash());
        assertEquals(PAYLOAD_HASH, fixture.ledger.evidence.stageHash());
        assertEquals(fixture.intent.rowCount(), fixture.ledger.evidence.stageRowCount());
        assertEquals(fixture.intent.rowCount(), fixture.ledger.evidence.publishedRowCount());
        assertEquals(0, fixture.ledger.evidence.rejectedRowCount());
        assertEquals(List.of("READ_FENCE", "VERIFY_PUBLISH"), fixture.ledger.calls);
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), fixture.session.events);
        assertEquals(fixture.plan.target(), fixture.openedBinding);
    }

    @Test
    void returnsNotPublishedOnlyAfterRollbackAndCloseAreConfirmed() {
        Fixture fixture = fixture();
        fixture.ledger.recorded = Optional.empty();

        var result = fixture.facade.reconcile(fixture.intent.runUuid());

        assertEquals(Outcome.NOT_PUBLISHED, result.outcome());
        assertNull(result.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), fixture.session.events);
    }

    @Test
    void treatsRecordedOwnerMismatchAndLedgerEvidenceConflictAsConflict() {
        Fixture mismatched = fixture();
        mismatched.ledger.recorded = Optional.of(new RecordedEvidence(
                UUID.randomUUID(), mismatched.intent.attemptNumber(),
                mismatched.intent.targetGeneration(), OffsetDateTime.now(ZoneOffset.UTC)));

        var mismatch = mismatched.facade.reconcile(mismatched.intent.runUuid());

        assertEquals(Outcome.CONFLICT, mismatch.outcome());
        assertEquals(Failure.LEDGER_EVIDENCE_CONFLICT, mismatch.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), mismatched.session.events);

        Fixture conflict = fixture();
        conflict.ledger.failure = new OracleLedgerConflictException(
                OracleLedgerFailure.PUBLISH_EVIDENCE_CONFLICT,
                20022,
                new SQLException("jdbc:oracle:user/secret@private-host"));

        var ledgerConflict = conflict.facade.reconcile(conflict.intent.runUuid());

        assertEquals(Outcome.CONFLICT, ledgerConflict.outcome());
        assertEquals(Failure.LEDGER_EVIDENCE_CONFLICT, ledgerConflict.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), conflict.session.events);
    }

    @Test
    void transactionOrCloseUncertaintyOverridesObservedNotPublishedResult() {
        Fixture rollbackFailure = fixture();
        rollbackFailure.ledger.recorded = Optional.empty();
        rollbackFailure.session.rollbackFailure = true;

        var rollbackUnknown = rollbackFailure.facade.reconcile(
                rollbackFailure.intent.runUuid());

        assertEquals(Outcome.OUTCOME_UNKNOWN, rollbackUnknown.outcome());
        assertEquals(Failure.ROLLBACK_NOT_CONFIRMED, rollbackUnknown.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"),
                rollbackFailure.session.events);

        Fixture closeFailure = fixture();
        closeFailure.ledger.recorded = Optional.empty();
        closeFailure.session.closeFailure = true;

        var closeUnknown = closeFailure.facade.reconcile(closeFailure.intent.runUuid());

        assertEquals(Outcome.OUTCOME_UNKNOWN, closeUnknown.outcome());
        assertEquals(Failure.SESSION_CLOSE_UNCONFIRMED, closeUnknown.failure());
    }

    @Test
    void masksUnexpectedOracleFailureAndStillResolvesReadTransaction() {
        Fixture fixture = fixture();
        fixture.ledger.failure = new IllegalStateException(
                "jdbc:oracle:thin:user/secret@private-host");

        var result = fixture.facade.reconcile(fixture.intent.runUuid());

        assertEquals(Outcome.OUTCOME_UNKNOWN, result.outcome());
        assertEquals(Failure.ORACLE_READ_UNCONFIRMED, result.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), fixture.session.events);
    }

    @Test
    void rejectsWrongPhysicalTargetIdentityWithoutReportingNotPublished() {
        Fixture fixture = fixture();
        fixture.session.connection = oracle("OTHERDB", "AKISPDB", 1);

        var result = fixture.facade.reconcile(fixture.intent.runUuid());

        assertEquals(Outcome.CONFLICT, result.outcome());
        assertEquals(Failure.TARGET_IDENTITY_MISMATCH, result.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), fixture.session.events);
    }

    @Test
    void rejectsMissingOrNonConsecutivePinnedBarrierBeforeOpeningOracle() {
        Fixture missing = fixture();
        missing.store.value = Optional.empty();
        var missingResult = missing.facade.reconcile(missing.intent.runUuid());
        assertEquals(Outcome.CONFLICT, missingResult.outcome());
        assertEquals(Failure.PINNED_EVIDENCE_NOT_FOUND, missingResult.failure());
        assertEquals(List.of(), missing.session.events);

        Fixture sameGeneration = fixture();
        sameGeneration.store.value = Optional.of(new PinnedReconciliation(
                sameGeneration.plan,
                execution(sameGeneration.intent),
                sameGeneration.intent,
                barrier(sameGeneration.intent, sameGeneration.intent.targetGeneration())));
        var invalidResult = sameGeneration.facade.reconcile(sameGeneration.intent.runUuid());
        assertEquals(Outcome.CONFLICT, invalidResult.outcome());
        assertEquals(Failure.PINNED_EVIDENCE_CONFLICT, invalidResult.failure());
        assertEquals(List.of(), sameGeneration.session.events);

        Fixture staleBarrier = fixture();
        staleBarrier.store.value = Optional.of(new PinnedReconciliation(
                staleBarrier.plan,
                execution(staleBarrier.intent),
                staleBarrier.intent,
                barrier(staleBarrier.intent,
                        staleBarrier.intent.targetGeneration() + 2)));
        var staleResult = staleBarrier.facade.reconcile(staleBarrier.intent.runUuid());
        assertEquals(Outcome.CONFLICT, staleResult.outcome());
        assertEquals(Failure.PINNED_EVIDENCE_CONFLICT, staleResult.failure());
        assertEquals(List.of(), staleBarrier.session.events);
    }

    @Test
    void refusesNotPublishedWhenCommittedNextFenceIsMissingOrMismatched() {
        Fixture missingFence = fixture();
        missingFence.ledger.fence = Optional.empty();

        var missingResult = missingFence.facade.reconcile(missingFence.intent.runUuid());

        assertEquals(Outcome.CONFLICT, missingResult.outcome());
        assertEquals(Failure.FENCE_BARRIER_CONFLICT, missingResult.failure());
        assertEquals(List.of("READ_FENCE"), missingFence.ledger.calls);
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), missingFence.session.events);

        Fixture wrongOwner = fixture();
        wrongOwner.ledger.fence = Optional.of(new FenceEvidence(
                wrongOwner.intent.targetGeneration() + 1,
                wrongOwner.intent.jobRequestUuid(),
                UUID.randomUUID(),
                wrongOwner.intent.attemptNumber(),
                wrongOwner.intent.releaseHash(),
                wrongOwner.intent.planHash(),
                OffsetDateTime.now(ZoneOffset.UTC)));

        var mismatch = wrongOwner.facade.reconcile(wrongOwner.intent.runUuid());

        assertEquals(Outcome.CONFLICT, mismatch.outcome());
        assertEquals(Failure.FENCE_BARRIER_CONFLICT, mismatch.failure());
        assertEquals(List.of("READ_FENCE"), wrongOwner.ledger.calls);
    }

    @Test
    void classifiesControlPlaneAndConnectionFailureWithoutLeakingExceptions() {
        Fixture controlPlane = fixture();
        controlPlane.store.failure = new IllegalStateException("secret control URL");
        var unavailable = controlPlane.facade.reconcile(controlPlane.intent.runUuid());
        assertEquals(Outcome.OUTCOME_UNKNOWN, unavailable.outcome());
        assertEquals(Failure.CONTROL_PLANE_UNAVAILABLE, unavailable.failure());

        Fixture connection = fixture();
        connection.openFailure = new IllegalStateException("secret JDBC URL");
        var connectionUnavailable = connection.facade.reconcile(connection.intent.runUuid());
        assertEquals(Outcome.OUTCOME_UNKNOWN, connectionUnavailable.outcome());
        assertEquals(Failure.CONNECTION_UNAVAILABLE, connectionUnavailable.failure());
    }

    @Test
    void rejectsWrongSessionPurposeBeforeConnectionOrLedgerAccess() {
        Fixture targetData = fixture();
        targetData.session.purpose = RuntimeOracleConnectionProvider.SessionPurpose.TARGET_DATA;

        var targetResult = targetData.facade.reconcile(targetData.intent.runUuid());

        assertEquals(Outcome.CONFLICT, targetResult.outcome());
        assertEquals(Failure.INVALID_SESSION_PURPOSE, targetResult.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), targetData.session.events);
        assertEquals(0, targetData.session.connectionAccesses);
        assertEquals(List.of(), targetData.ledger.calls);

        Fixture sourceRead = fixture();
        sourceRead.session.purpose = RuntimeOracleConnectionProvider.SessionPurpose.SOURCE_READ;

        var sourceResult = sourceRead.facade.reconcile(sourceRead.intent.runUuid());

        assertEquals(Outcome.CONFLICT, sourceResult.outcome());
        assertEquals(Failure.INVALID_SESSION_PURPOSE, sourceResult.failure());
        assertEquals(List.of("OPEN", "CLOSE"), sourceRead.session.events);
        assertEquals(0, sourceRead.session.connectionAccesses);

        Fixture targetIdentityRead = fixture();
        targetIdentityRead.session.purpose =
                RuntimeOracleConnectionProvider.SessionPurpose.TARGET_IDENTITY_READ;

        var identityResult = targetIdentityRead.facade.reconcile(
                targetIdentityRead.intent.runUuid());

        assertEquals(Outcome.CONFLICT, identityResult.outcome());
        assertEquals(Failure.INVALID_SESSION_PURPOSE, identityResult.failure());
        assertEquals(List.of("OPEN", "CLOSE"), targetIdentityRead.session.events);
        assertEquals(0, targetIdentityRead.session.connectionAccesses);
        assertEquals(List.of(), targetIdentityRead.ledger.calls);

        Fixture uncertainTarget = fixture();
        uncertainTarget.session.purpose =
                RuntimeOracleConnectionProvider.SessionPurpose.TARGET_FENCE;
        uncertainTarget.session.rollbackFailure = true;

        var uncertain = uncertainTarget.facade.reconcile(uncertainTarget.intent.runUuid());

        assertEquals(Outcome.OUTCOME_UNKNOWN, uncertain.outcome());
        assertEquals(Failure.ROLLBACK_NOT_CONFIRMED, uncertain.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"),
                uncertainTarget.session.events);
    }

    @Test
    void rejectsPinnedExecutionThatDoesNotExactlyMatchOriginalPublish() {
        Fixture fixture = fixture();
        PinnedExecutionContext wrongExecution = new PinnedExecutionContext(
                UUID.randomUUID(), fixture.intent.runUuid(), fixture.intent.publicationUuid(),
                fixture.intent.attemptNumber(), fixture.intent.releaseHash(),
                fixture.intent.planHash(), objectMapper.createObjectNode(),
                objectMapper.createObjectNode());
        fixture.store.value = Optional.of(new PinnedReconciliation(
                fixture.plan,
                wrongExecution,
                fixture.intent,
                barrier(fixture.intent, fixture.intent.targetGeneration() + 1)));

        var result = fixture.facade.reconcile(fixture.intent.runUuid());

        assertEquals(Outcome.CONFLICT, result.outcome());
        assertEquals(Failure.PINNED_EVIDENCE_CONFLICT, result.failure());
        assertEquals(List.of(), fixture.session.events);
    }

    @Test
    void neverOpensReadSessionUnlessTheBarrierCommitIsConfirmed() {
        Fixture safeFailure = fixture();
        safeFailure.fenceResult = new OracleTargetFencePort.SafeFailure(
                OracleTargetFencePort.FailureCode.ORACLE_OPERATION_REJECTED);
        var safe = safeFailure.facade.reconcile(safeFailure.intent.runUuid());
        assertEquals(Outcome.OUTCOME_UNKNOWN, safe.outcome());
        assertEquals(Failure.FENCE_BARRIER_NOT_COMMITTED, safe.failure());
        assertEquals(List.of(), safeFailure.session.events);

        Fixture unknown = fixture();
        unknown.fenceResult = new OracleTargetFencePort.OutcomeUnknown(
                OracleTargetFencePort.FailureCode.COMMIT_OUTCOME_UNKNOWN);
        var ambiguous = unknown.facade.reconcile(unknown.intent.runUuid());
        assertEquals(Outcome.OUTCOME_UNKNOWN, ambiguous.outcome());
        assertEquals(Failure.FENCE_BARRIER_OUTCOME_UNKNOWN, ambiguous.failure());
        assertEquals(List.of(), unknown.session.events);

        Fixture fenced = fixture();
        fenced.fenceResult = new OracleTargetFencePort.FencedOut(
                OracleTargetFencePort.FailureCode.STALE_FENCE);
        var rejected = fenced.facade.reconcile(fenced.intent.runUuid());
        assertEquals(Outcome.CONFLICT, rejected.outcome());
        assertEquals(Failure.FENCE_BARRIER_CONFLICT, rejected.failure());
        assertEquals(List.of(), fenced.session.events);
    }

    @Test
    void rejectsACommitReceiptThatDoesNotMatchThePinnedBarrier() {
        Fixture fixture = fixture();
        fixture.fenceResult = new OracleTargetFencePort.CommitConfirmed(
                new OracleTargetFencePort.FenceReceipt(
                        fixture.intent.targetResourceUuid(),
                        fixture.intent.targetGeneration(),
                        fixture.intent.canonicalTargetHash()));

        var result = fixture.facade.reconcile(fixture.intent.runUuid());

        assertEquals(Outcome.CONFLICT, result.outcome());
        assertEquals(Failure.PINNED_EVIDENCE_CONFLICT, result.failure());
        assertEquals(List.of(), fixture.session.events);
    }

    private Fixture fixture() {
        PilotRuntimePlan plan = plan();
        PilotPublishIntent intent = intent(plan);
        PinnedReconciliation pinned = new PinnedReconciliation(
                plan,
                execution(intent),
                intent,
                barrier(intent, intent.targetGeneration() + 1));
        return new Fixture(plan, intent, pinned);
    }

    private PilotRuntimePlan plan() {
        return new PilotRuntimePlan(
                1, RUNTIME_HASH, RELEASE_HASH, PLAN_HASH,
                UUID.randomUUID(), UUID.randomUUID(), 1000,
                binding(DatasetRole.SOURCE, "TTBP", "HAKEDIS_TIPI"),
                binding(DatasetRole.TARGET, OWNER, TABLE),
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
    }

    private PilotPublishIntent intent(PilotRuntimePlan plan) {
        UUID jobRequestUuid = UUID.randomUUID();
        String publishKey = new PilotPublishKeyV1().create(
                jobRequestUuid, RUNTIME_HASH, TARGET_HASH,
                PilotPublishKeyV1.PILOT_STEP_CODE);
        return new PilotPublishIntent(
                UUID.randomUUID(), UUID.randomUUID(), jobRequestUuid, UUID.randomUUID(),
                1, 4, "publisher-1", UUID.randomUUID(), 7,
                TARGET_HASH, OracleTargetIdentityV1.TARGET_IDENTITY_VERSION,
                plan.releaseHash(), plan.scenarioPlanHash(), plan.runtimePlanHash(),
                publishKey, PAYLOAD_HASH, 33, 2048);
    }

    private static BarrierEvidence barrier(
            PilotPublishIntent intent,
            long barrierTargetGeneration) {
        return new BarrierEvidence(
                intent.runUuid(), intent.runGeneration() + 1, "reconciler-1",
                intent.targetResourceUuid(), intent.targetGeneration(),
                barrierTargetGeneration, intent.canonicalTargetHash(),
                intent.targetIdentityVersion());
    }

    private static PinnedExecutionContext execution(PilotPublishIntent intent) {
        ObjectMapper mapper = new ObjectMapper();
        return new PinnedExecutionContext(
                intent.jobRequestUuid(), intent.runUuid(), intent.publicationUuid(),
                intent.attemptNumber(), intent.releaseHash(), intent.planHash(),
                mapper.createObjectNode(), mapper.createObjectNode());
    }

    private DatasetBinding binding(
            DatasetRole role, String owner, String objectName) {
        return new DatasetBinding(
                role.name().toLowerCase(), role, DatabaseType.ORACLE, DataObjectType.TABLE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "e".repeat(64),
                owner + "." + objectName, owner, objectName);
    }

    private static Connection oracle(
            String databaseUniqueName, String containerName, int objectCount) {
        return proxy(Connection.class, (proxy, method, arguments) -> {
            if (method.getName().equals("prepareStatement")) {
                String sql = (String) arguments[0];
                return statement(sql.contains("ALL_OBJECTS")
                        ? List.of(Map.of("OBJECT_COUNT", objectCount))
                        : List.of(Map.of(
                                "DB_UNIQUE_NAME", databaseUniqueName,
                                "CON_NAME", containerName)));
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static PreparedStatement statement(List<Map<String, Object>> rows) {
        return proxy(PreparedStatement.class, (proxy, method, arguments) -> {
            if (method.getName().equals("executeQuery")) {
                return resultSet(rows);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        Map<String, Object> state = new HashMap<>();
        state.put("index", -1);
        state.put("wasNull", false);
        return proxy(ResultSet.class, (proxy, method, arguments) -> {
            int index = (int) state.get("index");
            if (method.getName().equals("next")) {
                int next = index + 1;
                state.put("index", next);
                return next < rows.size();
            }
            if (method.getName().equals("getString")) {
                Object value = rows.get(index).get((String) arguments[0]);
                state.put("wasNull", value == null);
                return value == null ? null : value.toString();
            }
            if (method.getName().equals("getInt")) {
                Object value = rows.get(index).get((String) arguments[0]);
                state.put("wasNull", value == null);
                return value == null ? 0 : ((Number) value).intValue();
            }
            if (method.getName().equals("wasNull")) {
                return state.get("wasNull");
            }
            return defaultValue(method.getReturnType());
        });
    }

    private final class Fixture {
        private final PilotRuntimePlan plan;
        private final PilotPublishIntent intent;
        private final FakeStore store;
        private final FakeLedger ledger = new FakeLedger();
        private final FakeSession session = new FakeSession();
        private final OraclePublishReconciliationReadFacade facade;
        private OracleTargetFencePort.OracleTargetFenceResult fenceResult;
        private DatasetBinding openedBinding;
        private RuntimeException openFailure;

        private Fixture(
                PilotRuntimePlan plan,
                PilotPublishIntent intent,
                PinnedReconciliation pinned) {
            this.plan = plan;
            this.intent = intent;
            this.store = new FakeStore(Optional.of(pinned));
            this.ledger.fence = Optional.of(new FenceEvidence(
                    intent.targetGeneration() + 1,
                    intent.jobRequestUuid(),
                    intent.runUuid(),
                    intent.attemptNumber(),
                    intent.releaseHash(),
                    intent.planHash(),
                    OffsetDateTime.now(ZoneOffset.UTC)));
            this.fenceResult = new OracleTargetFencePort.CommitConfirmed(
                    new OracleTargetFencePort.FenceReceipt(
                            intent.targetResourceUuid(),
                            intent.targetGeneration() + 1,
                            intent.canonicalTargetHash()));
            this.facade = new OraclePublishReconciliationReadFacade(
                    store,
                    ledger,
                    command -> fenceResult,
                    binding -> {
                        if (openFailure != null) {
                            throw openFailure;
                        }
                        openedBinding = binding;
                        session.events.add("OPEN");
                        return session;
                    },
                    new JdbcOracleTargetIdentityReader(),
                    new PilotPublishKeyV1());
        }
    }

    private static final class FakeStore implements PinnedPublishReconciliationPort {
        private Optional<PinnedReconciliation> value;
        private RuntimeException failure;

        private FakeStore(Optional<PinnedReconciliation> value) {
            this.value = value;
        }

        @Override
        public Optional<PinnedReconciliation> find(UUID runUuid) {
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    private static final class FakeSession implements ReconciliationSessionHandle {
        private final List<String> events = new ArrayList<>();
        private Connection connection = oracle("AKISDB", "AKISPDB", 1);
        private RuntimeOracleConnectionProvider.SessionPurpose purpose =
                RuntimeOracleConnectionProvider.SessionPurpose.TARGET_RECONCILIATION;
        private boolean rollbackFailure;
        private boolean closeFailure;
        private int connectionAccesses;

        @Override
        public RuntimeOracleConnectionProvider.SessionPurpose purpose() {
            return purpose;
        }

        @Override
        public Connection connection() {
            connectionAccesses++;
            return connection;
        }

        @Override
        public void rollbackConfirmed() {
            events.add("ROLLBACK");
            if (rollbackFailure) {
                throw new IllegalStateException("secret rollback detail");
            }
        }

        @Override
        public void close() {
            events.add("CLOSE");
            if (closeFailure) {
                throw new IllegalStateException("secret close detail");
            }
        }
    }

    private static final class FakeLedger implements TargetLedgerPort {
        private Optional<FenceEvidence> fence = Optional.empty();
        private Optional<RecordedEvidence> recorded = Optional.empty();
        private RuntimeException failure;
        private TargetLedgerContext context;
        private PublishEvidence evidence;
        private final List<String> calls = new ArrayList<>();

        @Override
        public FenceSession bindFence(Connection connection, TargetLedgerContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DataLedgerSession bindData(Connection connection, TargetLedgerContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReconciliationSession bindReconciliation(
                Connection connection, TargetLedgerContext context) {
            this.context = context;
            return new ReconciliationSession() {
                @Override
                public Optional<FenceEvidence> readFence() {
                    calls.add("READ_FENCE");
                    if (failure != null) {
                        throw failure;
                    }
                    return fence;
                }

                @Override
                public Optional<RecordedEvidence> verifyBatch(BatchEvidence evidence) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<RecordedEvidence> verifyPublish(PublishEvidence evidence) {
                    calls.add("VERIFY_PUBLISH");
                    FakeLedger.this.evidence = evidence;
                    if (failure != null) {
                        throw failure;
                    }
                    return recorded;
                }
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
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
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.OracleAtomicPublishPort.AlreadyRecorded;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.CommitConfirmed;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.EvidenceConflict;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.FailureCode;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.FencedOut;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.NotAttempted;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OracleAtomicPublishCommand;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OracleAtomicPublishResult;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.OutcomeUnknown;
import tr.com.innova.akis.execution.OracleAtomicPublishPort.SafeFailure;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;

class OracleAtomicPublishFacadeTest {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_HASH = "c".repeat(64);
    private static final String TARGET_HASH = "d".repeat(64);
    private static final String PAYLOAD_HASH = "e".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enforcesPrepareLockPreflightDmlRecordCommitOrder() {
        Fixture fixture = fixture(false);

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        assertInstanceOf(CommitConfirmed.class, result);
        assertEquals(List.of(
                "OPEN", "BIND", "PREPARE", "LOCK", "PREFLIGHT",
                "DML_VERIFY", "RECORD", "COMMIT", "CLOSE"), fixture.events);
    }

    @Test
    void exactRecordedEvidenceSkipsEveryBusinessWrite() {
        Fixture fixture = fixture(true);

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        assertInstanceOf(AlreadyRecorded.class, result);
        assertEquals(List.of("OPEN", "BIND", "PREPARE", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    @Test
    void preCommitFailureIsSafeOnlyWhenRollbackIsConfirmed() {
        Fixture fixture = fixture(false);
        fixture.writerFailure = true;

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        SafeFailure failure = assertInstanceOf(SafeFailure.class, result);
        assertEquals(FailureCode.ORACLE_OPERATION_REJECTED, failure.failure());
        assertEquals(List.of(
                "OPEN", "BIND", "PREPARE", "LOCK", "PREFLIGHT",
                "ROLLBACK", "CLOSE"), fixture.events);
    }

    @Test
    void failedRollbackMakesPreCommitOutcomeUnknown() {
        Fixture fixture = fixture(false);
        fixture.writerFailure = true;
        fixture.connection.rollbackFailure = true;

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        OutcomeUnknown failure = assertInstanceOf(OutcomeUnknown.class, result);
        assertEquals(FailureCode.ROLLBACK_NOT_CONFIRMED, failure.failure());
        assertEquals(List.of(
                "OPEN", "BIND", "PREPARE", "LOCK", "PREFLIGHT",
                "ROLLBACK", "ROLLBACK", "CLOSE"), fixture.events);
    }

    @Test
    void commitErrorRemainsUnknownEvenWhenLaterRollbackResponds() {
        Fixture fixture = fixture(false);
        fixture.connection.commitFailure = true;

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        OutcomeUnknown failure = assertInstanceOf(OutcomeUnknown.class, result);
        assertEquals(FailureCode.COMMIT_OUTCOME_UNKNOWN, failure.failure());
        assertEquals(List.of(
                "OPEN", "BIND", "PREPARE", "LOCK", "PREFLIGHT",
                "DML_VERIFY", "RECORD", "COMMIT", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    @Test
    void prepareResponseLossIsUnknownEvenWhenRollbackResponds() {
        Fixture fixture = fixture(false);
        fixture.ledger.prepareFailure = new OracleLedgerUnavailableException(
                new SQLException("sensitive transport diagnostic"));

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        OutcomeUnknown failure = assertInstanceOf(OutcomeUnknown.class, result);
        assertEquals(FailureCode.PREPARE_OUTCOME_UNKNOWN, failure.failure());
        assertEquals(List.of("OPEN", "BIND", "PREPARE", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    @Test
    void deterministicPrepareConflictDoesNotBecomeTransportAmbiguity() {
        Fixture fixture = fixture(false);
        fixture.ledger.prepareFailure = new OracleLedgerConflictException(
                OracleLedgerFailure.PUBLISH_EVIDENCE_CONFLICT,
                20_064,
                new SQLException("sensitive conflict detail"));

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        EvidenceConflict failure = assertInstanceOf(EvidenceConflict.class, result);
        assertEquals(FailureCode.LEDGER_EVIDENCE_CONFLICT, failure.failure());
        assertEquals(List.of("OPEN", "BIND", "PREPARE", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    @Test
    void deterministicPrepareFenceRejectionIsFencedOut() {
        Fixture fixture = fixture(false);
        fixture.ledger.prepareFailure = new OracleFenceRejectedException(
                OracleLedgerFailure.STALE_FENCE_TOKEN,
                20_002,
                new SQLException("sensitive stale fence detail"));

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        FencedOut failure = assertInstanceOf(FencedOut.class, result);
        assertEquals(FailureCode.STALE_FENCE, failure.failure());
        assertEquals(List.of("OPEN", "BIND", "PREPARE", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    @Test
    void recordFailureRollsBackTheVerifiedBusinessWrite() {
        Fixture fixture = fixture(false);
        fixture.ledger.recordFailure = new OracleLedgerUnavailableException(
                new SQLException("sensitive record diagnostic"));

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        SafeFailure failure = assertInstanceOf(SafeFailure.class, result);
        assertEquals(FailureCode.ORACLE_OPERATION_REJECTED, failure.failure());
        assertEquals(List.of(
                "OPEN", "BIND", "PREPARE", "LOCK", "PREFLIGHT",
                "DML_VERIFY", "RECORD", "ROLLBACK", "CLOSE"), fixture.events);
    }

    @Test
    void closeErrorAfterConfirmedCommitDoesNotChangeDurabilityResult() {
        Fixture fixture = fixture(false);
        fixture.connection.closeFailure = true;

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        assertInstanceOf(CommitConfirmed.class, result);
        assertEquals("COMMIT", fixture.events.get(fixture.events.size() - 2));
        assertEquals("CLOSE", fixture.events.getLast());
    }

    @Test
    void intentMismatchFailsBeforeOpeningOracle() {
        Fixture fixture = fixture(false);
        fixture.intent = intent(fixture.command, "f".repeat(64));

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        EvidenceConflict failure = assertInstanceOf(EvidenceConflict.class, result);
        assertEquals(FailureCode.LEDGER_EVIDENCE_CONFLICT, failure.failure());
        assertEquals(List.of(), fixture.events);
    }

    @Test
    void controlPlaneReadFailureIsNotReportedAsConfirmedRollback() {
        Fixture fixture = fixture(false);
        fixture.intentLoadFailure = true;

        OracleAtomicPublishResult result = fixture.facade.publish(fixture.command);

        NotAttempted failure = assertInstanceOf(NotAttempted.class, result);
        assertEquals(FailureCode.CONTROL_PLANE_UNAVAILABLE, failure.failure());
        assertEquals(List.of(), fixture.events);
    }

    private Fixture fixture(boolean alreadyRecorded) {
        List<String> events = new ArrayList<>();
        PilotRuntimePlan plan = plan();
        PinnedExecutionContext execution = execution();
        TargetFenceToken fence = fence(execution.runUuid());
        OraclePilotBatch batch = new OraclePilotBatch(
                RUNTIME_HASH, List.of(), List.of(), PAYLOAD_HASH, 0);
        PinnedSnapshots snapshots = new PinnedSnapshots(
                UUID.randomUUID(), execution.publicationUuid(),
                new PinnedSnapshot(
                        plan.source().schemaSnapshotUuid(),
                        plan.source().schemaSnapshotFingerprint(), null),
                new PinnedSnapshot(
                        plan.target().schemaSnapshotUuid(),
                        plan.target().schemaSnapshotFingerprint(), null));
        OracleAtomicPublishCommand command = new OracleAtomicPublishCommand(
                plan, execution, fence, snapshots, batch);
        Fixture fixture = new Fixture(events, command);
        fixture.intent = intent(command, PAYLOAD_HASH);

        RuntimeOracleConnectionProvider provider = new RuntimeOracleConnectionProvider(
                binding -> Optional.of(profile(binding.connectionVersionUuid())),
                objectMapper,
                ignored -> "{\"username\":\"INNOVA_ODI\",\"password\":\"hidden\"}",
                (url, properties) -> {
                    events.add("OPEN");
                    return fixture.connection.proxy();
                },
                Runnable::run);
        FakeLedger ledger = new FakeLedger(events, alreadyRecorded);
        fixture.ledger = ledger;
        OracleAtomicRefreshWriterPort writer = (connection, runtimePlan, token,
                sourceBatch, lockedVerifier) -> {
            events.add("LOCK");
            lockedVerifier.verify(connection);
            if (fixture.writerFailure) {
                throw new IllegalStateException("sensitive detail must not escape");
            }
            events.add("DML_VERIFY");
            return new OraclePilotWriteResult(
                    7, 0, 0, sourceBatch.byteCount(), sourceBatch.payloadHash());
        };
        fixture.facade = new OracleAtomicPublishFacade(
                runUuid -> {
                    if (fixture.intentLoadFailure) {
                        throw new IllegalStateException("sensitive PostgreSQL detail");
                    }
                    return Optional.ofNullable(fixture.intent);
                },
                ledger,
                writer,
                provider,
                (runtimePlan, connection, pinned) -> events.add("PREFLIGHT"),
                new PilotPublishKeyV1());
        return fixture;
    }

    private PilotPublishIntent intent(
            OracleAtomicPublishCommand command, String payloadHash) {
        String publishKey = new PilotPublishKeyV1().create(
                command.execution().jobRequestUuid(),
                command.plan().runtimePlanHash(),
                command.fence().canonicalTargetHash(),
                PilotPublishKeyV1.PILOT_STEP_CODE);
        return new PilotPublishIntent(
                command.snapshots().projectUuid(),
                command.execution().publicationUuid(),
                command.execution().jobRequestUuid(),
                command.execution().runUuid(),
                command.execution().attemptNumber(),
                command.fence().runGeneration(),
                command.fence().workerReference(),
                command.fence().targetResourceUuid(),
                command.fence().targetGeneration(),
                command.fence().canonicalTargetHash(),
                command.fence().targetIdentityVersion(),
                command.execution().releaseHash(),
                command.execution().planHash(),
                command.plan().runtimePlanHash(),
                publishKey,
                payloadHash,
                command.batch().rows().size(),
                command.batch().byteCount());
    }

    private PilotRuntimePlan plan() {
        return new PilotRuntimePlan(
                1, RUNTIME_HASH, RELEASE_HASH, PLAN_HASH,
                UUID.randomUUID(), UUID.randomUUID(), 1000,
                binding(DatasetRole.SOURCE, "TTBP", "HAKEDIS_TIPI"),
                binding(DatasetRole.TARGET, "INNOVA_ODI", "STG_HAKEDIS_TIPI"),
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
    }

    private DatasetBinding binding(DatasetRole role, String owner, String objectName) {
        return new DatasetBinding(
                role.name().toLowerCase(), role, DatabaseType.ORACLE,
                DataObjectType.TABLE, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, role == DatasetRole.SOURCE
                        ? "1".repeat(64) : "2".repeat(64),
                owner + "." + objectName, owner, objectName);
    }

    private PinnedExecutionContext execution() {
        return new PinnedExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                RELEASE_HASH, PLAN_HASH,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
    }

    private TargetFenceToken fence(UUID runUuid) {
        return new TargetFenceToken(
                runUuid, "worker-1", 3, UUID.randomUUID(), 5,
                TARGET_HASH, OracleTargetIdentityV1.TARGET_IDENTITY_VERSION);
    }

    private ConnectionProfile profile(UUID connectionVersionUuid) {
        return new ConnectionProfile(
                UUID.randomUUID(), connectionVersionUuid,
                "oracle.jdbc.OracleDriver", "db.internal", "SERVICE_1", null,
                1521, "DISABLED", objectMapper.createObjectNode(),
                "ENV", "AKIS_TEST_ORACLE_SECRET");
    }

    private static final class Fixture {
        private final List<String> events;
        private final OracleAtomicPublishCommand command;
        private final FakeConnection connection;
        private PilotPublishIntent intent;
        private OracleAtomicPublishFacade facade;
        private FakeLedger ledger;
        private boolean writerFailure;
        private boolean intentLoadFailure;

        private Fixture(List<String> events, OracleAtomicPublishCommand command) {
            this.events = events;
            this.command = command;
            this.connection = new FakeConnection(events);
        }
    }

    private static final class FakeLedger implements TargetLedgerPort {
        private final List<String> events;
        private final boolean alreadyRecorded;
        private RuntimeException prepareFailure;
        private RuntimeException recordFailure;

        private FakeLedger(List<String> events, boolean alreadyRecorded) {
            this.events = events;
            this.alreadyRecorded = alreadyRecorded;
        }

        @Override
        public FenceSession bindFence(Connection connection, TargetLedgerContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DataLedgerSession bindData(Connection connection, TargetLedgerContext context) {
            events.add("BIND");
            return new DataLedgerSession() {
                @Override
                public BatchPreparation prepareBatch(BatchEvidence evidence) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void recordBatch(BatchPreparation preparation) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public PublishPreparation preparePublish(PublishEvidence evidence) {
                    events.add("PREPARE");
                    if (prepareFailure != null) {
                        throw prepareFailure;
                    }
                    return new PublishPreparation(
                            UUID.randomUUID(), evidence,
                            alreadyRecorded ? null : "a".repeat(32), alreadyRecorded);
                }

                @Override
                public void recordPublish(PublishPreparation preparation) {
                    events.add("RECORD");
                    if (recordFailure != null) {
                        throw recordFailure;
                    }
                }
            };
        }

        @Override
        public ReconciliationSession bindReconciliation(
                Connection connection, TargetLedgerContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeConnection implements InvocationHandler {
        private final List<String> events;
        private boolean autoCommit = true;
        private boolean readOnly;
        private boolean rollbackFailure;
        private boolean commitFailure;
        private boolean closeFailure;

        private FakeConnection(List<String> events) {
            this.events = events;
        }

        private Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            return switch (method.getName()) {
                case "setAutoCommit" -> {
                    autoCommit = (boolean) arguments[0];
                    yield null;
                }
                case "getAutoCommit" -> autoCommit;
                case "setReadOnly" -> {
                    readOnly = (boolean) arguments[0];
                    yield null;
                }
                case "isReadOnly" -> readOnly;
                case "isClosed" -> false;
                case "setNetworkTimeout" -> null;
                case "commit" -> {
                    events.add("COMMIT");
                    if (commitFailure) {
                        throw new SQLException("secret commit diagnostic");
                    }
                    yield null;
                }
                case "rollback" -> {
                    events.add("ROLLBACK");
                    if (rollbackFailure) {
                        throw new SQLException("secret rollback diagnostic");
                    }
                    yield null;
                }
                case "close" -> {
                    events.add("CLOSE");
                    if (closeFailure) {
                        throw new SQLException("secret close diagnostic");
                    }
                    yield null;
                }
                case "toString" -> "guarded-test-connection";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            return 0;
        }
    }
}

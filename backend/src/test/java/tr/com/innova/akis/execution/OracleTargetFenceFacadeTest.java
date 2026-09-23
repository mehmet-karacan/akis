package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.OracleTargetFencePort.CommitConfirmed;
import tr.com.innova.akis.execution.OracleTargetFencePort.FailureCode;
import tr.com.innova.akis.execution.OracleTargetFencePort.FencedOut;
import tr.com.innova.akis.execution.OracleTargetFencePort.NotAttempted;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceCommand;
import tr.com.innova.akis.execution.OracleTargetFencePort.OracleTargetFenceResult;
import tr.com.innova.akis.execution.OracleTargetFencePort.OutcomeUnknown;
import tr.com.innova.akis.execution.OracleTargetFencePort.SafeFailure;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.TargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;
import tr.com.innova.akis.execution.TargetIdentityPort.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;

class OracleTargetFenceFacadeTest {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_HASH = "c".repeat(64);
    private static final String TARGET_HASH = "d".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void commitsFenceInExactOpenBindAcquireCommitCloseOrder() {
        Fixture fixture = fixture();

        OracleTargetFenceResult result = fixture.facade.acquire(fixture.command);

        CommitConfirmed confirmed = assertInstanceOf(CommitConfirmed.class, result);
        assertEquals(7, confirmed.receipt().targetGeneration());
        assertSame(fixture.identityConnection, fixture.ledger.boundConnection);
        assertEquals(List.of(
                "OPEN", "IDENTITY", "BIND", "ACQUIRE", "COMMIT", "CLOSE"),
                fixture.events);
    }

    @Test
    void invalidControlContractIsNotAttempted() {
        Fixture fixture = fixture();
        PinnedExecutionContext mismatched = new PinnedExecutionContext(
                fixture.command.execution().jobRequestUuid(), UUID.randomUUID(),
                fixture.command.execution().publicationUuid(), 1,
                RELEASE_HASH, PLAN_HASH,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
        OracleTargetFenceCommand invalid = new OracleTargetFenceCommand(
                fixture.command.plan(), mismatched, fixture.command.fence());

        NotAttempted result = assertInstanceOf(NotAttempted.class,
                fixture.facade.acquire(invalid));

        assertEquals(FailureCode.INVALID_CONTRACT, result.failure());
        assertEquals(List.of(), fixture.events);
    }

    @Test
    void connectionFailureIsNotAttempted() {
        Fixture fixture = fixture();
        OracleTargetFenceFacade facade = new OracleTargetFenceFacade(
                fixture.ledger,
                target -> {
                    fixture.events.add("OPEN");
                    throw new IllegalStateException("secret endpoint detail");
                });

        NotAttempted result = assertInstanceOf(NotAttempted.class,
                facade.acquire(fixture.command));

        assertEquals(FailureCode.CONNECTION_UNAVAILABLE, result.failure());
        assertEquals(List.of("OPEN"), fixture.events);
    }

    @Test
    void wrongSessionPurposeIsRejectedBeforeBindingOrOracleWork() {
        Fixture fixture = fixture();
        OracleTargetFenceFacade facade = new OracleTargetFenceFacade(
                fixture.ledger,
                target -> {
                    fixture.events.add("OPEN");
                    return fixture.provider.openTargetReconciliation(target);
                });

        NotAttempted result = assertInstanceOf(NotAttempted.class,
                facade.acquire(fixture.command));

        assertEquals(FailureCode.INVALID_CONTRACT, result.failure());
        assertEquals(List.of("OPEN", "ROLLBACK", "CLOSE"), fixture.events);

        Fixture readOnly = fixture();
        OracleTargetFenceFacade readOnlyFacade = new OracleTargetFenceFacade(
                readOnly.ledger,
                target -> {
                    readOnly.events.add("OPEN");
                    return readOnly.provider.openTargetIdentityRead(target);
                });

        NotAttempted readOnlyResult = assertInstanceOf(NotAttempted.class,
                readOnlyFacade.acquire(readOnly.command));

        assertEquals(FailureCode.INVALID_CONTRACT, readOnlyResult.failure());
        assertEquals(List.of("OPEN", "CLOSE"), readOnly.events);
        assertEquals(0, readOnly.ledger.bindCalls);
    }

    @Test
    void rejectsLiveTargetIdentityMismatchBeforeLedgerBinding() {
        Fixture fixture = fixture();
        fixture.identity = identity("f".repeat(64));

        SafeFailure result = assertInstanceOf(SafeFailure.class,
                fixture.facade.acquire(fixture.command));

        assertEquals(FailureCode.TARGET_IDENTITY_MISMATCH, result.failure());
        assertEquals(List.of("OPEN", "IDENTITY", "ROLLBACK", "CLOSE"),
                fixture.events);
        assertEquals(0, fixture.ledger.bindCalls);
    }

    @Test
    void targetIdentityReadFailureIsSafeOnlyAfterConfirmedRollback() {
        Fixture fixture = fixture();
        fixture.identityFailure = new IllegalStateException(
                "jdbc:oracle:user/secret@private-host");

        SafeFailure result = assertInstanceOf(SafeFailure.class,
                fixture.facade.acquire(fixture.command));

        assertEquals(FailureCode.TARGET_IDENTITY_MISMATCH, result.failure());
        assertEquals(List.of("OPEN", "IDENTITY", "ROLLBACK", "CLOSE"),
                fixture.events);
        assertEquals(0, fixture.ledger.bindCalls);
    }

    @Test
    void targetIdentityFailureIsUnknownWhenRollbackCannotBeConfirmed() {
        Fixture fixture = fixture();
        fixture.identity = identity("f".repeat(64));
        fixture.connection.rollbackFailure = true;

        OutcomeUnknown result = assertInstanceOf(OutcomeUnknown.class,
                fixture.facade.acquire(fixture.command));

        assertEquals(FailureCode.ROLLBACK_NOT_CONFIRMED, result.failure());
        assertEquals(List.of(
                "OPEN", "IDENTITY", "ROLLBACK", "ROLLBACK", "CLOSE"),
                fixture.events);
        assertEquals(0, fixture.ledger.bindCalls);
    }

    @Test
    void preCommitFailureIsSafeOnlyAfterConfirmedRollback() {
        Fixture fixture = fixture();
        fixture.ledger.acquireFailure = true;

        SafeFailure result = assertInstanceOf(SafeFailure.class,
                fixture.facade.acquire(fixture.command));

        assertEquals(FailureCode.ORACLE_OPERATION_REJECTED, result.failure());
        assertEquals(List.of(
                "OPEN", "IDENTITY", "BIND", "ACQUIRE", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    @Test
    void deterministicLedgerFenceRejectionsBecomeFencedOutAfterRollback() {
        List<OracleLedgerFailure> failures = List.of(
                OracleLedgerFailure.FENCE_NOT_FOUND,
                OracleLedgerFailure.FENCE_OWNERSHIP_MISMATCH,
                OracleLedgerFailure.STALE_FENCE_TOKEN,
                OracleLedgerFailure.FENCE_OWNER_CONFLICT);

        for (OracleLedgerFailure failure : failures) {
            Fixture fixture = fixture();
            fixture.ledger.fenceRejection = failure;

            FencedOut result = assertInstanceOf(FencedOut.class,
                    fixture.facade.acquire(fixture.command));

            assertEquals(FailureCode.STALE_FENCE, result.failure());
            assertEquals(List.of(
                    "OPEN", "IDENTITY", "BIND", "ACQUIRE", "ROLLBACK", "CLOSE"),
                    fixture.events);
        }
    }

    @Test
    void unconfirmedPreCommitRollbackIsOutcomeUnknown() {
        Fixture fixture = fixture();
        fixture.ledger.acquireFailure = true;
        fixture.connection.rollbackFailure = true;

        OutcomeUnknown result = assertInstanceOf(OutcomeUnknown.class,
                fixture.facade.acquire(fixture.command));

        assertEquals(FailureCode.ROLLBACK_NOT_CONFIRMED, result.failure());
        assertEquals(List.of(
                "OPEN", "IDENTITY", "BIND", "ACQUIRE", "ROLLBACK", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    @Test
    void commitExceptionAlwaysRemainsOutcomeUnknown() {
        Fixture fixture = fixture();
        fixture.connection.commitFailure = true;

        OutcomeUnknown result = assertInstanceOf(OutcomeUnknown.class,
                fixture.facade.acquire(fixture.command));

        assertEquals(FailureCode.COMMIT_OUTCOME_UNKNOWN, result.failure());
        assertEquals(List.of(
                "OPEN", "IDENTITY", "BIND", "ACQUIRE", "COMMIT", "ROLLBACK", "CLOSE"),
                fixture.events);
    }

    private Fixture fixture() {
        List<String> events = new ArrayList<>();
        FakeConnection connection = new FakeConnection(events);
        FakeLedger ledger = new FakeLedger(events);
        OracleTargetFenceCommand command = command();
        RuntimeOracleConnectionProvider provider = new RuntimeOracleConnectionProvider(
                binding -> Optional.of(profile(binding.connectionVersionUuid())),
                objectMapper,
                ignored -> "{\"username\":\"INNOVA_ODI\",\"password\":\"hidden\"}",
                (url, properties) -> connection.proxy(),
                Runnable::run);
        Fixture fixture = new Fixture(events, connection, ledger, command, provider);
        fixture.facade = new OracleTargetFenceFacade(
                ledger,
                target -> {
                    events.add("OPEN");
                    return provider.openTargetFence(target);
                },
                (activeConnection, owner, objectType, objectName) -> {
                    events.add("IDENTITY");
                    fixture.identityConnection = activeConnection;
                    if (fixture.identityFailure != null) {
                        throw fixture.identityFailure;
                    }
                    return fixture.identity;
                });
        return fixture;
    }

    private CanonicalTargetIdentity identity(String hash) {
        return new CanonicalTargetIdentity(
                "ORACLE", OracleTargetIdentityV1.TARGET_IDENTITY_VERSION,
                "AKISDB", "AKISPDB", "OWNER", "TABLE", "TABLE",
                new byte[0], hash);
    }

    private OracleTargetFenceCommand command() {
        UUID runUuid = UUID.randomUUID();
        PilotRuntimePlan plan = new PilotRuntimePlan(
                1, RUNTIME_HASH, RELEASE_HASH, PLAN_HASH,
                UUID.randomUUID(), UUID.randomUUID(), 100,
                binding(DatasetRole.SOURCE), binding(DatasetRole.TARGET),
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
        PinnedExecutionContext execution = new PinnedExecutionContext(
                UUID.randomUUID(), runUuid, UUID.randomUUID(), 1,
                RELEASE_HASH, PLAN_HASH,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
        TargetFenceToken fence = new TargetFenceToken(
                runUuid, "worker-1", 3, UUID.randomUUID(), 7,
                TARGET_HASH, OracleTargetIdentityV1.TARGET_IDENTITY_VERSION);
        return new OracleTargetFenceCommand(plan, execution, fence);
    }

    private DatasetBinding binding(DatasetRole role) {
        return new DatasetBinding(
                role == DatasetRole.SOURCE ? "SOURCE" : "TARGET",
                role, DatabaseType.ORACLE, DataObjectType.TABLE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "e".repeat(64), "OWNER.TABLE", "OWNER", "TABLE");
    }

    private ConnectionProfile profile(UUID connectionVersionUuid) {
        return new ConnectionProfile(
                UUID.randomUUID(), connectionVersionUuid,
                "oracle.jdbc.OracleDriver", "db.internal", "SERVICE", null,
                1521, "DISABLED", objectMapper.createObjectNode(),
                "ENV", "AKIS_ORACLE_FENCE_SECRET");
    }

    private final class Fixture {
        private final List<String> events;
        private final FakeConnection connection;
        private final FakeLedger ledger;
        private final OracleTargetFenceCommand command;
        private final RuntimeOracleConnectionProvider provider;
        private OracleTargetFenceFacade facade;
        private CanonicalTargetIdentity identity = identity(TARGET_HASH);
        private RuntimeException identityFailure;
        private Connection identityConnection;

        private Fixture(
                List<String> events,
                FakeConnection connection,
                FakeLedger ledger,
                OracleTargetFenceCommand command,
                RuntimeOracleConnectionProvider provider) {
            this.events = events;
            this.connection = connection;
            this.ledger = ledger;
            this.command = command;
            this.provider = provider;
        }
    }

    private static final class FakeLedger implements TargetLedgerPort {

        private final List<String> events;
        private boolean acquireFailure;
        private OracleLedgerFailure fenceRejection;
        private int bindCalls;
        private Connection boundConnection;

        private FakeLedger(List<String> events) {
            this.events = events;
        }

        @Override
        public FenceSession bindFence(Connection connection, TargetLedgerContext context) {
            bindCalls++;
            boundConnection = connection;
            events.add("BIND");
            return () -> {
                events.add("ACQUIRE");
                if (fenceRejection != null) {
                    throw new OracleFenceRejectedException(
                            fenceRejection, 20_101,
                            new SQLException("secret fence diagnostic"));
                }
                if (acquireFailure) {
                    throw new IllegalStateException("secret Oracle diagnostic");
                }
            };
        }

        @Override
        public DataLedgerSession bindData(Connection connection, TargetLedgerContext context) {
            throw new UnsupportedOperationException();
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

        private FakeConnection(List<String> events) {
            this.events = events;
        }

        private Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, this);
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
                    yield null;
                }
                case "toString" -> "fence-test-connection";
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
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            return 0;
        }
    }
}

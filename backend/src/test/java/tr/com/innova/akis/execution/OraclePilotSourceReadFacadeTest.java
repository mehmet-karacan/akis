package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import tr.com.innova.akis.execution.OraclePilotSourceReadFacade.SourceSessionHandle;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.Failure;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.NotAttempted;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.OutcomeUnknown;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.ReadSucceeded;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.SafeFailure;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.SourceReadCommand;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;

class OraclePilotSourceReadFacadeTest {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_HASH = "c".repeat(64);
    private static final String PAYLOAD_HASH = "d".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaFingerprint fingerprint = new SchemaFingerprint(objectMapper);

    @Test
    void opensExactSourcePurposeReadsBoundedBatchAndAlwaysCloses() {
        Fixture fixture = fixture();

        var result = fixture.facade.read(fixture.command);

        ReadSucceeded success = assertInstanceOf(ReadSucceeded.class, result);
        assertEquals(RUNTIME_HASH, success.batch().runtimePlanHash());
        assertEquals(List.of(
                "OPEN", "PURPOSE", "CONNECTION", "IS_CLOSED", "IS_READ_ONLY",
                "GET_AUTO_COMMIT", "PREFLIGHT", "READ", "CLOSE"), fixture.events);
        assertEquals(fixture.command.plan().source(), fixture.openedBinding);
    }

    @Test
    void rejectsInvalidPinnedAggregateBeforeOpeningOracle() {
        Fixture fixture = fixture();
        PinnedSnapshots wrongPublication = new PinnedSnapshots(
                fixture.command.snapshots().projectUuid(), UUID.randomUUID(),
                fixture.command.snapshots().source(), fixture.command.snapshots().target());
        SourceReadCommand tampered = new SourceReadCommand(
                fixture.command.plan(), fixture.command.execution(), wrongPublication);

        NotAttempted result = assertInstanceOf(
                NotAttempted.class, fixture.facade.read(tampered));

        assertEquals(Failure.INVALID_CONTRACT, result.failure());
        assertEquals(List.of(), fixture.events);
    }

    @Test
    void rejectsTamperedSnapshotBodyBeforeOpeningOracle() {
        Fixture fixture = fixture();
        SchemaFingerprintInput tamperedBody = schema("VARCHAR2(10)", "STRING");
        PinnedSnapshot source = new PinnedSnapshot(
                fixture.command.snapshots().source().schemaSnapshotUuid(),
                fixture.command.snapshots().source().verifiedFingerprint(),
                tamperedBody);
        SourceReadCommand tampered = new SourceReadCommand(
                fixture.command.plan(),
                fixture.command.execution(),
                new PinnedSnapshots(
                        fixture.command.snapshots().projectUuid(),
                        fixture.command.snapshots().publicationUuid(),
                        source,
                        fixture.command.snapshots().target()));

        NotAttempted result = assertInstanceOf(
                NotAttempted.class, fixture.facade.read(tampered));

        assertEquals(Failure.INVALID_CONTRACT, result.failure());
        assertEquals(List.of(), fixture.events);
    }

    @Test
    void classifiesConnectionOpenFailureAsNotAttempted() {
        Fixture fixture = fixture();
        fixture.openFailure = new IllegalStateException(
                "jdbc:oracle:user/secret@private-host");

        NotAttempted result = assertInstanceOf(
                NotAttempted.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.CONNECTION_UNAVAILABLE, result.failure());
        assertEquals(List.of("OPEN"), fixture.events);
    }

    @Test
    void classifiesMissingSessionAsConnectionUnavailable() {
        Fixture fixture = fixture();
        fixture.session = null;

        NotAttempted result = assertInstanceOf(
                NotAttempted.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.CONNECTION_UNAVAILABLE, result.failure());
        assertEquals(List.of("OPEN"), fixture.events);
    }

    @Test
    void rejectsWrongPurposeBeforeConnectionAccessAndClosesSession() {
        Fixture fixture = fixture();
        fixture.session.purpose = RuntimeOracleConnectionProvider.SessionPurpose.TARGET_FENCE;

        NotAttempted result = assertInstanceOf(
                NotAttempted.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.INVALID_SESSION_PURPOSE, result.failure());
        assertEquals(List.of("OPEN", "PURPOSE", "CLOSE"), fixture.events);
        assertEquals(0, fixture.session.connectionAccesses);
        assertEquals(0, fixture.readCalls);
    }

    @Test
    void requiresReadOnlyAutoCommitSourceLifecycleBeforeRead() {
        for (ConnectionState state : List.of(
                new ConnectionState(false, false, true),
                new ConnectionState(false, true, false),
                new ConnectionState(true, true, true))) {
            Fixture fixture = fixture();
            fixture.session.state = state;

            NotAttempted result = assertInstanceOf(
                    NotAttempted.class, fixture.facade.read(fixture.command));

            assertEquals(Failure.INVALID_SESSION_STATE, result.failure());
            assertEquals(0, fixture.readCalls);
            assertEquals("CLOSE", fixture.events.getLast());
        }
    }

    @Test
    void mapsTypedAndUnexpectedReadErrorsWithoutRetainingRawCause() {
        Fixture typed = fixture();
        typed.readFailure = new OraclePilotDataException(
                OraclePilotDataException.Failure.SOURCE_ROW_LIMIT_EXCEEDED,
                "sanitized");

        SafeFailure typedResult = assertInstanceOf(
                SafeFailure.class, typed.facade.read(typed.command));

        assertEquals(Failure.SOURCE_READ_REJECTED, typedResult.failure());
        assertEquals("CLOSE", typed.events.getLast());

        Fixture unexpected = fixture();
        unexpected.readFailure = new IllegalStateException(
                "jdbc:oracle:user/secret@private-host");

        SafeFailure unexpectedResult = assertInstanceOf(
                SafeFailure.class, unexpected.facade.read(unexpected.command));

        assertEquals(Failure.SOURCE_READ_FAILED, unexpectedResult.failure());
        assertEquals("CLOSE", unexpected.events.getLast());
    }

    @Test
    void refusesToReadWhenSameSessionSourceSchemaAttestationFails() {
        Fixture fixture = fixture();
        fixture.preflightFailure = new OracleSchemaPreflightException(
                OracleSchemaPreflightFailure.SNAPSHOT_FINGERPRINT_MISMATCH);

        SafeFailure result = assertInstanceOf(
                SafeFailure.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.SOURCE_SCHEMA_DRIFT, result.failure());
        assertEquals(0, fixture.readCalls);
        assertEquals("CLOSE", fixture.events.getLast());
    }

    @Test
    void closeUncertaintyOverridesSuccessOrReadFailure() {
        Fixture success = fixture();
        success.session.closeFailure = true;

        OutcomeUnknown successUnknown = assertInstanceOf(
                OutcomeUnknown.class, success.facade.read(success.command));

        assertEquals(Failure.SESSION_CLOSE_UNCONFIRMED, successUnknown.failure());

        Fixture failedRead = fixture();
        failedRead.readFailure = new IllegalStateException("private failure");
        failedRead.session.closeFailure = true;

        OutcomeUnknown failureUnknown = assertInstanceOf(
                OutcomeUnknown.class, failedRead.facade.read(failedRead.command));

        assertEquals(Failure.SESSION_CLOSE_UNCONFIRMED, failureUnknown.failure());
    }

    private Fixture fixture() {
        SchemaFingerprintInput sourceBody = schema("NUMBER(19)", "INTEGER");
        SchemaFingerprintInput targetBody = schema("NUMBER(19)", "INTEGER");
        String sourceFingerprint = fingerprint.calculate(sourceBody);
        String targetFingerprint = fingerprint.calculate(targetBody);
        PilotRuntimePlan plan = plan(sourceFingerprint, targetFingerprint);
        UUID publicationUuid = UUID.randomUUID();
        PinnedExecutionContext execution = new PinnedExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), publicationUuid, 1,
                RELEASE_HASH, PLAN_HASH,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
        PinnedSnapshots snapshots = new PinnedSnapshots(
                UUID.randomUUID(), publicationUuid,
                new PinnedSnapshot(
                        plan.source().schemaSnapshotUuid(),
                        sourceFingerprint, sourceBody),
                new PinnedSnapshot(
                        plan.target().schemaSnapshotUuid(),
                        targetFingerprint, targetBody));
        return new Fixture(new SourceReadCommand(plan, execution, snapshots));
    }

    private PilotRuntimePlan plan(String sourceFingerprint, String targetFingerprint) {
        return new PilotRuntimePlan(
                1, RUNTIME_HASH, RELEASE_HASH, PLAN_HASH,
                UUID.randomUUID(), UUID.randomUUID(), 1000,
                binding(DatasetRole.SOURCE, "TTBP", "HAKEDIS_TIPI", sourceFingerprint),
                binding(DatasetRole.TARGET, "INNOVA_ODI", "STG_HAKEDIS_TIPI",
                        targetFingerprint),
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
    }

    private DatasetBinding binding(
            DatasetRole role, String owner, String objectName, String snapshotFingerprint) {
        return new DatasetBinding(
                role.name().toLowerCase(), role, DatabaseType.ORACLE, DataObjectType.TABLE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1,
                snapshotFingerprint, owner + "." + objectName, owner, objectName);
    }

    private SchemaFingerprintInput schema(String producerType, String canonicalType) {
        return new SchemaFingerprintInput(
                "Oracle Database 19c", 1, objectMapper.createObjectNode(),
                List.of(new Column(
                        "ID", producerType, canonicalType, 1,
                        producerType.startsWith("NUMBER") ? 19 : null,
                        producerType.startsWith("NUMBER") ? 0 : null,
                        producerType.startsWith("VARCHAR2") ? 10L : null,
                        null, false, null, "ID")),
                List.of());
    }

    private static OraclePilotBatch batch() {
        return new OraclePilotBatch(
                RUNTIME_HASH, List.of(), List.of(), PAYLOAD_HASH, 0);
    }

    private final class Fixture {
        private final SourceReadCommand command;
        private final List<String> events = new ArrayList<>();
        private FakeSession session = new FakeSession(events);
        private final OraclePilotSourceReadFacade facade;
        private DatasetBinding openedBinding;
        private RuntimeException openFailure;
        private RuntimeException readFailure;
        private RuntimeException preflightFailure;
        private int readCalls;

        private Fixture(SourceReadCommand command) {
            this.command = command;
            this.facade = new OraclePilotSourceReadFacade(
                    binding -> {
                        events.add("OPEN");
                        if (openFailure != null) {
                            throw openFailure;
                        }
                        openedBinding = binding;
                        return session;
                    },
                    (connection, plan) -> {
                        events.add("READ");
                        readCalls++;
                        if (readFailure != null) {
                            throw readFailure;
                        }
                        return batch();
                    },
                    (plan, connection, snapshot) -> {
                        events.add("PREFLIGHT");
                        if (preflightFailure != null) {
                            throw preflightFailure;
                        }
                    },
                    fingerprint);
        }
    }

    private static final class FakeSession implements SourceSessionHandle {
        private final List<String> events;
        private RuntimeOracleConnectionProvider.SessionPurpose purpose =
                RuntimeOracleConnectionProvider.SessionPurpose.SOURCE_READ;
        private ConnectionState state = new ConnectionState(false, true, true);
        private boolean closeFailure;
        private int connectionAccesses;

        private FakeSession(List<String> events) {
            this.events = events;
        }

        @Override
        public RuntimeOracleConnectionProvider.SessionPurpose purpose() {
            events.add("PURPOSE");
            return purpose;
        }

        @Override
        public Connection connection() {
            events.add("CONNECTION");
            connectionAccesses++;
            return proxy(Connection.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "isClosed" -> {
                    events.add("IS_CLOSED");
                    yield state.closed();
                }
                case "isReadOnly" -> {
                    events.add("IS_READ_ONLY");
                    yield state.readOnly();
                }
                case "getAutoCommit" -> {
                    events.add("GET_AUTO_COMMIT");
                    yield state.autoCommit();
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        @Override
        public void close() {
            events.add("CLOSE");
            if (closeFailure) {
                throw new IllegalStateException("private close failure");
            }
        }
    }

    private record ConnectionState(boolean closed, boolean readOnly, boolean autoCommit) {
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

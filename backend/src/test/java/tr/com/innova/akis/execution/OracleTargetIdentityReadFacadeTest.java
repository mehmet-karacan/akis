package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

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
import tr.com.innova.akis.execution.OracleTargetIdentityReadFacade.TargetIdentitySessionHandle;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.Failure;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.IdentityReadSucceeded;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.NotAttempted;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.SafeFailure;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityReadCommand;
import tr.com.innova.akis.execution.TargetIdentityPort.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.VerifiedDatabaseIdentity;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;

class OracleTargetIdentityReadFacadeTest {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_HASH = "c".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaFingerprint fingerprint = new SchemaFingerprint(objectMapper);

    @Test
    void readsSchemaAndCanonicalIdentityOnTheSameExactPurposeSession() {
        Fixture fixture = fixture();

        IdentityReadSucceeded result = assertInstanceOf(
                IdentityReadSucceeded.class, fixture.facade.read(fixture.command));

        assertEquals(OracleTargetIdentityV1.TARGET_IDENTITY_VERSION,
                result.evidence().targetIdentityVersion());
        assertEquals("AKISDB", result.evidence().databaseUniqueName());
        assertEquals("AKISPDB", result.evidence().containerName());
        assertEquals("INNOVA_ODI", result.evidence().owner());
        assertEquals("STG_HAKEDIS_TIPI", result.evidence().objectName());
        assertEquals(fixture.identity.canonicalTargetHash(),
                result.evidence().canonicalTargetHash());
        assertEquals(fixture.command.plan().target(), fixture.openedBinding);
        assertSame(fixture.preflightConnection, fixture.identityConnection);
        assertEquals(List.of(
                "OPEN", "PURPOSE", "CONNECTION", "IS_CLOSED", "IS_READ_ONLY",
                "GET_AUTO_COMMIT", "PREFLIGHT", "IDENTITY", "CLOSE"), fixture.events);
    }

    @Test
    void rejectsTamperedPinnedTargetBeforeOpeningOracle() {
        Fixture fixture = fixture();
        PinnedSnapshot tampered = new PinnedSnapshot(
                fixture.command.targetSnapshot().schemaSnapshotUuid(),
                fixture.command.targetSnapshot().verifiedFingerprint(),
                schema("VARCHAR2(10)", "STRING"));

        NotAttempted result = assertInstanceOf(NotAttempted.class,
                fixture.facade.read(new TargetIdentityReadCommand(
                        fixture.command.plan(), fixture.command.execution(), tampered)));

        assertEquals(Failure.INVALID_CONTRACT, result.failure());
        assertEquals(List.of(), fixture.events);
    }

    @Test
    void connectionFailureIsSanitizedAndClassifiedBeforeAnyRead() {
        Fixture fixture = fixture();
        fixture.openFailure = new IllegalStateException(
                "jdbc:oracle:user/secret@private-host");

        NotAttempted result = assertInstanceOf(
                NotAttempted.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.CONNECTION_UNAVAILABLE, result.failure());
        assertEquals(List.of("OPEN"), fixture.events);
    }

    @Test
    void rejectsWrongPurposeBeforeConnectionAccessAndStillCloses() {
        Fixture fixture = fixture();
        fixture.session.purpose = RuntimeOracleConnectionProvider.SessionPurpose.TARGET_FENCE;

        NotAttempted result = assertInstanceOf(
                NotAttempted.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.INVALID_SESSION_PURPOSE, result.failure());
        assertEquals(List.of("OPEN", "PURPOSE", "CLOSE"), fixture.events);
        assertEquals(0, fixture.session.connectionAccesses);
    }

    @Test
    void requiresOpenReadOnlyAutoCommitSessionBeforeOracleReads() {
        for (ConnectionState state : List.of(
                new ConnectionState(true, true, true),
                new ConnectionState(false, false, true),
                new ConnectionState(false, true, false))) {
            Fixture fixture = fixture();
            fixture.session.state = state;

            NotAttempted result = assertInstanceOf(
                    NotAttempted.class, fixture.facade.read(fixture.command));

            assertEquals(Failure.INVALID_SESSION_STATE, result.failure());
            assertEquals(0, fixture.preflightCalls);
            assertEquals(0, fixture.identityCalls);
            assertEquals("CLOSE", fixture.events.getLast());
        }
    }

    @Test
    void mapsSchemaIdentityAndUnexpectedFailuresWithoutRawCauses() {
        Fixture schemaFailure = fixture();
        schemaFailure.preflightFailure = new OracleSchemaPreflightException(
                OracleSchemaPreflightFailure.LIVE_SCHEMA_DRIFT);
        SafeFailure schemaResult = assertInstanceOf(
                SafeFailure.class, schemaFailure.facade.read(schemaFailure.command));
        assertEquals(Failure.TARGET_SCHEMA_REJECTED, schemaResult.failure());
        assertEquals(0, schemaFailure.identityCalls);
        assertEquals("CLOSE", schemaFailure.events.getLast());

        Fixture identityFailure = fixture();
        identityFailure.identityFailure = new OracleTargetIdentityException(
                "sanitized identity failure");
        SafeFailure identityResult = assertInstanceOf(
                SafeFailure.class, identityFailure.facade.read(identityFailure.command));
        assertEquals(Failure.TARGET_IDENTITY_REJECTED, identityResult.failure());
        assertEquals("CLOSE", identityFailure.events.getLast());

        Fixture unexpected = fixture();
        unexpected.identityFailure = new IllegalStateException(
                "jdbc:oracle:user/secret@private-host");
        SafeFailure unexpectedResult = assertInstanceOf(
                SafeFailure.class, unexpected.facade.read(unexpected.command));
        assertEquals(Failure.TARGET_READ_FAILED, unexpectedResult.failure());
        assertEquals("CLOSE", unexpected.events.getLast());
    }

    @Test
    void rejectsIdentityThatDoesNotMatchThePinnedTargetBinding() {
        Fixture fixture = fixture();
        fixture.identity = new OracleTargetIdentityV1().canonicalize(
                new VerifiedDatabaseIdentity("AKISDB", "AKISPDB"),
                "INNOVA_ODI", "TABLE", "OTHER_TABLE");

        SafeFailure result = assertInstanceOf(
                SafeFailure.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.TARGET_IDENTITY_REJECTED, result.failure());
        assertEquals("CLOSE", fixture.events.getLast());
    }

    @Test
    void closeUncertaintyOverridesSuccessfulReadWithoutLeakingCause() {
        Fixture fixture = fixture();
        fixture.session.closeFailure = true;

        SafeFailure result = assertInstanceOf(
                SafeFailure.class, fixture.facade.read(fixture.command));

        assertEquals(Failure.SESSION_CLOSE_UNCONFIRMED, result.failure());
        assertEquals("CLOSE", fixture.events.getLast());
    }

    private Fixture fixture() {
        SchemaFingerprintInput targetBody = schema("NUMBER(19)", "INTEGER");
        String targetFingerprint = fingerprint.calculate(targetBody);
        PilotRuntimePlan plan = plan(targetFingerprint);
        PinnedExecutionContext execution = new PinnedExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                RELEASE_HASH, PLAN_HASH,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
        PinnedSnapshot snapshot = new PinnedSnapshot(
                plan.target().schemaSnapshotUuid(), targetFingerprint, targetBody);
        return new Fixture(new TargetIdentityReadCommand(plan, execution, snapshot));
    }

    private PilotRuntimePlan plan(String targetFingerprint) {
        return new PilotRuntimePlan(
                1, RUNTIME_HASH, RELEASE_HASH, PLAN_HASH,
                UUID.randomUUID(), UUID.randomUUID(), 1000,
                binding(DatasetRole.SOURCE, "TTBP", "HAKEDIS_TIPI", "d".repeat(64)),
                binding(DatasetRole.TARGET, "INNOVA_ODI", "STG_HAKEDIS_TIPI",
                        targetFingerprint),
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
    }

    private DatasetBinding binding(
            DatasetRole role, String owner, String objectName, String snapshotFingerprint) {
        return new DatasetBinding(
                role.name(), role, DatabaseType.ORACLE, DataObjectType.TABLE,
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

    private final class Fixture {
        private final TargetIdentityReadCommand command;
        private final List<String> events = new ArrayList<>();
        private FakeSession session = new FakeSession(events);
        private final OracleTargetIdentityReadFacade facade;
        private DatasetBinding openedBinding;
        private RuntimeException openFailure;
        private RuntimeException preflightFailure;
        private RuntimeException identityFailure;
        private CanonicalTargetIdentity identity = new OracleTargetIdentityV1().canonicalize(
                new VerifiedDatabaseIdentity("AKISDB", "AKISPDB"),
                "INNOVA_ODI", "TABLE", "STG_HAKEDIS_TIPI");
        private Connection preflightConnection;
        private Connection identityConnection;
        private int preflightCalls;
        private int identityCalls;

        private Fixture(TargetIdentityReadCommand command) {
            this.command = command;
            this.facade = new OracleTargetIdentityReadFacade(
                    binding -> {
                        events.add("OPEN");
                        if (openFailure != null) {
                            throw openFailure;
                        }
                        openedBinding = binding;
                        return session;
                    },
                    (connection, owner, objectType, objectName) -> {
                        events.add("IDENTITY");
                        identityCalls++;
                        identityConnection = connection;
                        if (identityFailure != null) {
                            throw identityFailure;
                        }
                        return identity;
                    },
                    (plan, connection, snapshot) -> {
                        events.add("PREFLIGHT");
                        preflightCalls++;
                        preflightConnection = connection;
                        if (preflightFailure != null) {
                            throw preflightFailure;
                        }
                    },
                    fingerprint);
        }
    }

    private static final class FakeSession implements TargetIdentitySessionHandle {
        private final List<String> events;
        private RuntimeOracleConnectionProvider.SessionPurpose purpose =
                RuntimeOracleConnectionProvider.SessionPurpose.TARGET_IDENTITY_READ;
        private ConnectionState state = new ConnectionState(false, true, true);
        private boolean closeFailure;
        private int connectionAccesses;
        private final Connection connection;

        private FakeSession(List<String> events) {
            this.events = events;
            this.connection = proxy(Connection.class, (proxy, method, arguments) ->
                    switch (method.getName()) {
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
        public RuntimeOracleConnectionProvider.SessionPurpose purpose() {
            events.add("PURPOSE");
            return purpose;
        }

        @Override
        public Connection connection() {
            events.add("CONNECTION");
            connectionAccesses++;
            return connection;
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

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.RuntimeOracleConnectionException.Failure;
import tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.SessionPurpose;

class RuntimeOracleConnectionProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sourceAndTargetControlPurposesUseFreshConfiguredSessions() {
        List<FakeConnection> opened = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        List<Properties> seenProperties = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();
        RuntimeOracleConnectionProvider provider = provider(
                normalProfile(), name -> credential(), (url, properties) -> {
                    FakeConnection fake = new FakeConnection(false);
                    opened.add(fake);
                    urls.add(url);
                    Properties copy = new Properties();
                    copy.putAll(properties);
                    seenProperties.add(copy);
                    opens.incrementAndGet();
                    return fake.proxy();
                });

        RuntimeOracleSession source = provider.openSource(binding(DatasetRole.SOURCE));
        RuntimeOracleSession identity = provider.openTargetIdentityRead(
                binding(DatasetRole.TARGET));
        RuntimeOracleSession fence = provider.openTargetFence(binding(DatasetRole.TARGET));
        RuntimeOracleSession reconciliation = provider.openTargetReconciliation(
                binding(DatasetRole.TARGET));

        assertEquals(4, opens.get());
        assertNotSame(source.connection(), identity.connection());
        assertNotSame(identity.connection(), fence.connection());
        assertNotSame(fence.connection(), reconciliation.connection());
        assertEquals(SessionPurpose.SOURCE_READ, source.purpose());
        assertEquals(SessionPurpose.TARGET_IDENTITY_READ, identity.purpose());
        for (int index = 0; index < 2; index++) {
            assertTrue(opened.get(index).readOnly);
            assertTrue(opened.get(index).autoCommit);
        }
        for (int index = 2; index < opened.size(); index++) {
            assertFalse(opened.get(index).readOnly);
            assertFalse(opened.get(index).autoCommit);
            assertEquals(45_000, opened.get(index).networkTimeout);
        }
        assertEquals(
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)"
                        + "(HOST=db.internal)(PORT=1521))"
                        + "(CONNECT_DATA=(SERVICE_NAME=SERVICE_1)))",
                urls.getFirst());
        assertEquals("12000", seenProperties.getFirst()
                .getProperty("oracle.net.CONNECT_TIMEOUT"));
        assertEquals("34000", seenProperties.getFirst()
                .getProperty("oracle.jdbc.ReadTimeout"));

        source.close();
        identity.close();
        fence.close();
        reconciliation.commitConfirmed();
        reconciliation.close();

        assertEquals(List.of("close"), opened.get(0).terminalEvents());
        assertEquals(List.of("close"), opened.get(1).terminalEvents());
        assertEquals(List.of("rollback", "close"), opened.get(2).terminalEvents());
        assertEquals(List.of("commit", "close"), opened.get(3).terminalEvents());
    }

    @Test
    void targetDataSessionRequiresAtomicFacadeCapability() {
        AtomicInteger opens = new AtomicInteger();
        RuntimeOracleConnectionProvider provider = provider(
                normalProfile(), name -> credential(), (url, properties) -> {
                    opens.incrementAndGet();
                    return new FakeConnection(false).proxy();
                });

        RuntimeOracleConnectionException error = assertThrows(
                RuntimeOracleConnectionException.class,
                () -> provider.openTargetData(binding(DatasetRole.TARGET), null));

        assertEquals(Failure.INVALID_CONTRACT, error.failure());
        assertEquals(0, opens.get());
    }

    @Test
    void appliesBoundedQueryTimeoutToEveryStatementFactory() throws SQLException {
        FakeConnection fake = new FakeConnection(false);
        RuntimeOracleConnectionProvider provider = provider(
                normalProfile(), name -> credential(),
                (url, properties) -> fake.proxy());

        try (RuntimeOracleSession session = provider.openTargetFence(
                binding(DatasetRole.TARGET))) {
            session.connection().prepareStatement("select 1 from dual");
            session.connection().prepareCall("{call TEST_PROC()}");
            session.connection().createStatement();
            assertEquals(17, fake.preparedStatement.queryTimeout);
            assertEquals(17, fake.callableStatement.queryTimeout);
            assertEquals(17, fake.plainStatement.queryTimeout);
            session.rollbackConfirmed();
        }
    }

    @Test
    void dirtyTargetCloseReportsSanitizedRollbackFailureButStillCloses() {
        FakeConnection fake = new FakeConnection(true);
        RuntimeOracleConnectionProvider provider = provider(
                normalProfile(), name -> credential(),
                (url, properties) -> fake.proxy());
        RuntimeOracleSession session = provider.openTargetFence(binding(DatasetRole.TARGET));

        RuntimeOracleConnectionException exception = assertThrows(
                RuntimeOracleConnectionException.class, session::close);

        assertEquals(Failure.ROLLBACK_NOT_CONFIRMED, exception.failure());
        assertEquals("Runtime Oracle connection operation failed safely.",
                exception.getMessage());
        assertNull(exception.getCause());
        assertEquals(List.of("rollback", "close"), fake.terminalEvents());
    }

    @Test
    void rejectsDescriptorInjectionAndNonEnvProviderBeforeOpening() {
        ConnectionProfile invalid = new ConnectionProfile(
                UUID.randomUUID(), CONNECTION_VERSION_UUID, "oracle.jdbc.OracleDriver",
                "db.internal)(PORT=9999", "SERVICE", null, 1521, "DISABLED",
                policy(), "VAULT", "AKIS_SECRET");
        AtomicInteger opens = new AtomicInteger();
        RuntimeOracleConnectionProvider provider = provider(
                invalid, name -> credential(), (url, properties) -> {
                    opens.incrementAndGet();
                    return new FakeConnection(false).proxy();
                });

        RuntimeOracleConnectionException exception = assertThrows(
                RuntimeOracleConnectionException.class,
                () -> provider.openSource(binding(DatasetRole.SOURCE)));

        assertEquals(Failure.UNSUPPORTED_PROFILE, exception.failure());
        assertEquals(0, opens.get());
    }

    @Test
    void sanitizesEnvironmentAndJdbcFailures() {
        RuntimeOracleConnectionProvider envFailure = provider(
                normalProfile(), name -> {
                    throw new IllegalStateException("secret-value");
                }, (url, properties) -> new FakeConnection(false).proxy());
        RuntimeOracleConnectionException credentialException = assertThrows(
                RuntimeOracleConnectionException.class,
                () -> envFailure.openSource(binding(DatasetRole.SOURCE)));
        assertEquals(Failure.CREDENTIAL_UNAVAILABLE, credentialException.failure());
        assertNull(credentialException.getCause());

        RuntimeOracleConnectionProvider jdbcFailure = provider(
                normalProfile(), name -> credential(), (url, properties) -> {
                    throw new SQLException("jdbc:oracle:thin:@secret-endpoint");
                });
        RuntimeOracleConnectionException connectionException = assertThrows(
                RuntimeOracleConnectionException.class,
                () -> jdbcFailure.openSource(binding(DatasetRole.SOURCE)));
        assertEquals(Failure.CONNECTION_FAILED, connectionException.failure());
        assertFalse(connectionException.getMessage().contains("endpoint"));
        assertNull(connectionException.getCause());
    }

    private RuntimeOracleConnectionProvider provider(
            ConnectionProfile profile,
            java.util.function.Function<String, String> environment,
            RuntimeOracleConnectionProvider.ConnectionOpener opener) {
        RuntimeOracleConnectionMetadataPort metadata = binding ->
                java.util.Optional.of(profile);
        return new RuntimeOracleConnectionProvider(
                metadata, objectMapper, environment, opener, Runnable::run);
    }

    private ConnectionProfile normalProfile() {
        return new ConnectionProfile(
                UUID.randomUUID(), CONNECTION_VERSION_UUID,
                "oracle.jdbc.OracleDriver", "db.internal", "SERVICE_1", null,
                1521, "DISABLED", policy(), "ENV", "AKIS_ORACLE_RUNTIME_SECRET");
    }

    private ObjectNode policy() {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("connectTimeoutMs", 12_000);
        policy.put("readTimeoutMs", 34_000);
        policy.put("networkTimeoutMs", 45_000);
        policy.put("queryTimeoutSeconds", 17);
        return policy;
    }

    private String credential() {
        return "{\"username\":\"INNOVA_ODI\",\"password\":\"not-real\"}";
    }

    private static final UUID CONNECTION_VERSION_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");

    private DatasetBinding binding(DatasetRole role) {
        return new DatasetBinding(
                role == DatasetRole.SOURCE ? "SOURCE_NODE" : "TARGET_NODE",
                role, DatabaseType.ORACLE, DataObjectType.TABLE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CONNECTION_VERSION_UUID, UUID.randomUUID(),
                1, "a".repeat(64), "OWNER.TABLE", "OWNER", "TABLE");
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
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

    private static final class FakeConnection implements InvocationHandler {

        private final boolean failRollback;
        private final List<String> events = new ArrayList<>();
        private boolean readOnly;
        private boolean autoCommit = true;
        private int networkTimeout;
        private final FakeStatement preparedStatement = new FakeStatement();
        private final FakeStatement callableStatement = new FakeStatement();
        private final FakeStatement plainStatement = new FakeStatement();

        private FakeConnection(boolean failRollback) {
            this.failRollback = failRollback;
        }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, this);
        }

        List<String> terminalEvents() {
            return events.stream()
                    .filter(event -> event.equals("commit") || event.equals("rollback")
                            || event.equals("close"))
                    .toList();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "setReadOnly" -> {
                    readOnly = (boolean) args[0];
                    yield null;
                }
                case "setAutoCommit" -> {
                    autoCommit = (boolean) args[0];
                    yield null;
                }
                case "getAutoCommit" -> autoCommit;
                case "setNetworkTimeout" -> {
                    networkTimeout = (int) args[1];
                    yield null;
                }
                case "rollback" -> {
                    events.add("rollback");
                    if (failRollback) {
                        throw new SQLException("sensitive rollback failure");
                    }
                    yield null;
                }
                case "commit" -> {
                    events.add("commit");
                    yield null;
                }
                case "close" -> {
                    events.add("close");
                    yield null;
                }
                case "prepareStatement" -> preparedStatement.preparedProxy();
                case "prepareCall" -> callableStatement.callableProxy();
                case "createStatement" -> plainStatement.statementProxy();
                case "isClosed" -> events.contains("close");
                case "toString" -> "FakeConnection";
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class FakeStatement implements InvocationHandler {

        private int queryTimeout;

        PreparedStatement preparedProxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, this);
        }

        CallableStatement callableProxy() {
            return (CallableStatement) Proxy.newProxyInstance(
                    CallableStatement.class.getClassLoader(),
                    new Class<?>[] {CallableStatement.class}, this);
        }

        Statement statementProxy() {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] {Statement.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("setQueryTimeout")) {
                queryTimeout = (int) args[0];
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }
}

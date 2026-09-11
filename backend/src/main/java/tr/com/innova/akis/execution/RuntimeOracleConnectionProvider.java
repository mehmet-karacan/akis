package tr.com.innova.akis.execution;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.RuntimeOracleConnectionException.Failure;
import tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;

/**
 * Opens fresh, purpose-specific Oracle sessions for the bounded pilot.
 * Credentials exist only while a connection is being opened and are cleared on
 * every path. This component never logs or retains JDBC exception details.
 */
@Component
final class RuntimeOracleConnectionProvider {

    private static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";
    private static final Pattern HOST = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,199}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,127}");
    private static final Set<String> TLS_MODES = Set.of("DISABLED", "REQUIRED");
    private static final Set<String> CREDENTIAL_FIELDS = Set.of("username", "password");

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_NETWORK_TIMEOUT_MS = 35_000;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final RuntimeOracleConnectionMetadataPort metadata;
    private final ObjectMapper objectMapper;
    private final Function<String, String> environment;
    private final ConnectionOpener opener;
    private final Executor networkTimeoutExecutor;

    @Autowired
    RuntimeOracleConnectionProvider(
            RuntimeOracleConnectionMetadataPort metadata, ObjectMapper objectMapper) {
        this(metadata, objectMapper, System::getenv, DriverManager::getConnection,
                Runnable::run);
    }

    RuntimeOracleConnectionProvider(
            RuntimeOracleConnectionMetadataPort metadata,
            ObjectMapper objectMapper,
            Function<String, String> environment,
            ConnectionOpener opener,
            Executor networkTimeoutExecutor) {
        this.metadata = metadata;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.opener = opener;
        this.networkTimeoutExecutor = networkTimeoutExecutor;
    }

    RuntimeOracleSession openSource(DatasetBinding binding) {
        requireRole(binding, DatasetRole.SOURCE);
        return open(binding, SessionPurpose.SOURCE_READ);
    }

    RuntimeOracleSession openTargetFence(DatasetBinding binding) {
        requireRole(binding, DatasetRole.TARGET);
        return open(binding, SessionPurpose.TARGET_FENCE);
    }

    RuntimeOracleSession openTargetIdentityRead(DatasetBinding binding) {
        requireRole(binding, DatasetRole.TARGET);
        return open(binding, SessionPurpose.TARGET_IDENTITY_READ);
    }

    RuntimeOracleSession openTargetData(
            DatasetBinding binding, TargetDataPermit permit) {
        if (!(permit instanceof OracleAtomicPublishFacade.PublishPermit)) {
            throw failure(Failure.INVALID_CONTRACT);
        }
        requireRole(binding, DatasetRole.TARGET);
        return open(binding, SessionPurpose.TARGET_DATA);
    }

    RuntimeOracleSession openTargetReconciliation(DatasetBinding binding) {
        requireRole(binding, DatasetRole.TARGET);
        return open(binding, SessionPurpose.TARGET_RECONCILIATION);
    }

    private RuntimeOracleSession open(DatasetBinding binding, SessionPurpose purpose) {
        ConnectionProfile profile;
        try {
            profile = metadata.find(binding)
                    .orElseThrow(() -> failure(Failure.METADATA_NOT_FOUND));
        }
        catch (RuntimeOracleConnectionException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure(Failure.METADATA_NOT_FOUND);
        }
        validateProfile(binding, profile);
        TimeoutPolicy timeouts = timeoutPolicy(profile.policy());
        Credentials credentials = credentials(profile);
        Properties properties = new Properties();
        Connection connection = null;
        try {
            Class.forName(profile.driverReference());
            properties.setProperty("user", credentials.username());
            properties.setProperty("password", new String(credentials.password()));
            properties.setProperty("oracle.net.CONNECT_TIMEOUT",
                    Integer.toString(timeouts.connectTimeoutMs()));
            properties.setProperty("oracle.jdbc.ReadTimeout",
                    Integer.toString(timeouts.readTimeoutMs()));
            properties.setProperty("oracle.net.keepAlive", "true");
            connection = opener.open(jdbcUrl(profile), properties);
            connection.setNetworkTimeout(
                    networkTimeoutExecutor, timeouts.networkTimeoutMs());
            if (purpose.readOnly()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(true);
            }
            else {
                connection.setReadOnly(false);
                connection.setAutoCommit(false);
            }
            return new RuntimeOracleSession(
                    connection, purpose, timeouts.queryTimeoutSeconds());
        }
        catch (ClassNotFoundException | LinkageError | SQLException | RuntimeException exception) {
            closeAfterFailedOpen(connection, purpose);
            throw failure(Failure.CONNECTION_FAILED);
        }
        finally {
            credentials.close();
            properties.clear();
        }
    }

    private void validateProfile(DatasetBinding binding, ConnectionProfile profile) {
        if ("JNDI".equals(profile.mode())) {
            // A local name can be rebound by the application server. Execution remains
            // fail-closed until the resolved Oracle target has an immutable fingerprint.
            throw failure(Failure.UNSUPPORTED_PROFILE);
        }
        boolean hasService = validDatabaseName(profile.serviceName());
        boolean hasSid = validDatabaseName(profile.sid());
        if (!"JDBC".equals(profile.mode())
                || !binding.connectionVersionUuid().equals(profile.connectionVersionUuid())
                || !ORACLE_DRIVER.equals(profile.driverReference())
                || !validHost(profile.host())
                || profile.port() < 1 || profile.port() > 65_535
                || hasService == hasSid
                || !TLS_MODES.contains(profile.tlsMode())
                || profile.policy() == null || !profile.policy().isObject()
                || !"ENV".equals(profile.secretProvider())
                || profile.secretReferencePath() == null
                || !ENVIRONMENT_NAME.matcher(profile.secretReferencePath()).matches()) {
            throw failure(Failure.UNSUPPORTED_PROFILE);
        }
    }

    private Credentials credentials(ConnectionProfile profile) {
        String raw;
        try {
            raw = environment.apply(profile.secretReferencePath());
        }
        catch (RuntimeException exception) {
            throw failure(Failure.CREDENTIAL_UNAVAILABLE);
        }
        if (raw == null || raw.isBlank()) {
            throw failure(Failure.CREDENTIAL_UNAVAILABLE);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root == null || !root.isObject()
                    || !root.propertyNames().stream().allMatch(CREDENTIAL_FIELDS::contains)) {
                throw failure(Failure.CREDENTIAL_UNAVAILABLE);
            }
            String username = requiredText(root, "username");
            String password = requiredText(root, "password");
            if (!USERNAME.matcher(username).matches()) {
                throw failure(Failure.CREDENTIAL_UNAVAILABLE);
            }
            return new Credentials(username, password.toCharArray());
        }
        catch (RuntimeOracleConnectionException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure(Failure.CREDENTIAL_UNAVAILABLE);
        }
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw failure(Failure.CREDENTIAL_UNAVAILABLE);
        }
        return node.stringValue();
    }

    private TimeoutPolicy timeoutPolicy(JsonNode policy) {
        if (policy == null || !policy.isObject()) {
            throw failure(Failure.UNSUPPORTED_PROFILE);
        }
        Set<String> allowed = Set.of(
                "connectTimeoutMs", "readTimeoutMs", "networkTimeoutMs",
                "queryTimeoutSeconds", "purpose");
        if (!policy.propertyNames().stream().allMatch(allowed::contains)) {
            throw failure(Failure.UNSUPPORTED_PROFILE);
        }
        return new TimeoutPolicy(
                integer(policy, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS,
                        1_000, 120_000),
                integer(policy, "readTimeoutMs", DEFAULT_READ_TIMEOUT_MS,
                        1_000, 300_000),
                integer(policy, "networkTimeoutMs", DEFAULT_NETWORK_TIMEOUT_MS,
                        1_000, 300_000),
                integer(policy, "queryTimeoutSeconds", DEFAULT_QUERY_TIMEOUT_SECONDS,
                        1, 300));
    }

    private int integer(
            JsonNode policy, String field, int defaultValue, int minimum, int maximum) {
        if (!policy.has(field)) {
            return defaultValue;
        }
        JsonNode node = policy.get(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw failure(Failure.UNSUPPORTED_PROFILE);
        }
        int value = node.intValue();
        if (value < minimum || value > maximum) {
            throw failure(Failure.UNSUPPORTED_PROFILE);
        }
        return value;
    }

    private String jdbcUrl(ConnectionProfile profile) {
        String protocol = "DISABLED".equals(profile.tlsMode()) ? "TCP" : "TCPS";
        String connectData = profile.serviceName() != null
                ? "SERVICE_NAME=" + profile.serviceName()
                : "SID=" + profile.sid();
        return "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=" + protocol
                + ")(HOST=" + profile.host() + ")(PORT=" + profile.port()
                + "))(CONNECT_DATA=(" + connectData + ")))";
    }

    private void requireRole(DatasetBinding binding, DatasetRole role) {
        if (binding == null || binding.role() != role
                || binding.connectionVersionUuid() == null) {
            throw failure(Failure.INVALID_CONTRACT);
        }
    }

    private boolean validHost(String host) {
        return host != null && HOST.matcher(host).matches()
                && !host.contains("..") && !host.startsWith(".") && !host.endsWith(".");
    }

    private boolean validDatabaseName(String value) {
        return value != null && DATABASE_NAME.matcher(value).matches();
    }

    private void closeAfterFailedOpen(Connection connection, SessionPurpose purpose) {
        if (connection == null) {
            return;
        }
        try {
            if (!purpose.readOnly() && !connection.getAutoCommit()) {
                connection.rollback();
            }
        }
        catch (SQLException | RuntimeException ignored) {
            // The public failure remains sanitized and the close is still attempted.
        }
        try {
            connection.close();
        }
        catch (SQLException ignored) {
            // Opening already failed; preserve only the sanitized failure category.
        }
    }

    private RuntimeOracleConnectionException failure(Failure failure) {
        return new RuntimeOracleConnectionException(failure);
    }

    enum SessionPurpose {
        SOURCE_READ(true),
        TARGET_IDENTITY_READ(true),
        TARGET_FENCE(false),
        TARGET_DATA(false),
        TARGET_RECONCILIATION(false);

        private final boolean readOnly;

        SessionPurpose(boolean readOnly) {
            this.readOnly = readOnly;
        }

        boolean readOnly() {
            return readOnly;
        }
    }

    /** Compile-time capability; only the atomic facade can construct its permit. */
    sealed interface TargetDataPermit
            permits OracleAtomicPublishFacade.PublishPermit {
    }

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(String url, Properties properties) throws SQLException;
    }

    private record TimeoutPolicy(
            int connectTimeoutMs,
            int readTimeoutMs,
            int networkTimeoutMs,
            int queryTimeoutSeconds) {
    }

    private record Credentials(String username, char[] password) implements AutoCloseable {

        @Override
        public void close() {
            Arrays.fill(password, '\0');
        }
    }

    static final class RuntimeOracleSession implements AutoCloseable {

        private final Connection connection;
        private final Connection guardedConnection;
        private final SessionPurpose purpose;
        private final int queryTimeoutSeconds;
        private boolean transactionResolved;
        private boolean closed;

        private RuntimeOracleSession(
                Connection connection, SessionPurpose purpose, int queryTimeoutSeconds) {
            this.connection = connection;
            this.guardedConnection = ConnectionGuard.wrap(
                    connection, queryTimeoutSeconds);
            this.purpose = purpose;
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            this.transactionResolved = purpose.readOnly();
        }

        Connection connection() {
            ensureOpen();
            return guardedConnection;
        }

        SessionPurpose purpose() {
            return purpose;
        }

        <T extends Statement> T applyQueryTimeout(T statement) {
            ensureOpen();
            if (statement == null) {
                throw new RuntimeOracleConnectionException(Failure.INVALID_CONTRACT);
            }
            try {
                statement.setQueryTimeout(queryTimeoutSeconds);
                return statement;
            }
            catch (SQLException | RuntimeException exception) {
                throw new RuntimeOracleConnectionException(Failure.SESSION_OPERATION_FAILED);
            }
        }

        void commitConfirmed() {
            requireTarget();
            try {
                connection.commit();
                transactionResolved = true;
            }
            catch (SQLException | RuntimeException exception) {
                throw new RuntimeOracleConnectionException(Failure.SESSION_OPERATION_FAILED);
            }
        }

        void rollbackConfirmed() {
            requireTarget();
            try {
                connection.rollback();
                transactionResolved = true;
            }
            catch (SQLException | RuntimeException exception) {
                throw new RuntimeOracleConnectionException(Failure.ROLLBACK_NOT_CONFIRMED);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            RuntimeOracleConnectionException failure = null;
            if (!transactionResolved) {
                try {
                    connection.rollback();
                    transactionResolved = true;
                }
                catch (SQLException | RuntimeException exception) {
                    failure = new RuntimeOracleConnectionException(
                            Failure.ROLLBACK_NOT_CONFIRMED);
                }
            }
            try {
                connection.close();
            }
            catch (SQLException | RuntimeException exception) {
                if (failure == null) {
                    failure = new RuntimeOracleConnectionException(
                            Failure.SESSION_OPERATION_FAILED);
                }
            }
            finally {
                closed = true;
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void requireTarget() {
            ensureOpen();
            if (purpose.readOnly() || transactionResolved) {
                throw new RuntimeOracleConnectionException(Failure.INVALID_CONTRACT);
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new RuntimeOracleConnectionException(Failure.INVALID_CONTRACT);
            }
        }
    }

    private static final class ConnectionGuard implements java.lang.reflect.InvocationHandler {

        private static final Set<String> SESSION_CONTROL_METHODS = Set.of(
                "commit", "rollback", "close", "setAutoCommit", "setReadOnly",
                "setNetworkTimeout", "abort", "unwrap");
        private static final Set<String> STATEMENT_FACTORY_METHODS = Set.of(
                "prepareStatement", "prepareCall", "createStatement");

        private final Connection delegate;
        private final int queryTimeoutSeconds;

        private ConnectionGuard(Connection delegate, int queryTimeoutSeconds) {
            this.delegate = delegate;
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        private static Connection wrap(Connection delegate, int queryTimeoutSeconds) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    new ConnectionGuard(delegate, queryTimeoutSeconds));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (name.equals("isWrapperFor")) {
                return false;
            }
            if (SESSION_CONTROL_METHODS.contains(name)) {
                throw new SQLException("Session lifecycle is managed by the runtime.");
            }
            try {
                Object result = method.invoke(delegate, arguments);
                if (STATEMENT_FACTORY_METHODS.contains(name) && result instanceof Statement statement) {
                    statement.setQueryTimeout(queryTimeoutSeconds);
                }
                return result;
            }
            catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}

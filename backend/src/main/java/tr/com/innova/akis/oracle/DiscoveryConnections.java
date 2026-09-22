package tr.com.innova.akis.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;

/**
 * Opens the read-only metadata session a discovery adapter works on. Shared by every technology adapter: the profile
 * decides the JDBC URL and driver timeouts, credentials never outlive the call, and JNDI resources are looked up once.
 */
public final class DiscoveryConnections {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private DiscoveryConnections() { }

    public static Connection open(ConnectionProfile profile, Credentials credentials) throws SQLException {
        if ("JNDI".equals(profile.mode())) {
            return openJndi(profile);
        }
        loadDriver(profile.driverReference());
        Properties properties = new Properties();
        properties.setProperty("user", credentials.username());
        properties.setProperty("password", new String(credentials.password()));
        if ("POSTGRESQL".equals(profile.databaseType())) {
            properties.setProperty("loginTimeout", Integer.toString(Math.max(
                    1, policyInteger(profile, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS, 1_000, 120_000) / 1000)));
            properties.setProperty("socketTimeout", Integer.toString(
                    policyInteger(profile, "readTimeoutMs", DEFAULT_READ_TIMEOUT_MS, 1_000, 300_000) / 1000));
        }
        else {
            properties.setProperty(
                    "oracle.net.CONNECT_TIMEOUT",
                    Integer.toString(policyInteger(
                            profile, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS, 1_000, 120_000)));
            properties.setProperty(
                    "oracle.jdbc.ReadTimeout",
                    Integer.toString(policyInteger(
                            profile, "readTimeoutMs", DEFAULT_READ_TIMEOUT_MS, 1_000, 300_000)));
        }
        Connection connection;
        try {
            connection = DriverManager.getConnection(jdbcUrl(profile), properties);
        }
        finally {
            properties.clear();
        }
        try {
            connection.setReadOnly(true);
            return connection;
        }
        catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private static Connection openJndi(ConnectionProfile profile) {
        InitialContext context = null;
        Connection connection = null;
        try {
            context = new InitialContext();
            Object resource = context.lookup(profile.jndiName());
            if (!(resource instanceof DataSource dataSource)) {
                throw jndiUnavailable();
            }
            connection = dataSource.getConnection();
            connection.setReadOnly(true);
            return connection;
        }
        catch (NamingException | SQLException | RuntimeException exception) {
            if (connection != null) {
                try {
                    connection.close();
                }
                catch (SQLException ignored) {
                    // Preserve only the sanitized JNDI failure.
                }
            }
            throw jndiUnavailable();
        }
        finally {
            if (context != null) {
                try {
                    context.close();
                }
                catch (NamingException ignored) {
                    // The DataSource lookup result no longer depends on this context.
                }
            }
        }
    }

    private static ApiException jndiUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ORACLE_JNDI_RESOURCE_UNAVAILABLE",
                "JNDI DataSource bu çalışma ortamında kullanılamıyor.");
    }

    private static void loadDriver(String driverReference) {
        try {
            Class.forName(driverReference);
        }
        catch (ClassNotFoundException | LinkageError exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORACLE_DRIVER_UNAVAILABLE",
                    "JDBC sürücüsü çalışma zamanında bulunamadı.");
        }
    }

    static String jdbcUrl(ConnectionProfile profile) {
        if ("POSTGRESQL".equals(profile.databaseType())) {
            String sslMode = "REQUIRED".equals(profile.tlsMode()) ? "require" : "disable";
            return "jdbc:postgresql://" + profile.host() + ":" + profile.port()
                    + "/" + profile.serviceName() + "?sslmode=" + sslMode;
        }
        String protocol = "DISABLED".equals(profile.tlsMode()) ? "tcp" : "tcps";
        if (profile.serviceName() != null) {
            return "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=" + protocol
                    + ")(HOST=" + profile.host() + ")(PORT=" + profile.port()
                    + "))(CONNECT_DATA=(SERVICE_NAME=" + profile.serviceName() + ")))";
        }
        return "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=" + protocol
                + ")(HOST=" + profile.host() + ")(PORT=" + profile.port()
                + "))(CONNECT_DATA=(SID=" + profile.sid() + ")))";
    }

    private static int policyInteger(
            ConnectionProfile profile,
            String name,
            int defaultValue,
            int minimum,
            int maximum) {
        if (profile.policy() == null || !profile.policy().has(name)) {
            return defaultValue;
        }
        int value = profile.policy().path(name).asInt(-1);
        if (value < minimum || value > maximum) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORACLE_POLICY_INVALID",
                    "Bağlantı timeout politikası geçersiz.");
        }
        return value;
    }

    public static ApiException connectionFailed() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "ORACLE_CONNECTION_FAILED",
                "Bağlantı kurulamadı veya doğrulanamadı.");
    }

    public static ApiException discoveryFailed() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "ORACLE_DISCOVERY_FAILED",
                "Şema metadata keşfi tamamlanamadı.");
    }
}

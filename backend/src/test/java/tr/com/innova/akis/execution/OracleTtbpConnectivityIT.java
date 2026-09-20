package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Read-only TTBP connectivity probe; no procedure, DML or schema mutation. */
class OracleTtbpConnectivityIT {
    @Test
    void reachesTtbpWithConfiguredRuntimeCredential() throws Exception {
        String url = System.getenv("AKIS_ORACLE_SOURCE_URL");
        String user = System.getenv("AKIS_ORACLE_SOURCE_USERNAME");
        String password = System.getenv("AKIS_ORACLE_SOURCE_PASSWORD");
        assumeTrue(url != null && user != null && password != null,
                "TTBP Oracle fixture is not configured");
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "12000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "34000");
        try (var connection = DriverManager.getConnection(url, properties);
             var statement = connection.createStatement();
             var result = statement.executeQuery("select 1 from dual")) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}

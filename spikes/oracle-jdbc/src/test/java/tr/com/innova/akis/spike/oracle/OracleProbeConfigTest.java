package tr.com.innova.akis.spike.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.spike.oracle.OracleJdbcSpikeApplication.OracleProbeConfig;

class OracleProbeConfigTest {

    @Test
    void readsTwoEndpointsWithoutExposingSecrets() {
        Map<String, String> environment = validEnvironment();

        OracleProbeConfig config = OracleProbeConfig.fromEnvironment(environment);

        assertEquals(19, config.expectedMajor());
        assertEquals(100, config.batchSize());
        assertEquals(false, config.copyEnabled());
        assertEquals("source_user", config.source().username());
        assertEquals("target_user", config.target().username());
        assertEquals("SOURCE_OWNER", config.tableCopyRequest().sourceOwner());
        assertEquals("STG_SOURCE_TABLE", config.tableCopyRequest().targetTable());
    }

    @Test
    void rejectsMissingTargetPassword() {
        Map<String, String> environment = validEnvironment();
        environment.remove("AKIS_ORACLE_TARGET_PASSWORD");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> OracleProbeConfig.fromEnvironment(environment));

        assertEquals(
                "Missing required local environment value: AKIS_ORACLE_TARGET_PASSWORD",
                exception.getMessage());
    }

    @Test
    void rejectsUnsafeOracleIdentifier() {
        Map<String, String> environment = validEnvironment();
        environment.put("AKIS_ORACLE_TARGET_TABLE", "TARGET_TABLE; DROP TABLE X");

        assertThrows(
                IllegalArgumentException.class,
                () -> OracleProbeConfig.fromEnvironment(environment));
    }

    @Test
    void rejectsInvalidCopyFlag() {
        Map<String, String> environment = validEnvironment();
        environment.put("AKIS_ORACLE_COPY_ENABLED", "yes");

        assertThrows(
                IllegalArgumentException.class,
                () -> OracleProbeConfig.fromEnvironment(environment));
    }

    private Map<String, String> validEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("AKIS_ORACLE_EXPECTED_MAJOR", "19");
        environment.put("AKIS_ORACLE_CONNECT_TIMEOUT_MS", "10000");
        environment.put("AKIS_ORACLE_READ_TIMEOUT_MS", "30000");
        environment.put("AKIS_ORACLE_BATCH_SIZE", "100");
        environment.put("AKIS_ORACLE_COPY_ENABLED", "false");
        environment.put("AKIS_ORACLE_SOURCE_URL", "jdbc:oracle:thin:@//source:1521/service");
        environment.put("AKIS_ORACLE_SOURCE_USERNAME", "source_user");
        environment.put("AKIS_ORACLE_SOURCE_PASSWORD", "source_password");
        environment.put("AKIS_ORACLE_TARGET_URL", "jdbc:oracle:thin:@//target:1521/service");
        environment.put("AKIS_ORACLE_TARGET_USERNAME", "target_user");
        environment.put("AKIS_ORACLE_TARGET_PASSWORD", "target_password");
        environment.put("AKIS_ORACLE_SOURCE_OWNER", "SOURCE_OWNER");
        environment.put("AKIS_ORACLE_SOURCE_TABLE", "SOURCE_TABLE");
        environment.put("AKIS_ORACLE_TARGET_OWNER", "TARGET_OWNER");
        environment.put("AKIS_ORACLE_TARGET_TABLE", "STG_SOURCE_TABLE");
        return environment;
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.TargetLedgerPort.BatchEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.BatchPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.DataLedgerSession;
import tr.com.innova.akis.execution.TargetLedgerPort.FenceSession;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.ReconciliationSession;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;

class OracleTargetLedgerIT {

    private static final String URL_ENV = "AKIS_ORACLE_TARGET_URL";
    private static final String USER_ENV = "AKIS_ORACLE_TARGET_USERNAME";
    private static final String PASSWORD_ENV = "AKIS_ORACLE_TARGET_PASSWORD";
    private static final String OWNER_ENV = "AKIS_ORACLE_TARGET_OWNER";

    @Test
    void realOracleTargetIdentityUsesVerifiedDatabaseContainerAndPilotTable() throws Exception {
        String url = environment(URL_ENV);
        String username = environment(USER_ENV);
        String password = environment(PASSWORD_ENV);
        String owner = environment(OWNER_ENV);
        Assumptions.assumeTrue(
                url != null && username != null && password != null && owner != null,
                "Oracle target identity integration environment is not configured.");

        Class.forName("oracle.jdbc.OracleDriver");
        Properties properties = connectionProperties(username, password);
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            var identity = new JdbcOracleTargetIdentityReader().read(
                    connection,
                    owner.trim().toUpperCase(Locale.ROOT),
                    "TABLE",
                    "STG_HAKEDIS_TIPI");

            assertEquals(1, identity.targetIdentityVersion());
            assertEquals(owner.trim().toUpperCase(Locale.ROOT), identity.owner());
            assertEquals("STG_HAKEDIS_TIPI", identity.objectName());
            assertEquals(64, identity.canonicalTargetHash().length());
            assertFalse(identity.site().isBlank());
            assertFalse(identity.container().isBlank());
        }
        finally {
            properties.clear();
        }
    }

    @Test
    void realOracleFenceBatchEvidenceAndRollbackGuardsFailClosed() throws Exception {
        String url = environment(URL_ENV);
        String username = environment(USER_ENV);
        String password = environment(PASSWORD_ENV);
        Assumptions.assumeTrue(
                url != null && username != null && password != null,
                "Oracle ledger integration environment is not configured.");

        Class.forName("oracle.jdbc.OracleDriver");
        Properties properties = connectionProperties(username, password);
        try (Connection writer = DriverManager.getConnection(url, properties);
                Connection reconciliationConnection =
                        DriverManager.getConnection(url, properties)) {
            writer.setAutoCommit(false);
            reconciliationConnection.setAutoCommit(false);
            String targetHash = sha256("akis-ledger-it-target-" + UUID.randomUUID());
            TargetLedgerContext context = context(targetHash, 101);
            JdbcOracleTargetLedgerAdapter adapter = new JdbcOracleTargetLedgerAdapter();
            BatchEvidence evidence = new BatchEvidence(
                    "IT_BATCH", "P0", sha256("batch-" + targetHash), 0,
                    sha256("payload-" + targetHash), 4, 128);
            DataLedgerSession preCommitData = adapter.bindData(writer, context);
            FenceSession fenceSession = adapter.bindFence(writer, context);
            ReconciliationSession reconciliation = adapter.bindReconciliation(
                    reconciliationConnection, context);
            try {
                fenceSession.acquireFence();
                assertTrue(reconciliation.readFence().isEmpty());
                OracleLedgerTransactionException dirtyTransaction = assertThrows(
                        OracleLedgerTransactionException.class,
                        () -> preCommitData.prepareBatch(evidence));
                assertEquals(20026, dirtyTransaction.oracleErrorCode());
                writer.commit();

                DataLedgerSession session = adapter.bindData(writer, context);
                var fence = reconciliation.readFence().orElseThrow();
                assertEquals(context.runUuid(), fence.runUuid());
                assertEquals(context.fenceToken(), fence.fenceToken());

                BatchPreparation preparation = session.prepareBatch(evidence);
                assertFalse(preparation.alreadyRecorded());
                session.recordBatch(preparation);
                assertTrue(reconciliation.verifyBatch(evidence).isEmpty());
                writer.rollback();
                assertTrue(reconciliation.verifyBatch(evidence).isEmpty());

                PublishEvidence publish = new PublishEvidence(
                        "IT_PUBLISH", sha256("publish-" + targetHash),
                        evidence.payloadHash(), evidence.rowCount(),
                        evidence.rowCount(), 0, null, null);
                PublishPreparation publishPreparation = session.preparePublish(publish);
                assertFalse(publishPreparation.alreadyRecorded());
                session.recordPublish(publishPreparation);
                assertTrue(reconciliation.verifyPublish(publish).isEmpty());
                writer.rollback();

                assertTrue(reconciliation.verifyPublish(publish).isEmpty());

                BatchPreparation rolledBackPreparation = session.prepareBatch(evidence);
                writer.rollback();
                OracleLedgerTransactionException guardError = assertThrows(
                        OracleLedgerTransactionException.class,
                        () -> session.recordBatch(rolledBackPreparation));
                assertEquals(20018, guardError.oracleErrorCode());
                writer.rollback();

                FenceSession stale = adapter.bindFence(
                        writer, context(targetHash, context.fenceToken() - 1));
                OracleFenceRejectedException staleError = assertThrows(
                        OracleFenceRejectedException.class, stale::acquireFence);
                assertEquals(OracleLedgerFailure.STALE_FENCE_TOKEN, staleError.failure());
                writer.rollback();
            }
            finally {
                writer.rollback();
                deleteFence(writer, targetHash);
                writer.commit();
            }
        }
        finally {
            properties.clear();
        }
    }

    private TargetLedgerContext context(String targetHash, long token) {
        return new TargetLedgerContext(
                targetHash,
                token,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                sha256("release-" + targetHash),
                sha256("plan-" + targetHash));
    }

    private void deleteFence(Connection connection, String targetHash) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM ETL_YUKLEME_KILIDI WHERE TARGET_KEY_HASH = ?")) {
            statement.setString(1, targetHash);
            statement.executeUpdate();
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", exception);
        }
    }

    private String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private Properties connectionProperties(String username, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "30000");
        return properties;
    }
}

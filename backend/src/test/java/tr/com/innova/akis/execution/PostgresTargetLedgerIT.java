package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.PublishPreparation;
import tr.com.innova.akis.execution.TargetLedgerPort.RecordedEvidence;
import tr.com.innova.akis.execution.TargetLedgerPort.TargetLedgerContext;

/**
 * Real PostgreSQL ledger acceptance (database/postgres-target installed on the fixture database); skipped unless the
 * runtime fixture is configured. Exercises fence, single-transaction prepare/record, reconciliation and the identity reader.
 */
class PostgresTargetLedgerIT {

    private static final String TARGET_SCHEMA = "akis";

    @Test
    void fencePublishAndReconcileOnRealPostgres() throws Exception {
        String url = System.getenv("AKIS_POSTGRES_RUNTIME_URL");
        String username = System.getenv("AKIS_POSTGRES_RUNTIME_USERNAME");
        String password = System.getenv("AKIS_POSTGRES_RUNTIME_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "PostgreSQL runtime fixture is not configured");
        JdbcPostgresTargetLedgerAdapter ledger = new JdbcPostgresTargetLedgerAdapter();
        String table = "akis_it_" + Long.toHexString(System.nanoTime());
        try (Connection writer = DriverManager.getConnection(url, username, password);
                Connection reader = DriverManager.getConnection(url, username, password)) {
            writer.setAutoCommit(false);
            reader.setAutoCommit(false);
            try (Statement ddl = writer.createStatement()) {
                ddl.execute("create table " + TARGET_SCHEMA + "." + table + "(id bigint primary key, ad text)");
            }
            writer.commit();
            var identity = new JdbcPostgresTargetIdentityReader().read(writer, TARGET_SCHEMA, "TABLE", table);
            assertEquals(64, identity.canonicalTargetHash().length());
            assertEquals(table, identity.objectName());
            assertTrue(identity.container().matches("[0-9a-f-]{36}"), "installation uuid is the container");

            TargetLedgerContext context = new TargetLedgerContext(identity.canonicalTargetHash(), 7L, UUID.randomUUID(), UUID.randomUUID(), 1, hash("release"), hash("plan"));
            ledger.bindFence(writer, context).acquireFence();
            writer.commit();

            // Stale token from another run is rejected before any data work.
            TargetLedgerContext stale = new TargetLedgerContext(identity.canonicalTargetHash(), 6L, UUID.randomUUID(), UUID.randomUUID(), 1, hash("release"), hash("plan"));
            OracleTargetLedgerException rejected = assertThrows(OracleTargetLedgerException.class, () -> ledger.bindFence(writer, stale).acquireFence());
            assertEquals(OracleLedgerFailure.STALE_FENCE_TOKEN, rejected.failure());
            writer.rollback();

            // Faz A publish: prepare, truncate+insert and record in ONE transaction.
            PublishEvidence evidence = new PublishEvidence("HEDEFE_YAZ", hash("publish-key"), hash("stage"), 3, 3, 0, null, null);
            var data = ledger.bindData(writer, context);
            PublishPreparation preparation = data.preparePublish(evidence);
            assertFalse(preparation.alreadyRecorded());
            try (Statement dml = writer.createStatement()) {
                dml.execute("truncate table only " + TARGET_SCHEMA + "." + table);
                dml.execute("insert into " + TARGET_SCHEMA + "." + table + " values (1,'a'),(2,'b'),(3,'c')");
            }
            data.recordPublish(preparation);
            // Not visible to a fresh connection before commit.
            assertTrue(ledger.bindReconciliation(reader, context).verifyPublish(evidence).isEmpty());
            reader.rollback();
            writer.commit();

            Optional<RecordedEvidence> recorded = ledger.bindReconciliation(reader, context).verifyPublish(evidence);
            assertTrue(recorded.isPresent());
            assertEquals(context.runUuid(), recorded.get().runUuid());
            assertEquals(7L, recorded.get().fenceToken());
            assertEquals(7L, ledger.bindReconciliation(reader, context).readFence().orElseThrow().fenceToken());
            reader.rollback();

            // Same key with different evidence is a conflict, and a second prepare of the exact evidence is idempotent.
            PublishEvidence conflicting = new PublishEvidence("HEDEFE_YAZ", hash("publish-key"), hash("stage"), 4, 4, 0, null, null);
            OracleTargetLedgerException conflict = assertThrows(OracleTargetLedgerException.class, () -> ledger.bindReconciliation(reader, context).verifyPublish(conflicting));
            assertEquals(OracleLedgerFailure.PUBLISH_EVIDENCE_CONFLICT, conflict.failure());
            reader.rollback();
            assertTrue(ledger.bindData(writer, context).preparePublish(evidence).alreadyRecorded());
            writer.rollback();

            // Rollback after prepare leaves no evidence and the guard cannot be replayed in a new transaction.
            PublishEvidence second = new PublishEvidence("ADIM_2", hash("key-2"), hash("stage-2"), 1, 1, 0, null, null);
            var session = ledger.bindData(writer, context);
            PublishPreparation abandoned = session.preparePublish(second);
            writer.rollback();
            OracleTargetLedgerException replay = assertThrows(OracleTargetLedgerException.class, () -> session.recordPublish(abandoned));
            assertEquals(OracleLedgerFailure.TRANSACTION_PROTOCOL_REJECTED, replay.failure());
            writer.rollback();
            assertTrue(ledger.bindReconciliation(reader, context).verifyPublish(second).isEmpty());
            reader.rollback();

            try (Statement ddl = writer.createStatement()) {
                ddl.execute("drop table " + TARGET_SCHEMA + "." + table);
            }
            writer.commit();
        }
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}

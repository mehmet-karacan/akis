package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.execution.TargetLedgerPort.*;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;

/** Real PostgreSQL locks model the SHARE UPDATE EXCLUSIVE lock held by autoanalyze. */
class PostgresPublishLockIT {
    @Test
    void publishesAfterTransientAutoanalyzeLockIsReleased() throws Exception {
        exerciseLock(false);
    }

    @Test
    void rollsBackWithoutChangingTargetWhenStageRemainsLocked() throws Exception {
        exerciseLock(true);
    }

    private void exerciseLock(boolean keepLocked) throws Exception {
        String url = System.getenv("AKIS_POSTGRES_RUNTIME_URL");
        assumeTrue(url != null, "PostgreSQL fixture not configured");
        String user = System.getenv("AKIS_POSTGRES_RUNTIME_USERNAME");
        String password = System.getenv("AKIS_POSTGRES_RUNTIME_PASSWORD");
        String schema = "LOCK_IT_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Table stage = new Table(schema, "STAGE"), target = new Table(schema, "TARGET");
        try (Connection setup = DriverManager.getConnection(url, user, password);
             Connection blocker = DriverManager.getConnection(url, user, password);
             Connection writer = DriverManager.getConnection(url, user, password);
             var executor = Executors.newSingleThreadExecutor()) {
            try (var sql = setup.createStatement()) {
                sql.execute("CREATE SCHEMA \"" + schema + "\"");
                sql.execute("CREATE TABLE " + stage.sql() + " (\"ID\" bigint)");
                sql.execute("INSERT INTO " + stage.sql() + " VALUES (1),(2)");
                sql.execute("CREATE TABLE " + target.sql() + " (\"ID\" bigint)");
                sql.execute("INSERT INTO " + target.sql() + " VALUES (99)");
            }
            try {
                blocker.setAutoCommit(false);
                writer.setAutoCommit(false);
                try (var sql = blocker.createStatement()) {
                    sql.execute("LOCK TABLE " + stage.sql() + " IN SHARE UPDATE EXCLUSIVE MODE");
                }
                var ledger = mock(TargetLedgerPort.class);
                var data = mock(DataLedgerSession.class);
                when(ledger.bindData(any(), any())).thenReturn(data);
                var evidence = new PublishEvidence("HEDEFE_YAZ", "a".repeat(64), "b".repeat(64), 2, 2, 0, null, null);
                when(data.preparePublish(evidence)).thenReturn(new PublishPreparation(UUID.randomUUID(), evidence, "guard", false));
                var context = new TargetLedgerContext("c".repeat(64), 1, UUID.randomUUID(), UUID.randomUUID(), 1, "d".repeat(64), "e".repeat(64));
                var result = executor.submit(() -> new JdbcPostgresAtomicRefreshWriter(ledger).publish(
                        writer, context, evidence, stage, target,
                        List.of(new JdbcStagedAtomicRefreshWriter.Column("ID", "ID")),
                        keepLocked ? 1 : 5, () -> {}, () -> {},
                        new JdbcTransactionBoundary() {
                            public void commit() throws java.sql.SQLException { writer.commit(); }
                            public void rollback() throws java.sql.SQLException { writer.rollback(); }
                        }, JdbcStagedAtomicRefreshWriter.WriteMode.APPEND));
                if (!keepLocked) {
                    // Hold the conflicting lock through the first acquisition attempt.
                    Thread.sleep(500);
                    blocker.commit();
                }
                assertEquals(keepLocked ? JdbcStagedAtomicRefreshWriter.Outcome.ROLLED_BACK
                                       : JdbcStagedAtomicRefreshWriter.Outcome.COMMITTED,
                             result.get(10, TimeUnit.SECONDS).outcome());
                try (var sql = setup.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM " + target.sql())) {
                    assertTrue(rows.next());
                    assertEquals(keepLocked ? 1 : 3, rows.getLong(1));
                }
            } finally {
                blocker.rollback(); writer.rollback();
                try (var sql = setup.createStatement()) { sql.execute("DROP SCHEMA \"" + schema + "\" CASCADE"); }
            }
        }
    }
}

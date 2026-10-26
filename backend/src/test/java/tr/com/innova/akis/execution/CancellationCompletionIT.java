package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;

/** Explicit opt-in only; always uses a disposable container, never a configured application database. */
@EnabledIfEnvironmentVariable(named = "AKIS_CANCELLATION_DOCKER_IT", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
class CancellationCompletionIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    static JdbcTemplate jdbc;
    static JdbcRunLeaseStore store;
    static TransactionTemplate transactions;

    @BeforeAll
    static void schema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcRunLeaseStore(JdbcClient.create(dataSource));
        // Minimal fixture mirrors the columns and valid states used by V007/V009/V011/V020/V021/V036.
        jdbc.execute("""
                create schema akis;
                create table akis.calistirma(id bigserial primary key, proje_id bigint not null, uuid uuid unique not null);
                create table akis.hedef_kaynagi(id bigserial primary key, uuid uuid not null default gen_random_uuid(),
                    calistirma_id bigint, nesil_no bigint not null, durum text not null,
                    kiralama_bitis_zamani timestamptz, guncellenme_zamani timestamptz, versiyon_no bigint default 1,
                    check(durum in ('BOS','SAHIPLENILDI','ASKIDA')),
                    check((durum='SAHIPLENILDI' and calistirma_id is not null and kiralama_bitis_zamani is not null)
                       or (durum<>'SAHIPLENILDI' and calistirma_id is null and kiralama_bitis_zamani is null)));
                create table akis.calistirma_durumu(id bigserial primary key, proje_id bigint not null,
                    calistirma_id bigint unique not null references akis.calistirma(id), durum text not null,
                    nesil_no bigint not null, isleyici_referansi text, hedef_kaynagi_id bigint, hedef_nesil_no bigint,
                    kiralama_bitis_zamani timestamptz, bitis_zamani timestamptz, son_olay_no bigint default 1,
                    guncellenme_zamani timestamptz, versiyon_no bigint default 1);
                create table akis.calistirma_olayi(proje_id bigint, calistirma_id bigint references akis.calistirma(id), olay_no bigint,
                    tur text, olay_zamani timestamptz, veri jsonb, unique(calistirma_id,olay_no));
                create table akis.pilot_yayin_niyeti(proje_id bigint, calistirma_id bigint);
                create table akis.km_step_journal(proje_id bigint, calistirma_id bigint, generation bigint,
                    worker_reference text, ordinal int, operation text, state text check(state in
                    ('PENDING','RUNNING','SUCCEEDED','SKIPPED','FAILED','UNKNOWN')), error_code text,
                    started_at timestamptz, completed_at timestamptz);
                create table akis.prosedur_adim_durumu(proje_id bigint, calistirma_id bigint, calistirma_adimi_id bigint,
                    durum text check(durum in ('BEKLIYOR','CALISIYOR','BASARILI','HATA_DEVAM','BASARISIZ','SONUC_BELIRSIZ','ATLANDI')),
                    transaction_outcome text default 'NOT_ATTEMPTED', durable_commit_reference text,
                    baslama_zamani timestamptz, bitis_zamani timestamptz, hata_kodu text, son_olay_no bigint,
                    guncellenme_zamani timestamptz, versiyon_no bigint default 1);
                create table akis.prosedur_adim_niyeti(proje_id bigint, calistirma_id bigint, calistirma_adimi_id bigint,
                    calistirma_nesil_no bigint, isleyici_referansi text);
                create table akis.islem_grubu_kaniti(id bigserial primary key, proje_id bigint, calistirma_id bigint);
                create table akis.islem_grubu_durumu(islem_grubu_kaniti_id bigint, outcome text,
                    sonuc_zamani timestamptz, hata_kodu text, guncellenme_zamani timestamptz, versiyon_no bigint default 1);
                """);
        jdbc.execute(new ClassPathResource("db/akis/V070__calistirma_iptal_tamamlama.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void clearFixture() {
        jdbc.execute("""
                truncate akis.calistirma,akis.calistirma_durumu,akis.calistirma_olayi,akis.hedef_kaynagi,
                    akis.pilot_yayin_niyeti,akis.km_step_journal,akis.prosedur_adim_durumu,
                    akis.prosedur_adim_niyeti,akis.islem_grubu_kaniti,akis.islem_grubu_durumu restart identity cascade
                """);
    }

    @Test
    void expiredCancellationClosesCurrentStepsReleasesFenceAndAcknowledgesExactlyOnce() {
        RunLeaseToken token = fixture(true, false);
        jdbc.update("""
                insert into akis.prosedur_adim_durumu(proje_id,calistirma_id,calistirma_adimi_id,durum,baslama_zamani)
                values(1,1,1,'CALISIYOR',clock_timestamp())
                """);
        assertTrue(store.completeCancellation(token));
        assertTrue(store.completeCancellation(token));
        assertFalse(store.completeCancellation(new RunLeaseToken(token.runUuid(), "other-worker", 7, token.leaseDeadline())));
        assertFalse(store.completeCancellation(new RunLeaseToken(token.runUuid(), token.workerReference(), 6, token.leaseDeadline())));
        assertEquals("IPTAL", value("calistirma_durumu", "durum"));
        assertEquals("BOS", value("hedef_kaynagi", "durum"));
        assertEquals("FAILED", journal(7, "worker-cancel"));
        assertEquals("RUNNING", journal(6, "previous-worker"));
        assertEquals("BASARISIZ", value("prosedur_adim_durumu", "durum"));
        assertEquals(1, jdbc.queryForObject("select count(*) from akis.calistirma_olayi", Integer.class));
        assertTrue(jdbc.queryForObject("select bitis_zamani is not null and kiralama_bitis_zamani is null from akis.calistirma_durumu", Boolean.class));
        assertEquals("worker-cancel", jdbc.queryForObject("select veri->>'workerReference' from akis.calistirma_olayi", String.class));
        assertEquals("7", jdbc.queryForObject("select veri->>'generation' from akis.calistirma_olayi", String.class));
    }

    @Test
    void publishIntentAlwaysRequiresReconciliationAndQuarantinesOnlyMatchingFence() {
        RunLeaseToken token = fixture(true, true);
        jdbc.update("update akis.km_step_journal set operation='ATOMIC_REPLACE' where generation=7");
        assertTrue(store.completeCancellation(token));
        assertTrue(store.completeCancellation(token));
        assertEquals("SONUC_BELIRSIZ", value("calistirma_durumu", "durum"));
        assertEquals("ASKIDA", value("hedef_kaynagi", "durum"));
        assertEquals("UNKNOWN", journal(7, "worker-cancel"));
        assertEquals("true", jdbc.queryForObject("select veri->>'requiresReconciliation' from akis.calistirma_olayi", String.class));
    }

    @Test
    void uncertainPublishDoesNotQuarantineAnotherOwnersTarget() {
        RunLeaseToken token = fixture(true, true);
        jdbc.update("insert into akis.calistirma(proje_id,uuid) values(1,?)", UUID.randomUUID());
        jdbc.update("update akis.hedef_kaynagi set calistirma_id=2,nesil_no=12");
        assertTrue(store.completeCancellation(token));
        assertEquals("SONUC_BELIRSIZ", value("calistirma_durumu", "durum"));
        assertEquals("SAHIPLENILDI", value("hedef_kaynagi", "durum"));
        assertEquals(2L, jdbc.queryForObject("select calistirma_id from akis.hedef_kaynagi", Long.class));
    }

    @Test
    void unconfirmedProcedureWriteIntentCannotBeReportedAsCleanCancellation() {
        RunLeaseToken token = fixture(true, false);
        jdbc.update("insert into akis.prosedur_adim_durumu(proje_id,calistirma_id,calistirma_adimi_id,durum) values(1,1,1,'CALISIYOR')");
        jdbc.update("insert into akis.prosedur_adim_niyeti values(1,1,1,7,'worker-cancel')");
        assertTrue(store.completeCancellation(token));
        assertEquals("SONUC_BELIRSIZ", value("calistirma_durumu", "durum"));
        assertEquals("ASKIDA", value("hedef_kaynagi", "durum"));
        assertEquals("SONUC_BELIRSIZ", value("prosedur_adim_durumu", "durum"));
        assertEquals("OUTCOME_UNKNOWN", value("prosedur_adim_durumu", "transaction_outcome"));
    }

    @Test
    void unconfirmedTransactionGroupRemainsUnknownAfterCancellation() {
        RunLeaseToken token = fixture(true, false);
        jdbc.update("insert into akis.islem_grubu_kaniti(proje_id,calistirma_id) values(1,1)");
        jdbc.update("insert into akis.islem_grubu_durumu(islem_grubu_kaniti_id,outcome) values(1,'EXECUTED_UNCOMMITTED')");
        assertTrue(store.completeCancellation(token));
        assertEquals("SONUC_BELIRSIZ", value("calistirma_durumu", "durum"));
        assertEquals("OUTCOME_UNKNOWN", value("islem_grubu_durumu", "outcome"));
        assertTrue(jdbc.queryForObject("select sonuc_zamani is not null from akis.islem_grubu_durumu", Boolean.class));
    }

    @Test
    void acknowledgementDoesNotTouchReassignedTargetOrNewTargetGeneration() {
        RunLeaseToken token = fixture(true, false);
        jdbc.update("insert into akis.calistirma(proje_id,uuid) values(1,?)", UUID.randomUUID());
        jdbc.update("update akis.hedef_kaynagi set calistirma_id=2,nesil_no=12");
        assertTrue(store.completeCancellation(token));
        assertEquals("SAHIPLENILDI", value("hedef_kaynagi", "durum"));
        assertEquals(2L, jdbc.queryForObject("select calistirma_id from akis.hedef_kaynagi", Long.class));
        assertEquals(12L, jdbc.queryForObject("select nesil_no from akis.hedef_kaynagi", Long.class));
    }

    @Test
    void mismatchedTargetGenerationEvenWithSameRunIsNotReleased() {
        RunLeaseToken token = fixture(true, false);
        jdbc.update("update akis.hedef_kaynagi set nesil_no=12");
        assertTrue(store.completeCancellation(token));
        assertEquals("SAHIPLENILDI", value("hedef_kaynagi", "durum"));
    }

    @Test
    void staleOwnerGenerationUnknownRunAndNonCancellationAreRejectedWithoutMutations() {
        RunLeaseToken token = fixture(true, false);
        assertFalse(store.completeCancellation(new RunLeaseToken(token.runUuid(), "other-worker", 7, token.leaseDeadline())));
        assertFalse(store.completeCancellation(new RunLeaseToken(token.runUuid(), token.workerReference(), 6, token.leaseDeadline())));
        assertFalse(store.completeCancellation(new RunLeaseToken(UUID.randomUUID(), token.workerReference(), 7, token.leaseDeadline())));
        jdbc.update("update akis.calistirma_durumu set durum='CALISIYOR'");
        assertFalse(store.completeCancellation(token));
        assertEquals(0, jdbc.queryForObject("select count(*) from akis.calistirma_olayi", Integer.class));
        assertEquals("SAHIPLENILDI", value("hedef_kaynagi", "durum"));
    }

    @Test
    void terminalStatusWithoutExactAcknowledgementEventDoesNotPassReadback() {
        RunLeaseToken token = fixture(false, false);
        jdbc.update("update akis.calistirma_durumu set durum='IPTAL',bitis_zamani=clock_timestamp(),kiralama_bitis_zamani=null");
        assertFalse(store.completeCancellation(token));
    }

    @Test
    void cancellationBeforeStepsOrTargetExistStillCompletes() {
        RunLeaseToken token = fixture(false, false);
        jdbc.update("delete from akis.km_step_journal");
        assertTrue(store.completeCancellation(token));
        assertEquals("IPTAL", value("calistirma_durumu", "durum"));
    }

    @Test
    void durableRecoveryClosesExpiredCancellationOnceAndHonorsBatchLimit() {
        fixture(true, false);
        UUID second = UUID.randomUUID();
        jdbc.update("insert into akis.calistirma(proje_id,uuid) values(1,?)", second);
        jdbc.update("""
                insert into akis.calistirma_durumu(proje_id,calistirma_id,durum,nesil_no,isleyici_referansi,kiralama_bitis_zamani)
                values(1,2,'IPTAL_ISTENDI',7,'worker-cancel',clock_timestamp()-interval '30 seconds')
                """);
        assertEquals(1, recover(1));
        assertEquals(1, jdbc.queryForObject("select count(*) from akis.calistirma_durumu where durum='IPTAL_ISTENDI'", Integer.class));
        assertEquals("BOS", value("hedef_kaynagi", "durum"));
        assertEquals(1, recover(1));
        assertEquals(0, recover(10));
        assertEquals(2, jdbc.queryForObject("select count(*) from akis.calistirma_olayi", Integer.class));
    }

    @Test
    void recoveryDoesNotAcknowledgeLiveLeaseOrNonCancellation() {
        fixture(false, false);
        jdbc.update("update akis.calistirma_durumu set kiralama_bitis_zamani=clock_timestamp()+interval '1 minute'");
        assertEquals(0, recover(10));
        jdbc.update("update akis.calistirma_durumu set durum='CALISIYOR',kiralama_bitis_zamani=clock_timestamp()-interval '1 minute'");
        assertEquals(0, recover(10));
    }

    @Test
    void recoverySkipsLockedRequestsAndPreservesUnknownPublication() throws Exception {
        fixture(true, true);
        try (var caller = Executors.newSingleThreadExecutor()) {
            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from akis.calistirma_durumu for update", Long.class);
                try {
                    assertEquals(0, caller.submit(() -> recover(10)).get(2, TimeUnit.SECONDS));
                }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
        }
        assertEquals(1, recover(10));
        assertEquals("SONUC_BELIRSIZ", value("calistirma_durumu", "durum"));
        assertEquals("ASKIDA", value("hedef_kaynagi", "durum"));
    }

    private int recover(int limit) {
        return jdbc.queryForObject("select akis.calistirma_suresi_dolan_iptalleri_tamamla(?)", Integer.class, limit);
    }

    @Test
    void acknowledgementWaitsForCancellationRequestAndItsForeignKeyEventWithoutDeadlock() throws Exception {
        RunLeaseToken token = fixture(false, false);
        jdbc.update("update akis.calistirma_durumu set durum='CALISIYOR'");
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = transactions.execute(status -> {
                jdbc.queryForObject("select id from akis.calistirma_durumu for update", Long.class);
                CountDownLatch dispatched = new CountDownLatch(1);
                var completion = caller.submit(() -> { dispatched.countDown(); return store.completeCancellation(token); });
                try {
                    assertTrue(dispatched.await(2, TimeUnit.SECONDS));
                    awaitStateLockWait();
                    assertFalse(completion.isDone());
                }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                jdbc.update("update akis.calistirma_durumu set durum='IPTAL_ISTENDI',son_olay_no=son_olay_no+1");
                jdbc.update("""
                        insert into akis.calistirma_olayi(proje_id,calistirma_id,olay_no,tur,olay_zamani,veri)
                        select proje_id,calistirma_id,son_olay_no,'RUN_CANCELLATION_REQUESTED',clock_timestamp(),'{}'::jsonb
                        from akis.calistirma_durumu
                        """);
                return completion;
            });
            assertNotNull(result);
            assertTrue(result.get(2, TimeUnit.SECONDS));
        }
    }

    private RunLeaseToken fixture(boolean target, boolean intent) {
        UUID run = UUID.randomUUID();
        jdbc.update("insert into akis.calistirma(proje_id,uuid) values(1,?)", run);
        jdbc.update("""
                insert into akis.calistirma_durumu(proje_id,calistirma_id,durum,nesil_no,isleyici_referansi,kiralama_bitis_zamani)
                values(1,1,'IPTAL_ISTENDI',7,'worker-cancel',clock_timestamp()-interval '1 minute')
                """);
        if (target) {
            jdbc.update("insert into akis.hedef_kaynagi(calistirma_id,nesil_no,durum,kiralama_bitis_zamani) values(1,11,'SAHIPLENILDI',clock_timestamp()-interval '1 minute')");
            jdbc.update("update akis.calistirma_durumu set hedef_kaynagi_id=1,hedef_nesil_no=11");
        }
        if (intent) jdbc.update("insert into akis.pilot_yayin_niyeti values(1,1)");
        jdbc.update("""
                insert into akis.km_step_journal(proje_id,calistirma_id,generation,worker_reference,ordinal,operation,state,started_at)
                values(1,1,7,'worker-cancel',1,'TRANSFER_JDBC','RUNNING',clock_timestamp()),
                      (1,1,6,'previous-worker',1,'TRANSFER_JDBC','RUNNING',clock_timestamp())
                """);
        return new RunLeaseToken(run, "worker-cancel", 7, OffsetDateTime.now().minusMinutes(1));
    }

    private void awaitStateLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_locks where locktype='transactionid' and not granted)", Boolean.class))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Acknowledgement never reached the locked state row");
    }

    private String value(String table, String column) {
        return jdbc.queryForObject("select " + column + " from akis." + table, String.class);
    }

    private String journal(long generation, String worker) {
        return jdbc.queryForObject("select state from akis.km_step_journal where generation=? and worker_reference=?",
                String.class, generation, worker);
    }
}

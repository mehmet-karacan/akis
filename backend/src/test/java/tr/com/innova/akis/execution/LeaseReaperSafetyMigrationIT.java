package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LeaseReaperSafetyMigrationIT {

    private static final String TARGET_HASH = "c".repeat(64);
    private static final String RUNTIME_PLAN_HASH = "f".repeat(64);
    private static final String PUBLISH_KEY_HASH = "d".repeat(64);
    private static final String PAYLOAD_HASH = "e".repeat(64);

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabaseContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        Flyway.configure()
                .dataSource(context.getBean(DataSource.class))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = context.getBean(JdbcClient.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopDatabaseContext() {
        context.close();
    }

    @BeforeEach
    void createQueuedRuns() {
        jdbcTemplate.execute("""
                SET search_path TO entegrasyon, public;
                TRUNCATE TABLE proje, worker_profili RESTART IDENTITY CASCADE;
                INSERT INTO proje(kod, ad) VALUES ('REAPER_IT', 'Lease reaper IT');
                INSERT INTO klasor(proje_id, kod, ad)
                SELECT id, 'ROOT', 'Root' FROM proje WHERE kod = 'REAPER_IT';
                INSERT INTO tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
                SELECT p.id, k.id, 'PROJE', 'MAPPING', 'REAPER_MAP', 'Reaper mapping'
                  FROM proje p JOIN klasor k ON k.proje_id = p.id
                 WHERE p.kod = 'REAPER_IT';
                INSERT INTO tanim_surumu(
                    tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                SELECT id, 1, 1,
                       encode(sha256(convert_to('reaper-definition', 'UTF8')), 'hex'),
                       '{}'::jsonb
                  FROM tanim WHERE kod = 'REAPER_MAP';
                INSERT INTO ortam(proje_id, kod, risk_kodu, ad)
                SELECT id, 'TEST', 'DUSUK', 'Test' FROM proje WHERE kod = 'REAPER_IT';
                INSERT INTO dogrulama(
                    tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
                  FROM tanim_surumu ts
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'REAPER_MAP'
                  JOIN ortam o ON o.proje_id = t.proje_id AND o.kod = 'TEST';
                INSERT INTO senaryo(
                    tanim_surumu_id, dogrulama_id, surum_no,
                    plan_surumu, plan_ozeti, plan)
                SELECT d.tanim_surumu_id, d.id, 1, 1, repeat('b', 64), '{}'::jsonb
                  FROM dogrulama d
                  JOIN tanim_surumu ts ON ts.id = d.tanim_surumu_id
                  JOIN tanim t ON t.id = ts.tanim_id AND t.kod = 'REAPER_MAP';
                INSERT INTO yayin(
                    proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
                    bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani)
                SELECT p.id, s.id, o.id, 1, 'AKTIF', repeat('d', 64),
                       jsonb_build_object(
                           'releaseHash', repeat('a', 64),
                           'runtimeCapability', 'ORACLE_TABLE_COPY_V1',
                           'runtimePlanHash', repeat('f', 64)), current_timestamp
                  FROM proje p
                  JOIN ortam o ON o.proje_id = p.id AND o.kod = 'TEST'
                  JOIN tanim t ON t.proje_id = p.id AND t.kod = 'REAPER_MAP'
                  JOIN tanim_surumu ts ON ts.tanim_id = t.id
                  JOIN senaryo s ON s.tanim_surumu_id = ts.id
                 WHERE p.kod = 'REAPER_IT';
                INSERT INTO worker_profili(kod, capability, ad)
                VALUES ('REAPER_IT_WORKER', '{}'::jsonb, 'Reaper test worker');
                INSERT INTO is_talebi(
                    proje_id, yayin_id, istek_ozeti, is_turu, parametre)
                SELECT y.proje_id, y.id,
                       encode(sha256(convert_to('reaper-request-' || g, 'UTF8')), 'hex'),
                       'RUN', '{}'::jsonb
                  FROM yayin y CROSS JOIN generate_series(1, 4) g;
                INSERT INTO calistirma(
                    proje_id, is_talebi_id, deneme_no,
                    yayin_ozeti, plan_ozeti, baslatma_turu)
                SELECT proje_id, id, 1, repeat('a', 64), repeat('b', 64), 'ILK'
                  FROM is_talebi;
                INSERT INTO calistirma_durumu(
                    proje_id, calistirma_id, durum_kodu, son_olay_no)
                SELECT proje_id, id, 'BEKLIYOR', 1 FROM calistirma;
                INSERT INTO calistirma_olayi(
                    proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
                SELECT proje_id, id, 1, 'RUN_QUEUED', current_timestamp, '{}'::jsonb
                  FROM calistirma;
                """);
    }

    @Test
    void expiredTargetBeforeIntentIsReleasedAndRunFailsDeterministically() {
        Execution execution = workingExecution("safe-pre-publish");
        expire(execution);

        List<ReapedTarget> reaped = reapTargets();

        assertEquals(List.of(new ReapedTarget(
                execution.claim().runUuid(), execution.target().targetUuid(),
                execution.target().generation())), reaped);
        assertEquals("BASARISIZ", runStatus(execution.claim().runUuid()));
        assertEquals("BOS", targetStatus(execution.target().targetUuid()));
        assertFalse(hasPublishIntent(execution.claim().runUuid()));
        assertEquals("false", eventValue(execution.claim().runUuid(),
                "PRE_PUBLISH_TARGET_LEASE_EXPIRED", "requiresReconciliation"));
        assertEquals("false", eventValue(execution.claim().runUuid(),
                "PRE_PUBLISH_TARGET_LEASE_EXPIRED", "oracleDmlStarted"));
        assertExactFenceEvent(execution, "PRE_PUBLISH_TARGET_LEASE_EXPIRED");
    }

    @Test
    void committedIntentWithoutPublishTransitionStillQuarantinesTarget() {
        Execution execution = workingExecution("intent-before-transition");
        pinIntent(execution);
        expire(execution);

        assertEquals(1, reapTargets().size());

        assertEquals("SONUC_BELIRSIZ", runStatus(execution.claim().runUuid()));
        assertEquals("ASKIDA", targetStatus(execution.target().targetUuid()));
        assertTrue(hasPublishIntent(execution.claim().runUuid()));
        assertEquals("true", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "publishIntentPresent"));
        assertEquals("true", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "requiresReconciliation"));
        assertEquals("33", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "rowCount"));
        assertEquals("1024", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "byteCount"));
        assertEquals(Long.toString(execution.target().generation()), eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "publishTargetGeneration"));
        assertExactFenceEvent(execution, "LEASE_EXPIRED");
    }

    @Test
    void prePublishCancellationExpiresToCancelledAndReleasesTarget() {
        Execution execution = workingExecution("cancelled-pre-publish");
        requestCancellation(execution.claim().runUuid());
        expire(execution);

        assertEquals(1, reapTargets().size());

        assertEquals("IPTAL", runStatus(execution.claim().runUuid()));
        assertEquals("BOS", targetStatus(execution.target().targetUuid()));
        assertEquals("true", eventValue(execution.claim().runUuid(),
                "PRE_PUBLISH_CANCEL_LEASE_EXPIRED", "cancellationAcknowledged"));
        assertEquals("false", eventValue(execution.claim().runUuid(),
                "PRE_PUBLISH_CANCEL_LEASE_EXPIRED", "requiresReconciliation"));
        assertExactFenceEvent(execution, "PRE_PUBLISH_CANCEL_LEASE_EXPIRED");
    }

    @Test
    void cancellationAfterIntentRemainsUnknownAndKeepsTargetSuspended() {
        Execution execution = workingExecution("cancelled-after-intent");
        pinIntent(execution);
        requestCancellation(execution.claim().runUuid());
        expire(execution);

        assertEquals(1, reapTargets().size());

        assertEquals("SONUC_BELIRSIZ", runStatus(execution.claim().runUuid()));
        assertEquals("ASKIDA", targetStatus(execution.target().targetUuid()));
        assertEquals("IPTAL_ISTENDI", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "previousState"));
        assertEquals("true", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "publishIntentPresent"));
        assertEquals("true", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "requiresReconciliation"));
    }

    @Test
    void mismatchedRunAndTargetDeadlinesFailClosedToReconciliation() {
        Execution execution = workingExecution("deadline-mismatch");
        expireWithMismatchedDeadlines(execution);

        assertEquals(1, reapTargets().size());

        assertEquals("SONUC_BELIRSIZ", runStatus(execution.claim().runUuid()));
        assertEquals("ASKIDA", targetStatus(execution.target().targetUuid()));
        assertEquals("LEASE_TOKEN_MISMATCH", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "invariantConflict"));
        assertEquals("true", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "requiresReconciliation"));
        assertExactFenceEvent(execution, "LEASE_EXPIRED");
    }

    @Test
    void publishingRunAlwaysKeepsTheExistingUnknownAndSuspendedBehavior() {
        Execution execution = workingExecution("publishing-expiry");
        assertTrue(beginPublish(execution));
        expire(execution);

        assertEquals(1, reapTargets().size());

        assertEquals("SONUC_BELIRSIZ", runStatus(execution.claim().runUuid()));
        assertEquals("ASKIDA", targetStatus(execution.target().targetUuid()));
        assertEquals("YAYINLANIYOR", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "previousState"));
        assertEquals("true", eventValue(
                execution.claim().runUuid(), "LEASE_EXPIRED", "requiresReconciliation"));
        assertExactFenceEvent(execution, "LEASE_EXPIRED");
    }

    @Test
    void reaperContractExplicitlyDetectsEveryPinnedIntentTokenMismatch() {
        String definition = jdbc.sql("""
                        select pg_get_functiondef(
                            'entegrasyon.suresi_dolan_hedefleri_askiya_al(integer)'
                            ::regprocedure)
                        """)
                .query(String.class)
                .single();

        assertTrue(definition.contains("PUBLISH_INTENT_TOKEN_MISMATCH"));
        assertTrue(definition.contains(
                "v_niyet.calistirma_nesil_no = v_durum.nesil_no"));
        assertTrue(definition.contains(
                "v_niyet.isleyici_referansi = v_durum.isleyici_referansi"));
        assertTrue(definition.contains(
                "v_niyet.hedef_kaynagi_id = v_hedef.id"));
        assertTrue(definition.contains(
                "v_niyet.hedef_nesil_no = v_hedef.nesil_no"));
        assertTrue(definition.contains(
                "v_niyet.hedef_fiziksel_ozeti = v_hedef.fiziksel_ozet"));
        assertTrue(definition.contains(
                "v_niyet.hedef_kimlik_surumu = v_hedef.kimlik_surumu"));
        assertTrue(definition.contains("PUBLISH_STATE_WITHOUT_INTENT"));
        String reconciliationDefinition = jdbc.sql("""
                        select pg_get_functiondef(
                            'entegrasyon.suresi_dolan_mutabakatlari_sonlandir(integer)'
                            ::regprocedure)
                        """)
                .query(String.class)
                .single();
        assertTrue(reconciliationDefinition.contains("RECONCILIATION_WITHOUT_INTENT"));
        assertTrue(reconciliationDefinition.contains(
                "NOT v_claim_veri ? 'reconciliationWorkerReference'"));
    }

    @Test
    void expiredTargetlessPreparationRetainsItsDeterministicFailureBranch() {
        Claim claim = claim("targetless-expiry");
        expireRun(claim.runUuid());

        List<ReapedRun> reaped = jdbc.sql("""
                        select calistirma_uuid, nesil_no
                          from entegrasyon.suresi_dolan_hedefsiz_hazirliklari_sonlandir(10)
                        """)
                .query((rs, rowNum) -> new ReapedRun(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getLong("nesil_no")))
                .list();

        assertEquals(List.of(new ReapedRun(claim.runUuid(), claim.generation())), reaped);
        assertEquals("BASARISIZ", runStatus(claim.runUuid()));
        assertTrue(reapTargets().isEmpty());
        assertEquals(claim.workerReference(), eventValue(
                claim.runUuid(), "PREPARATION_LEASE_EXPIRED", "workerReference"));
        assertEquals(Long.toString(claim.generation()), eventValue(
                claim.runUuid(), "PREPARATION_LEASE_EXPIRED", "generation"));
        assertEquals("false", eventValue(
                claim.runUuid(), "PREPARATION_LEASE_EXPIRED", "targetAcquired"));
    }

    @Test
    void reconciliationExpiryAttestsOriginalAndBarrierGenerations() {
        Execution execution = publishingExecution("publish-worker");
        assertTrue(markUnknown(execution));
        Reconciliation reconciliation = claimReconciliation(
                execution.claim().runUuid(), "reconciliation-worker");
        expireRun(execution.claim().runUuid());

        assertEquals(List.of(new ReapedTarget(
                execution.claim().runUuid(), execution.target().targetUuid(),
                reconciliation.targetGeneration())), reapReconciliations());

        assertEquals("MUDAHALE_GEREKLI", runStatus(execution.claim().runUuid()));
        assertEquals("ASKIDA", targetStatus(execution.target().targetUuid()));
        assertEquals("true", eventValue(execution.claim().runUuid(),
                "RECONCILIATION_LEASE_EXPIRED", "attestationExact"));
        assertEquals(Long.toString(execution.claim().generation()), eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED",
                "publishRunGeneration"));
        assertEquals(Long.toString(reconciliation.runGeneration()), eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED", "generation"));
        assertEquals(Long.toString(execution.target().generation()), eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED",
                "publishTargetGeneration"));
        assertEquals(Long.toString(reconciliation.targetGeneration()), eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED",
                "targetGeneration"));
        assertEquals(execution.claim().workerReference(), eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED",
                "publishWorkerReference"));
        assertEquals("reconciliation-worker", eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED",
                "reconciliationWorkerReference"));
        assertEquals(RUNTIME_PLAN_HASH, eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED",
                "runtimePlanHash"));
        assertEquals("33", eventValue(execution.claim().runUuid(),
                "RECONCILIATION_LEASE_EXPIRED", "rowCount"));
        assertEquals("1024", eventValue(execution.claim().runUuid(),
                "RECONCILIATION_LEASE_EXPIRED", "byteCount"));
    }

    @Test
    void legacyReconciliationClaimWithoutWorkerEventFieldIsNotStranded() {
        Execution execution = publishingExecution("legacy-publish-worker");
        assertTrue(markUnknown(execution));
        Reconciliation reconciliation = claimReconciliation(
                execution.claim().runUuid(), "legacy-reconciliation-worker");
        removeReconciliationWorkerFromLatestEvent(execution.claim().runUuid());

        assertEquals(reconciliation, claimReconciliation(
                execution.claim().runUuid(), "legacy-reconciliation-worker"));
        expireRun(execution.claim().runUuid());
        assertEquals(1, reapReconciliations().size());

        assertEquals("MUDAHALE_GEREKLI", runStatus(execution.claim().runUuid()));
        assertEquals("true", eventValue(execution.claim().runUuid(),
                "RECONCILIATION_LEASE_EXPIRED", "attestationExact"));
        assertEquals("legacy-reconciliation-worker", eventValue(
                execution.claim().runUuid(), "RECONCILIATION_LEASE_EXPIRED",
                "reconciliationWorkerReference"));
    }

    private Execution workingExecution(String workerReference) {
        Claim claim = claim(workerReference);
        Target target = acquire(claim);
        assertTrue(jdbc.sql("""
                        select entegrasyon.calistirma_calismaya_baslat(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration)
                        """)
                .param("runUuid", claim.runUuid())
                .param("workerReference", claim.workerReference())
                .param("runGeneration", claim.generation())
                .param("targetUuid", target.targetUuid())
                .param("targetGeneration", target.generation())
                .query(Boolean.class)
                .single());
        return new Execution(claim, target);
    }

    private Execution publishingExecution(String workerReference) {
        Execution execution = workingExecution(workerReference);
        assertTrue(beginPublish(execution));
        return execution;
    }

    private Claim claim(String workerReference) {
        return jdbc.sql("""
                        select calistirma_uuid, nesil_no
                          from entegrasyon.calistirma_sahiplen(
                              :profileUuid, :workerReference, 60)
                        """)
                .param("profileUuid", workerProfileUuid())
                .param("workerReference", workerReference)
                .query((rs, rowNum) -> new Claim(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getLong("nesil_no"), workerReference))
                .single();
    }

    private Target acquire(Claim claim) {
        return jdbc.sql("""
                        select hedef_kaynagi_uuid, hedef_nesil_no
                          from entegrasyon.hedef_kaynagi_sahiplen(
                              :runUuid, :workerReference, :runGeneration,
                              :targetHash, 1)
                        """)
                .param("runUuid", claim.runUuid())
                .param("workerReference", claim.workerReference())
                .param("runGeneration", claim.generation())
                .param("targetHash", TARGET_HASH)
                .query((rs, rowNum) -> new Target(
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no")))
                .single();
    }

    private void pinIntent(Execution execution) {
        int inserted = jdbc.sql("""
                        insert into entegrasyon.pilot_yayin_niyeti(
                            proje_id, is_talebi_id, calistirma_id,
                            calistirma_adimi_id, calistirma_nesil_no,
                            isleyici_referansi, deneme_no, hedef_kaynagi_id,
                            hedef_nesil_no, hedef_fiziksel_ozeti,
                            hedef_kimlik_surumu, yayin_ozeti, plan_ozeti,
                            runtime_plan_ozeti, yayin_anahtari_ozeti,
                            payload_ozeti, satir_sayisi, bayt_sayisi)
                        select c.proje_id, c.is_talebi_id, c.id, ca.id,
                               cd.nesil_no, cd.isleyici_referansi, c.deneme_no,
                               hk.id, hk.nesil_no, hk.fiziksel_ozet,
                               hk.kimlik_surumu, c.yayin_ozeti, c.plan_ozeti,
                               :runtimePlanHash, :publishKeyHash, :payloadHash,
                               33, 1024
                          from entegrasyon.calistirma c
                          join entegrasyon.calistirma_durumu cd
                            on cd.proje_id = c.proje_id and cd.calistirma_id = c.id
                          join entegrasyon.hedef_kaynagi hk
                            on hk.id = cd.hedef_kaynagi_id
                          join entegrasyon.calistirma_adimi ca
                            on ca.proje_id = c.proje_id and ca.calistirma_id = c.id
                           and ca.adim_kodu = 'PILOT_PUBLISH'
                         where c.uuid = :runUuid
                        """)
                .param("runtimePlanHash", RUNTIME_PLAN_HASH)
                .param("publishKeyHash", PUBLISH_KEY_HASH)
                .param("payloadHash", PAYLOAD_HASH)
                .param("runUuid", execution.claim().runUuid())
                .update();
        assertEquals(1, inserted);
    }

    private boolean beginPublish(Execution execution) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_yayina_gec(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration, :runtimePlanHash,
                            :publishKeyHash, :payloadHash, 33, 1024)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", execution.claim().workerReference())
                .param("runGeneration", execution.claim().generation())
                .param("targetUuid", execution.target().targetUuid())
                .param("targetGeneration", execution.target().generation())
                .param("runtimePlanHash", RUNTIME_PLAN_HASH)
                .param("publishKeyHash", PUBLISH_KEY_HASH)
                .param("payloadHash", PAYLOAD_HASH)
                .query(Boolean.class)
                .single();
    }

    private boolean markUnknown(Execution execution) {
        return jdbc.sql("""
                        select entegrasyon.calistirma_sonucu_belirsiz_isaretle(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration)
                        """)
                .param("runUuid", execution.claim().runUuid())
                .param("workerReference", execution.claim().workerReference())
                .param("runGeneration", execution.claim().generation())
                .param("targetUuid", execution.target().targetUuid())
                .param("targetGeneration", execution.target().generation())
                .query(Boolean.class)
                .single();
    }

    private Reconciliation claimReconciliation(UUID runUuid, String workerReference) {
        return jdbc.sql("""
                        select calistirma_nesil_no, hedef_kaynagi_uuid,
                               hedef_nesil_no
                          from entegrasyon.calistirma_mutabakat_sahiplen(
                              :runUuid, :profileUuid, :workerReference, 60)
                        """)
                .param("runUuid", runUuid)
                .param("profileUuid", workerProfileUuid())
                .param("workerReference", workerReference)
                .query((rs, rowNum) -> new Reconciliation(
                        rs.getLong("calistirma_nesil_no"),
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no")))
                .single();
    }

    private void expire(Execution execution) {
        expireRun(execution.claim().runUuid());
        jdbc.sql("""
                        update entegrasyon.hedef_kaynagi
                           set kiralama_bitis_zamani =
                                   timestamptz '2000-01-01 00:00:00+00',
                               guncellenme_zamani = clock_timestamp(),
                               versiyon_no = versiyon_no + 1
                         where uuid = :targetUuid
                        """)
                .param("targetUuid", execution.target().targetUuid())
                .update();
    }

    private void expireWithMismatchedDeadlines(Execution execution) {
        expireRun(execution.claim().runUuid());
        jdbc.sql("""
                        update entegrasyon.hedef_kaynagi
                           set kiralama_bitis_zamani = clock_timestamp() + interval '30 seconds',
                               guncellenme_zamani = clock_timestamp(),
                               versiyon_no = versiyon_no + 1
                         where uuid = :targetUuid
                        """)
                .param("targetUuid", execution.target().targetUuid())
                .update();
    }

    private void requestCancellation(UUID runUuid) {
        assertEquals(1, jdbc.sql("""
                        with changed as (
                            update entegrasyon.calistirma_durumu cd
                               set durum_kodu = 'IPTAL_ISTENDI',
                                   son_olay_no = cd.son_olay_no + 1,
                                   iptal_isteme_zamani = clock_timestamp(),
                                   guncellenme_zamani = clock_timestamp(),
                                   versiyon_no = cd.versiyon_no + 1
                              from entegrasyon.calistirma c
                             where c.id = cd.calistirma_id and c.uuid = :runUuid
                            returning cd.proje_id, cd.calistirma_id, cd.son_olay_no)
                        insert into entegrasyon.calistirma_olayi(
                            proje_id, calistirma_id, olay_no,
                            tur_kodu, olay_zamani, veri)
                        select proje_id, calistirma_id, son_olay_no,
                               'CANCEL_REQUESTED', clock_timestamp(), '{}'::jsonb
                          from changed
                        """)
                .param("runUuid", runUuid)
                .update());
    }

    private void expireRun(UUID runUuid) {
        jdbc.sql("""
                        update entegrasyon.calistirma_durumu cd
                           set kiralama_bitis_zamani =
                                   timestamptz '2000-01-01 00:00:00+00',
                               guncellenme_zamani = clock_timestamp(),
                               versiyon_no = cd.versiyon_no + 1
                          from entegrasyon.calistirma c
                         where c.id = cd.calistirma_id and c.uuid = :runUuid
                        """)
                .param("runUuid", runUuid)
                .update();
    }

    private List<ReapedTarget> reapTargets() {
        return jdbc.sql("""
                        select calistirma_uuid, hedef_kaynagi_uuid, hedef_nesil_no
                          from entegrasyon.suresi_dolan_hedefleri_askiya_al(10)
                        """)
                .query((rs, rowNum) -> new ReapedTarget(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no")))
                .list();
    }

    private List<ReapedTarget> reapReconciliations() {
        return jdbc.sql("""
                        select calistirma_uuid, hedef_kaynagi_uuid, hedef_nesil_no
                          from entegrasyon.suresi_dolan_mutabakatlari_sonlandir(10)
                        """)
                .query((rs, rowNum) -> new ReapedTarget(
                        rs.getObject("calistirma_uuid", UUID.class),
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no")))
                .list();
    }

    private void removeReconciliationWorkerFromLatestEvent(UUID runUuid) {
        jdbcTemplate.execute("""
                ALTER TABLE entegrasyon.calistirma_olayi
                    DISABLE TRIGGER tr_calistirma_olayi_immutable
                """);
        try {
            assertEquals(1, jdbc.sql("""
                            update entegrasyon.calistirma_olayi co
                               set veri = co.veri - 'reconciliationWorkerReference'
                              from entegrasyon.calistirma c,
                                   entegrasyon.calistirma_durumu cd
                             where c.uuid = :runUuid
                               and cd.calistirma_id = c.id
                               and co.calistirma_id = c.id
                               and co.olay_no = cd.son_olay_no
                               and co.tur_kodu = 'RECONCILIATION_CLAIMED'
                            """)
                    .param("runUuid", runUuid)
                    .update());
        }
        finally {
            jdbcTemplate.execute("""
                    ALTER TABLE entegrasyon.calistirma_olayi
                        ENABLE TRIGGER tr_calistirma_olayi_immutable
                    """);
        }
    }

    private void assertExactFenceEvent(Execution execution, String eventType) {
        assertEquals(Long.toString(execution.claim().generation()), eventValue(
                execution.claim().runUuid(), eventType, "generation"));
        assertEquals(execution.claim().workerReference(), eventValue(
                execution.claim().runUuid(), eventType, "workerReference"));
        assertEquals(execution.target().targetUuid().toString(), eventValue(
                execution.claim().runUuid(), eventType, "targetResourceUuid"));
        assertEquals(Long.toString(execution.target().generation()), eventValue(
                execution.claim().runUuid(), eventType, "targetGeneration"));
        assertEquals(TARGET_HASH, eventValue(
                execution.claim().runUuid(), eventType, "canonicalTargetHash"));
    }

    private boolean hasPublishIntent(UUID runUuid) {
        return jdbc.sql("""
                        select exists (
                            select 1 from entegrasyon.pilot_yayin_niyeti pyn
                              join entegrasyon.calistirma c on c.id = pyn.calistirma_id
                             where c.uuid = :runUuid)
                        """)
                .param("runUuid", runUuid)
                .query(Boolean.class)
                .single();
    }

    private String runStatus(UUID runUuid) {
        return jdbc.sql("""
                        select cd.durum_kodu
                          from entegrasyon.calistirma_durumu cd
                          join entegrasyon.calistirma c on c.id = cd.calistirma_id
                         where c.uuid = :runUuid
                        """)
                .param("runUuid", runUuid)
                .query(String.class)
                .single();
    }

    private String targetStatus(UUID targetUuid) {
        return jdbc.sql("""
                        select durum_kodu from entegrasyon.hedef_kaynagi
                         where uuid = :targetUuid
                        """)
                .param("targetUuid", targetUuid)
                .query(String.class)
                .single();
    }

    private String eventValue(UUID runUuid, String type, String key) {
        return jdbc.sql("""
                        select co.veri ->> :key
                          from entegrasyon.calistirma_olayi co
                          join entegrasyon.calistirma c on c.id = co.calistirma_id
                         where c.uuid = :runUuid and co.tur_kodu = :type
                        """)
                .param("key", key)
                .param("runUuid", runUuid)
                .param("type", type)
                .query(String.class)
                .single();
    }

    private UUID workerProfileUuid() {
        return jdbc.sql("""
                        select uuid from entegrasyon.worker_profili
                         where kod = 'REAPER_IT_WORKER'
                        """)
                .query(UUID.class)
                .single();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(requiredEnvironment("SPRING_DATASOURCE_URL"));
            dataSource.setUsername(requiredEnvironment("SPRING_DATASOURCE_USERNAME"));
            dataSource.setPassword(requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        name + " is required for the JDBC integration test.");
            }
            return value;
        }
    }

    private record Claim(UUID runUuid, long generation, String workerReference) {
    }

    private record Target(UUID targetUuid, long generation) {
    }

    private record Execution(Claim claim, Target target) {
    }

    private record ReapedTarget(UUID runUuid, UUID targetUuid, long targetGeneration) {
    }

    private record ReapedRun(UUID runUuid, long generation) {
    }

    private record Reconciliation(
            long runGeneration, UUID targetUuid, long targetGeneration) {
    }
}

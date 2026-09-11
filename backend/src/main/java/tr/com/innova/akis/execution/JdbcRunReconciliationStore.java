package tr.com.innova.akis.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;
import tr.com.innova.akis.execution.RunReconciliationPort.CompletionResult;
import tr.com.innova.akis.execution.RunReconciliationPort.Conflict;
import tr.com.innova.akis.execution.RunReconciliationPort.HeartbeatResult;
import tr.com.innova.akis.execution.RunReconciliationPort.NotPublished;
import tr.com.innova.akis.execution.RunReconciliationPort.PublishEvidence;
import tr.com.innova.akis.execution.RunReconciliationPort.Published;
import tr.com.innova.akis.execution.RunReconciliationPort.ReconciliationCompletion;
import tr.com.innova.akis.execution.RunReconciliationPort.ReconciliationLeaseToken;

@Repository
public class JdbcRunReconciliationStore implements RunReconciliationPort {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MINIMUM_LEASE_SECONDS = 30;
    private static final int MAXIMUM_LEASE_SECONDS = 300;

    private final JdbcClient jdbc;

    public JdbcRunReconciliationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<ReconciliationLeaseToken> claim(
            UUID runUuid, WorkerIdentity worker, Duration lease) {
        if (runUuid == null) {
            throw new IllegalArgumentException("Run UUID is required.");
        }
        WorkerIdentity safeWorker = worker(worker);
        int leaseSeconds = leaseSeconds(lease);
        return jdbc.sql("""
                        select calistirma_nesil_no, hedef_kaynagi_uuid,
                               hedef_nesil_no, kiralama_bitis_zamani
                          from entegrasyon.calistirma_mutabakat_sahiplen(
                               :runUuid, :profileUuid, :workerReference, :leaseSeconds)
                        """)
                .param("runUuid", runUuid)
                .param("profileUuid", safeWorker.profileUuid())
                .param("workerReference", safeWorker.reference())
                .param("leaseSeconds", leaseSeconds)
                .query((rs, rowNum) -> mapToken(rs, runUuid, safeWorker.reference()))
                .optional();
    }

    @Override
    @Transactional
    public HeartbeatResult heartbeat(
            ReconciliationLeaseToken token, Duration lease) {
        ReconciliationLeaseToken safeToken = token(token);
        int leaseSeconds = leaseSeconds(lease);
        if (!lockActiveLease(safeToken)) {
            return HeartbeatResult.rejected();
        }
        Boolean accepted = jdbc.sql("""
                        select entegrasyon.calistirma_mutabakat_yasam_sinyali(
                            :runUuid, :workerReference, :runGeneration, :leaseSeconds)
                        """)
                .param("runUuid", safeToken.runUuid())
                .param("workerReference", safeToken.workerReference())
                .param("runGeneration", safeToken.runGeneration())
                .param("leaseSeconds", leaseSeconds)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(accepted)) {
            return HeartbeatResult.rejected();
        }
        Optional<OffsetDateTime> deadline = activeLeaseDeadline(safeToken);
        return deadline
                .map(value -> HeartbeatResult.accepted(new ReconciliationLeaseToken(
                        safeToken.runUuid(), safeToken.workerReference(),
                        safeToken.runGeneration(), safeToken.targetResourceUuid(),
                        safeToken.targetGeneration(), value)))
                .orElseGet(HeartbeatResult::rejected);
    }

    private boolean lockActiveLease(ReconciliationLeaseToken token) {
        return jdbc.sql("""
                        select true
                          from entegrasyon.calistirma_durumu cd
                          join entegrasyon.calistirma c
                            on c.proje_id = cd.proje_id
                           and c.id = cd.calistirma_id
                          join entegrasyon.hedef_kaynagi hk
                            on hk.id = cd.hedef_kaynagi_id
                         where c.uuid = :runUuid
                           and cd.durum_kodu = 'MUTABAKAT'
                           and cd.isleyici_referansi = :workerReference
                           and cd.nesil_no = :runGeneration
                           and cd.kiralama_bitis_zamani > clock_timestamp()
                           and hk.uuid = :targetUuid
                           and hk.durum_kodu = 'ASKIDA'
                           and hk.nesil_no = :targetGeneration
                           and cd.hedef_nesil_no = hk.nesil_no
                         for update of cd, hk
                        """)
                .param("runUuid", token.runUuid())
                .param("workerReference", token.workerReference())
                .param("runGeneration", token.runGeneration())
                .param("targetUuid", token.targetResourceUuid())
                .param("targetGeneration", token.targetGeneration())
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private Optional<OffsetDateTime> activeLeaseDeadline(
            ReconciliationLeaseToken token) {
        return jdbc.sql("""
                        select cd.kiralama_bitis_zamani
                          from entegrasyon.calistirma_durumu cd
                          join entegrasyon.calistirma c
                            on c.proje_id = cd.proje_id
                           and c.id = cd.calistirma_id
                          join entegrasyon.hedef_kaynagi hk
                            on hk.id = cd.hedef_kaynagi_id
                         where c.uuid = :runUuid
                           and cd.durum_kodu = 'MUTABAKAT'
                           and cd.isleyici_referansi = :workerReference
                           and cd.nesil_no = :runGeneration
                           and hk.uuid = :targetUuid
                           and hk.durum_kodu = 'ASKIDA'
                           and hk.nesil_no = :targetGeneration
                           and cd.hedef_nesil_no = hk.nesil_no
                        """)
                .param("runUuid", token.runUuid())
                .param("workerReference", token.workerReference())
                .param("runGeneration", token.runGeneration())
                .param("targetUuid", token.targetResourceUuid())
                .param("targetGeneration", token.targetGeneration())
                .query(OffsetDateTime.class)
                .optional();
    }

    @Override
    @Transactional
    public CompletionResult complete(
            ReconciliationLeaseToken token, ReconciliationCompletion completion) {
        ReconciliationLeaseToken safeToken = token(token);
        if (completion == null) {
            throw new IllegalArgumentException("A typed reconciliation completion is required.");
        }

        boolean accepted;
        if (completion instanceof Published published) {
            PublishEvidence evidence = evidence(published.evidence());
            accepted = completePublished(safeToken, evidence);
        }
        else if (completion instanceof NotPublished) {
            accepted = completeWithoutEvidence(safeToken, "NOT_PUBLISHED");
        }
        else if (completion instanceof Conflict) {
            accepted = completeWithoutEvidence(safeToken, "CONFLICT");
        }
        else {
            throw new IllegalArgumentException("Unsupported reconciliation completion.");
        }
        if (accepted || acknowledgedCompletion(safeToken, completion)) {
            return CompletionResult.accepted();
        }
        return CompletionResult.rejected();
    }

    private boolean acknowledgedCompletion(
            ReconciliationLeaseToken token, ReconciliationCompletion completion) {
        if (completion instanceof Published published) {
            return finalStateMatches(token, "PUBLISHED", "BASARILI", "BOS")
                    && publishedCheckpointMatches(token, evidence(published.evidence()));
        }
        if (completion instanceof NotPublished) {
            return finalStateMatches(
                    token, "NOT_PUBLISHED", "YENIDEN_DENENEBILIR", "BOS");
        }
        if (completion instanceof Conflict) {
            return finalStateMatches(
                    token, "CONFLICT", "MUDAHALE_GEREKLI", "ASKIDA");
        }
        return false;
    }

    private boolean finalStateMatches(
            ReconciliationLeaseToken token,
            String outcome,
            String runStatus,
            String targetStatus) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (
                            select 1
                              from entegrasyon.calistirma c
                              join entegrasyon.calistirma_durumu cd
                                on cd.proje_id = c.proje_id
                               and cd.calistirma_id = c.id
                              join entegrasyon.hedef_kaynagi hk
                                on hk.id = cd.hedef_kaynagi_id
                              join entegrasyon.calistirma_olayi co
                                on co.proje_id = c.proje_id
                               and co.calistirma_id = c.id
                               and co.olay_no = cd.son_olay_no
                             where c.uuid = :runUuid
                               and cd.durum_kodu = :runStatus
                               and cd.isleyici_referansi = :workerReference
                               and cd.nesil_no = :runGeneration
                               and cd.kiralama_bitis_zamani is null
                               and cd.bitis_zamani is not null
                               and hk.uuid = :targetUuid
                               and cd.hedef_nesil_no = :targetGeneration
                               and co.tur_kodu = 'RECONCILIATION_COMPLETED'
                               and co.veri ->> 'outcome' = :outcome
                               and co.veri ->> 'targetStatus' = :targetStatus
                        )
                        """)
                .param("runUuid", token.runUuid())
                .param("runStatus", runStatus)
                .param("workerReference", token.workerReference())
                .param("runGeneration", token.runGeneration())
                .param("targetUuid", token.targetResourceUuid())
                .param("targetStatus", targetStatus)
                .param("targetGeneration", token.targetGeneration())
                .param("outcome", outcome)
                .query(Boolean.class)
                .single());
    }

    private boolean publishedCheckpointMatches(
            ReconciliationLeaseToken token, PublishEvidence evidence) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (
                            select 1
                              from entegrasyon.kontrol_noktasi kn
                              join entegrasyon.calistirma c
                                on c.proje_id = kn.proje_id
                               and c.id = kn.calistirma_id
                              join entegrasyon.calistirma_adimi ca
                                on ca.proje_id = kn.proje_id
                               and ca.id = kn.calistirma_adimi_id
                             where c.uuid = :runUuid
                               and ca.adim_kodu = 'PILOT_PUBLISH'
                               and kn.tur_kodu = 'PUBLISH'
                               and kn.hedef_kaynagi_id = (
                                   select hk.id
                                     from entegrasyon.hedef_kaynagi hk
                                    where hk.uuid = :targetUuid)
                               and kn.hedef_nesil_no = :targetGeneration - 1
                               and kn.mutabakat_hedef_nesil_no = :targetGeneration
                               and kn.kapsam_ozeti = :runtimePlanHash
                               and kn.hedef_defter_referansi = :publishKeyHash
                               and kn.payload_ozeti = :payloadHash
                               and (kn.imlec ->> 'rowCount')::bigint = :rowCount
                               and (kn.imlec ->> 'byteCount')::bigint = :byteCount
                               and coalesce((kn.imlec ->> 'reconciled')::boolean, false)
                        )
                        """)
                .param("runUuid", token.runUuid())
                .param("targetUuid", token.targetResourceUuid())
                .param("targetGeneration", token.targetGeneration())
                .param("runtimePlanHash", evidence.runtimePlanHash())
                .param("publishKeyHash", evidence.publishKeyHash())
                .param("payloadHash", evidence.payloadHash())
                .param("rowCount", evidence.rowCount())
                .param("byteCount", evidence.byteCount())
                .query(Boolean.class)
                .single());
    }

    private boolean completePublished(
            ReconciliationLeaseToken token, PublishEvidence evidence) {
        Boolean accepted = jdbc.sql("""
                        select entegrasyon.calistirma_mutabakat_sonlandir(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration, 'PUBLISHED',
                            :runtimePlanHash, :publishKeyHash, :payloadHash,
                            :rowCount, :byteCount)
                        """)
                .param("runUuid", token.runUuid())
                .param("workerReference", token.workerReference())
                .param("runGeneration", token.runGeneration())
                .param("targetUuid", token.targetResourceUuid())
                .param("targetGeneration", token.targetGeneration())
                .param("runtimePlanHash", evidence.runtimePlanHash())
                .param("publishKeyHash", evidence.publishKeyHash())
                .param("payloadHash", evidence.payloadHash())
                .param("rowCount", evidence.rowCount())
                .param("byteCount", evidence.byteCount())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(accepted);
    }

    private boolean completeWithoutEvidence(
            ReconciliationLeaseToken token, String outcome) {
        Boolean accepted = jdbc.sql("""
                        select entegrasyon.calistirma_mutabakat_sonlandir(
                            :runUuid, :workerReference, :runGeneration,
                            :targetUuid, :targetGeneration, :outcome,
                            null::text, null::text, null::text,
                            null::bigint, null::bigint)
                        """)
                .param("runUuid", token.runUuid())
                .param("workerReference", token.workerReference())
                .param("runGeneration", token.runGeneration())
                .param("targetUuid", token.targetResourceUuid())
                .param("targetGeneration", token.targetGeneration())
                .param("outcome", outcome)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(accepted);
    }

    private ReconciliationLeaseToken mapToken(
            ResultSet rs, UUID runUuid, String workerReference) throws SQLException {
        return new ReconciliationLeaseToken(
                runUuid,
                workerReference,
                rs.getLong("calistirma_nesil_no"),
                rs.getObject("hedef_kaynagi_uuid", UUID.class),
                rs.getLong("hedef_nesil_no"),
                rs.getObject("kiralama_bitis_zamani", OffsetDateTime.class));
    }

    private WorkerIdentity worker(WorkerIdentity worker) {
        if (worker == null || worker.profileUuid() == null
                || worker.reference() == null || worker.reference().isBlank()) {
            throw new IllegalArgumentException("Worker identity and profile UUID are required.");
        }
        String reference = worker.reference().trim();
        if (reference.length() > 200) {
            throw new IllegalArgumentException("Worker reference cannot exceed 200 characters.");
        }
        return new WorkerIdentity(reference, worker.profileUuid());
    }

    private ReconciliationLeaseToken token(ReconciliationLeaseToken token) {
        if (token == null || token.runUuid() == null
                || token.workerReference() == null || token.workerReference().isBlank()
                || token.workerReference().length() > 200
                || token.runGeneration() <= 0 || token.targetResourceUuid() == null
                || token.targetGeneration() <= 0 || token.leaseDeadline() == null) {
            throw new IllegalArgumentException("A complete reconciliation lease token is required.");
        }
        return token;
    }

    private PublishEvidence evidence(PublishEvidence evidence) {
        if (evidence == null
                || !hash(evidence.runtimePlanHash())
                || !hash(evidence.publishKeyHash())
                || !hash(evidence.payloadHash())
                || evidence.rowCount() < 0 || evidence.byteCount() < 0) {
            throw new IllegalArgumentException("Published reconciliation evidence is invalid.");
        }
        return evidence;
    }

    private boolean hash(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private int leaseSeconds(Duration lease) {
        if (lease == null || lease.isNegative() || lease.isZero()
                || lease.getNano() != 0
                || lease.getSeconds() < MINIMUM_LEASE_SECONDS
                || lease.getSeconds() > MAXIMUM_LEASE_SECONDS) {
            throw new IllegalArgumentException("Lease must be 30-300 whole seconds.");
        }
        return Math.toIntExact(lease.getSeconds());
    }
}

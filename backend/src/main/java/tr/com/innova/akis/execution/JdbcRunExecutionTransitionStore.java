package tr.com.innova.akis.execution;

import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.MutationResult;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.PublishIntentEvidence;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;

@Repository
public class JdbcRunExecutionTransitionStore implements RunExecutionTransitionPort {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    private final JdbcClient jdbc;

    public JdbcRunExecutionTransitionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MutationResult completePreflight(ActiveExecutionToken token) {
        ActiveExecutionToken safe = required(token);
        return invokeWithReadback("""
                select akis.calistirma_calismaya_baslat(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration)
                """, """
                select exists (
                    select 1
                      from akis.calistirma c
                      join akis.calistirma_durumu cd
                        on cd.proje_id = c.proje_id and cd.calistirma_id = c.id
                      join akis.hedef_kaynagi hk
                        on hk.id = cd.hedef_kaynagi_id
                      join akis.calistirma_olayi co
                        on co.proje_id = c.proje_id and co.calistirma_id = c.id
                       and co.olay_no = cd.son_olay_no
                     where c.uuid = :runUuid
                       and cd.durum = 'CALISIYOR'
                       and cd.isleyici_referansi = :workerReference
                       and cd.nesil_no = :runGeneration
                       and cd.kiralama_bitis_zamani > clock_timestamp()
                       and cd.hedef_nesil_no = :targetGeneration
                       and hk.uuid = :targetUuid
                       and hk.durum = 'SAHIPLENILDI'
                       and hk.calistirma_id = c.id
                       and hk.nesil_no = :targetGeneration
                       and hk.kiralama_bitis_zamani > clock_timestamp()
                       and co.tur = 'PREFLIGHT_COMPLETED'
                       and co.veri ->> 'generation' = cast(:runGeneration as text)
                       and co.veri ->> 'targetGeneration' = cast(:targetGeneration as text)
                       and (select count(*) from akis.calistirma_adimi ca
                             where ca.calistirma_id = c.id
                               and ca.adim_kodu in ('PILOT_PREFLIGHT', 'PILOT_PUBLISH')) = 2)
                """, safe, null, null, false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MutationResult beginPublish(
            ActiveExecutionToken token, PublishIntentEvidence evidence) {
        ActiveExecutionToken safe = required(token);
        PublishIntentEvidence proof = required(evidence);
        // V008 makes an exact retry an active-lease readback acknowledgement;
        // callers may safely repeat this method after an acknowledgement loss.
        return invokeWithReadback("""
                select akis.calistirma_yayina_gec(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :runtimePlanHash,
                    :publishKeyHash, :payloadHash, :rowCount, :byteCount)
                """, null, safe, proof, null, false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MutationResult failPreflightSafely(
            RunLeaseToken token, String safeErrorCode) {
        RunLeaseToken safe = required(token);
        validateErrorCode(safeErrorCode);
        try {
            Boolean mutated = jdbc.sql("""
                            select akis.calistirma_guvenli_hata_ile_sonlandir(
                                :runUuid, :workerReference, :runGeneration,
                                cast(null as uuid), cast(null as bigint), :errorCode)
                            """)
                    .param("runUuid", safe.runUuid())
                    .param("workerReference", safe.workerReference())
                    .param("runGeneration", safe.generation())
                    .param("errorCode", safeErrorCode)
                    .query(Boolean.class)
                    .single();
            if (Boolean.TRUE.equals(mutated)) {
                return MutationResult.accepted();
            }
            Boolean acknowledged = jdbc.sql("""
                            select exists (
                                select 1
                                  from akis.calistirma c
                                  join akis.calistirma_durumu cd
                                    on cd.proje_id = c.proje_id
                                   and cd.calistirma_id = c.id
                                  join akis.calistirma_olayi co
                                    on co.proje_id = c.proje_id
                                   and co.calistirma_id = c.id
                                   and co.olay_no = cd.son_olay_no
                                 where c.uuid = :runUuid
                                   and cd.durum = 'BASARISIZ'
                                   and cd.isleyici_referansi = :workerReference
                                   and cd.nesil_no = :runGeneration
                                   and cd.kiralama_bitis_zamani is null
                                   and cd.bitis_zamani is not null
                                   and cd.hedef_kaynagi_id is null
                                   and cd.hedef_nesil_no is null
                                   and co.tur = 'RUN_FAILED_SAFE'
                                   and co.veri ->> 'generation' = cast(:runGeneration as text)
                                   and co.veri ->> 'workerReference' = :workerReference
                                   and co.veri ->> 'targetResourceUuid' is null
                                   and co.veri ->> 'targetGeneration' is null
                                   and co.veri ->> 'errorCode' = :errorCode
                                   and co.veri ->> 'rollbackConfirmed' = 'true'
                                   and co.veri ->> 'runtimePlanHash' is null
                                   and co.veri ->> 'publishKeyHash' is null
                                   and co.veri ->> 'payloadHash' is null
                                   and co.veri ->> 'rowCount' is null
                                   and co.veri ->> 'byteCount' is null)
                            """)
                    .param("runUuid", safe.runUuid())
                    .param("workerReference", safe.workerReference())
                    .param("runGeneration", safe.generation())
                    .param("errorCode", safeErrorCode)
                    .query(Boolean.class)
                    .single();
            return Boolean.TRUE.equals(acknowledged)
                    ? MutationResult.accepted()
                    : MutationResult.rejected();
        }
        catch (RuntimeException exception) {
            throw new RunExecutionTransitionException();
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MutationResult failSafely(
            ActiveExecutionToken token, String safeErrorCode) {
        ActiveExecutionToken safe = required(token);
        validateErrorCode(safeErrorCode);
        return invokeWithReadback("""
                select akis.calistirma_guvenli_hata_ile_sonlandir(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :errorCode)
                """, """
                select exists (
                    select 1
                      from akis.calistirma c
                      join akis.calistirma_durumu cd
                        on cd.proje_id = c.proje_id and cd.calistirma_id = c.id
                      join akis.hedef_kaynagi hk
                        on hk.id = cd.hedef_kaynagi_id
                      join akis.calistirma_olayi co
                        on co.proje_id = c.proje_id and co.calistirma_id = c.id
                       and co.olay_no = cd.son_olay_no
                     where c.uuid = :runUuid
                       and cd.durum = 'BASARISIZ'
                       and cd.isleyici_referansi = :workerReference
                       and cd.nesil_no = :runGeneration
                       and cd.kiralama_bitis_zamani is null
                       and cd.bitis_zamani is not null
                       and cd.hedef_nesil_no = :targetGeneration
                       and hk.uuid = :targetUuid
                       and hk.durum = 'BOS'
                       and hk.calistirma_id is null
                       and hk.nesil_no = :targetGeneration
                       and hk.kiralama_bitis_zamani is null
                       and co.tur = 'RUN_FAILED_SAFE'
                       and co.veri ->> 'errorCode' = :errorCode
                       and co.veri ->> 'rollbackConfirmed' = 'true')
                """, safe, null, safeErrorCode, false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MutationResult markOutcomeUnknown(ActiveExecutionToken token) {
        ActiveExecutionToken safe = required(token);
        return invokeWithReadback("""
                select akis.calistirma_sonucu_belirsiz_isaretle(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration)
                """, """
                select exists (
                    select 1
                      from akis.calistirma c
                      join akis.calistirma_durumu cd
                        on cd.proje_id = c.proje_id and cd.calistirma_id = c.id
                      join akis.hedef_kaynagi hk
                        on hk.id = cd.hedef_kaynagi_id
                      join akis.calistirma_olayi co
                        on co.proje_id = c.proje_id and co.calistirma_id = c.id
                       and co.olay_no = cd.son_olay_no
                     where c.uuid = :runUuid
                       and cd.durum = 'SONUC_BELIRSIZ'
                       and cd.isleyici_referansi = :workerReference
                       and cd.nesil_no = :runGeneration
                       and cd.kiralama_bitis_zamani is null
                       and cd.bitis_zamani is not null
                       and cd.hedef_nesil_no = :targetGeneration
                       and hk.uuid = :targetUuid
                       and hk.durum = 'ASKIDA'
                       and hk.calistirma_id is null
                       and hk.nesil_no = :targetGeneration
                       and hk.kiralama_bitis_zamani is null
                       and co.tur = 'RESULT_UNCERTAIN'
                       and co.veri ->> 'targetGeneration' = cast(:targetGeneration as text)
                       and co.veri ->> 'requiresReconciliation' = 'true')
                """, safe, null, null, false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MutationResult completeSuccessfully(
            ActiveExecutionToken token, PublishIntentEvidence evidence) {
        ActiveExecutionToken safe = required(token);
        PublishIntentEvidence proof = required(evidence);
        // V010 performs the durable exact terminal readback inside the locked
        // database function. Do not require the target to remain BOS here: a
        // later run may legitimately acquire the same target before this
        // caller retries a lost acknowledgement.
        return invokeWithReadback("""
                select akis.calistirma_basarili_tamamla(
                    :runUuid, :workerReference, :runGeneration,
                    :targetUuid, :targetGeneration, :runtimePlanHash,
                    :publishKeyHash, :payloadHash, :rowCount, :byteCount)
                """, """
                select exists (
                    select 1
                      from akis.calistirma c
                      join akis.calistirma_durumu cd
                        on cd.proje_id = c.proje_id and cd.calistirma_id = c.id
                      join akis.hedef_kaynagi hk
                        on hk.id = cd.hedef_kaynagi_id
                      join akis.calistirma_olayi co
                        on co.proje_id = c.proje_id and co.calistirma_id = c.id
                       and co.olay_no = cd.son_olay_no
                      join akis.kontrol_noktasi kn
                        on kn.proje_id = c.proje_id and kn.calistirma_id = c.id
                       and kn.hedef_kaynagi_id = hk.id
                     where c.uuid = :runUuid
                       and cd.durum = 'BASARILI'
                       and cd.isleyici_referansi = :workerReference
                       and cd.nesil_no = :runGeneration
                       and cd.kiralama_bitis_zamani is null
                       and cd.bitis_zamani is not null
                       and cd.hedef_nesil_no = :targetGeneration
                       and hk.uuid = :targetUuid
                       and hk.durum = 'BOS'
                       and hk.calistirma_id is null
                       and hk.nesil_no = :targetGeneration
                       and hk.kiralama_bitis_zamani is null
                       and co.tur = 'RUN_SUCCEEDED'
                       and co.veri ->> 'rowCount' = cast(:rowCount as text)
                       and co.veri ->> 'byteCount' = cast(:byteCount as text)
                       and co.veri ->> 'targetGeneration' = cast(:targetGeneration as text)
                       and kn.hedef_nesil_no = :targetGeneration
                       and kn.kapsam_ozeti = :runtimePlanHash
                       and kn.hedef_defter_referansi = :publishKeyHash
                       and kn.payload_ozeti = :payloadHash
                       and kn.imlec ->> 'rowCount' = cast(:rowCount as text)
                       and kn.imlec ->> 'byteCount' = cast(:byteCount as text))
                """, safe, proof, null, false);
    }

    private MutationResult invokeWithReadback(
            String mutationSql,
            String exactReadbackSql,
            ActiveExecutionToken token,
            PublishIntentEvidence evidence,
            String errorCode,
            boolean readbackAlways) {
        try {
            Boolean mutated = bind(jdbc.sql(mutationSql), token, evidence, errorCode)
                    .query(Boolean.class)
                    .single();
            if (Boolean.TRUE.equals(mutated) && !readbackAlways) {
                return MutationResult.accepted();
            }
            if (exactReadbackSql == null) {
                return MutationResult.rejected();
            }
            Boolean acknowledged = bind(
                    jdbc.sql(exactReadbackSql), token, evidence, errorCode)
                    .query(Boolean.class)
                    .single();
            return Boolean.TRUE.equals(acknowledged)
                    ? MutationResult.accepted()
                    : MutationResult.rejected();
        }
        catch (RuntimeException exception) {
            // Do not retain SQL diagnostics; they can contain endpoint/data details.
            throw new RunExecutionTransitionException();
        }
    }

    private JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec statement,
            ActiveExecutionToken token,
            PublishIntentEvidence evidence,
            String errorCode) {
        JdbcClient.StatementSpec bound = statement
                .param("runUuid", token.run().runUuid())
                .param("workerReference", token.run().workerReference())
                .param("runGeneration", token.run().generation())
                .param("targetUuid", token.target().targetResourceUuid())
                .param("targetGeneration", token.target().targetGeneration());
        if (evidence != null) {
            bound = bound
                    .param("runtimePlanHash", evidence.runtimePlanHash())
                    .param("publishKeyHash", evidence.publishKeyHash())
                    .param("payloadHash", evidence.payloadHash())
                    .param("rowCount", evidence.rowCount())
                    .param("byteCount", evidence.byteCount());
        }
        if (errorCode != null) {
            bound = bound.param("errorCode", errorCode);
        }
        return bound;
    }

    private ActiveExecutionToken required(ActiveExecutionToken token) {
        if (token == null) {
            throw new IllegalArgumentException("Active execution token is required.");
        }
        return token;
    }

    private RunLeaseToken required(RunLeaseToken token) {
        if (token == null) {
            throw new IllegalArgumentException("Run lease token is required.");
        }
        return token;
    }

    private void validateErrorCode(String safeErrorCode) {
        if (safeErrorCode == null || !ERROR_CODE.matcher(safeErrorCode).matches()) {
            throw new IllegalArgumentException("Safe error code is invalid.");
        }
    }

    private PublishIntentEvidence required(PublishIntentEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("Publish intent evidence is required.");
        }
        return evidence;
    }
}

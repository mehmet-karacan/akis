package tr.com.innova.akis.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;

@Repository
class JdbcExecutionRecoveryStore implements ExecutionRecoveryStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    JdbcExecutionRecoveryStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<RecoveryApplication> find(
            UUID projectUuid, UUID parentRunUuid, long actorId, String keyHash) {
        return jdbc.sql(selectApplication() + """
                 where p.uuid=:projectUuid and parent.uuid=:parentRunUuid
                   and kr.olusturan_kullanici_id=:actorId
                   and kr.idempotency_anahtar_ozeti=:keyHash
                """).param("projectUuid", projectUuid).param("parentRunUuid", parentRunUuid)
                .param("actorId", actorId).param("keyHash", keyHash)
                .query(this::mapApplication).optional();
    }

    @Override
    @Transactional
    public RecoveryApplication create(
            RunRow parent, Actor actor, RecoveryAction action, String keyHash,
            String requestHash, RecoveryPlan plan) {
        long projectId = jdbc.sql("select proje_id from akis.calistirma where id=:id")
                .param("id", parent.runId()).query(Long.class).single();
        jdbc.sql("select id from akis.is_talebi where id=:jobId for update")
                .param("jobId", parent.jobRequestId()).query(Long.class).single();
        Long newRunId = null;
        RunRow result = parent;
        if (action == RecoveryAction.RETRY_FAILED_UNIT || action == RecoveryAction.RESUME) {
            newRunId = createAttempt(projectId, parent, actor,
                    action == RecoveryAction.RESUME ? "DEVAM_ET" : "YENIDEN_DENE");
            result = findRun(newRunId);
        }
        else if (action == RecoveryAction.RESTART) {
            newRunId = createRestart(projectId, parent, actor, requestHash);
            result = findRun(newRunId);
        }
        UUID requestUuid = UUID.randomUUID();
        jdbc.sql("""
                insert into akis.kurtarma_talebi(
                    uuid,proje_id,is_talebi_id,onceki_calistirma_id,yeni_calistirma_id,
                    eylem,kanit_surumu,beklenen_durum_surumu,plan_ozeti,karar_ozeti,
                    istek_ozeti,idempotency_anahtar_ozeti,birimler,nedenler,durum,
                    olusturan_kullanici_id)
                values(:uuid,:projectId,:jobId,:parentRunId,:newRunId,:action,
                    :evidenceVersion,:stateVersion,:runtimePlanHash,:decisionHash,
                    :requestHash,:keyHash,cast(:units as jsonb),cast(:reasons as jsonb),
                    'APPLIED',:actorId)
                """).param("uuid", requestUuid).param("projectId", projectId)
                .param("jobId", parent.jobRequestId()).param("parentRunId", parent.runId())
                .param("newRunId", newRunId, java.sql.Types.BIGINT).param("action", action.name())
                .param("evidenceVersion", plan.evidenceVersion())
                .param("stateVersion", plan.expectedStateVersion())
                .param("runtimePlanHash", parent.planHash()).param("decisionHash", plan.planHash())
                .param("requestHash", requestHash).param("keyHash", keyHash)
                .param("units", json(plan.units())).param("reasons", json(plan.reasonCodes()))
                .param("actorId", actor.id()).update();
        return new RecoveryApplication(requestUuid, result, requestHash, true);
    }

    private long createAttempt(long projectId, RunRow parent, Actor actor, String startType) {
        long runId = jdbc.sql("""
                insert into akis.calistirma(
                    proje_id,is_talebi_id,deneme_no,yayin_ozeti,plan_ozeti,
                    baslatma_turu,onceki_calistirma_id,uuid,olusturan_kullanici_id)
                select :projectId,:jobId,max(deneme_no)+1,:releaseHash,:planHash,
                       :startType,:parentRunId,:runUuid,:actorId
                  from akis.calistirma where is_talebi_id=:jobId
                returning id
                """)
                .param("projectId", projectId).param("jobId", parent.jobRequestId())
                .param("releaseHash", parent.releaseHash()).param("planHash", parent.planHash())
                .param("startType", startType).param("parentRunId", parent.runId())
                .param("runUuid", UUID.randomUUID()).param("actorId", actor.id())
                .query(Long.class).single();
        initializeRun(projectId, runId, actor, parent.runUuid(), startType);
        return runId;
    }

    private long createRestart(long projectId, RunRow parent, Actor actor, String requestHash) {
        long jobId = jdbc.sql("""
                insert into akis.is_talebi(
                    proje_id,yayin_id,istek_ozeti,is_turu,oncelik,planlanan_zaman,
                    parametre_sema_surumu,parametre,uuid,olusturan_kullanici_id)
                select proje_id,yayin_id,:requestHash,is_turu,oncelik,null,
                       parametre_sema_surumu,parametre,:jobUuid,:actorId
                  from akis.is_talebi where id=:jobId
                returning id
                """).param("requestHash", requestHash).param("jobUuid", UUID.randomUUID())
                .param("actorId", actor.id()).param("jobId", parent.jobRequestId())
                .query(Long.class).single();
        long runId = jdbc.sql("""
                insert into akis.calistirma(
                    proje_id,is_talebi_id,deneme_no,yayin_ozeti,plan_ozeti,
                    baslatma_turu,uuid,olusturan_kullanici_id)
                values(:projectId,:jobId,1,:releaseHash,:planHash,'ILK',:runUuid,:actorId)
                returning id
                """).param("projectId", projectId).param("jobId", jobId)
                .param("releaseHash", parent.releaseHash()).param("planHash", parent.planHash())
                .param("runUuid", UUID.randomUUID()).param("actorId", actor.id())
                .query(Long.class).single();
        initializeRun(projectId, runId, actor, parent.runUuid(), "RESTART");
        return runId;
    }

    private void initializeRun(
            long projectId, long runId, Actor actor, UUID parentRunUuid, String action) {
        jdbc.sql("""
                insert into akis.calistirma_durumu(
                    proje_id,calistirma_id,durum,son_olay_no,uuid,
                    olusturan_kullanici_id,guncellenme_zamani,guncelleyen_kullanici_id)
                values(:projectId,:runId,'BEKLIYOR',1,:uuid,:actorId,clock_timestamp(),:actorId)
                """).param("projectId", projectId).param("runId", runId)
                .param("uuid", UUID.randomUUID()).param("actorId", actor.id()).update();
        jdbc.sql("""
                insert into akis.calistirma_olayi(
                    proje_id,calistirma_id,olay_no,tur,olay_zamani,veri,uuid,
                    olusturan_kullanici_id)
                values(:projectId,:runId,1,'RECOVERY_ATTEMPT_CREATED',clock_timestamp(),
                    jsonb_build_object('parentRunUuid',cast(:parentUuid as text),'action',:action),
                    :uuid,:actorId)
                """).param("projectId", projectId).param("runId", runId)
                .param("parentUuid", parentRunUuid.toString()).param("action", action)
                .param("uuid", UUID.randomUUID()).param("actorId", actor.id()).update();
    }

    private String selectApplication() {
        return """
                select kr.uuid as request_uuid,kr.istek_ozeti,
                       coalesce(child.id,parent.id) as run_id,
                       coalesce(child.is_talebi_id,parent.is_talebi_id) as job_request_id,
                       coalesce(child_state.id,parent_state.id) as state_id,
                       coalesce(child_job.uuid,parent_job.uuid) as job_request_uuid,
                       coalesce(child.uuid,parent.uuid) as run_uuid,
                       publication.uuid as publication_uuid,
                       coalesce(child.deneme_no,parent.deneme_no) as deneme_no,
                       coalesce(child.baslatma_turu,parent.baslatma_turu) as baslatma_turu,
                       coalesce(child_state.durum,parent_state.durum) as durum_kodu,
                       coalesce(child.yayin_ozeti,parent.yayin_ozeti) as yayin_ozeti,
                       coalesce(child.plan_ozeti,parent.plan_ozeti) as plan_ozeti,
                       coalesce(child_state.son_olay_no,parent_state.son_olay_no) as son_olay_no,
                       coalesce(child.olusturulma_zamani,parent.olusturulma_zamani) as olusturulma_zamani,
                       coalesce(child_state.baslama_zamani,parent_state.baslama_zamani) as baslama_zamani,
                       coalesce(child_state.bitis_zamani,parent_state.bitis_zamani) as bitis_zamani,
                       coalesce(child_state.iptal_isteme_zamani,parent_state.iptal_isteme_zamani) as iptal_isteme_zamani
                  from akis.kurtarma_talebi kr
                  join akis.calistirma parent on parent.id=kr.onceki_calistirma_id
                  join akis.calistirma_durumu parent_state on parent_state.calistirma_id=parent.id
                  join akis.is_talebi parent_job on parent_job.id=parent.is_talebi_id
                  join akis.yayin publication on publication.id=parent_job.yayin_id
                  join akis.proje p on p.id=parent.proje_id
                  left join akis.calistirma child on child.id=kr.yeni_calistirma_id
                  left join akis.calistirma_durumu child_state on child_state.calistirma_id=child.id
                  left join akis.is_talebi child_job on child_job.id=child.is_talebi_id
                """;
    }

    private RecoveryApplication mapApplication(ResultSet rs, int row) throws SQLException {
        return new RecoveryApplication(
                rs.getObject("request_uuid", UUID.class), mapRun(rs),
                rs.getString("istek_ozeti"), false);
    }

    private RunRow findRun(long runId) {
        return jdbc.sql("""
                select j.id as job_request_id,r.id as run_id,d.id as state_id,
                       j.uuid as job_request_uuid,r.uuid as run_uuid,y.uuid as publication_uuid,
                       r.deneme_no,r.baslatma_turu,d.durum as durum_kodu,r.yayin_ozeti,r.plan_ozeti,
                       d.son_olay_no,r.olusturulma_zamani,d.baslama_zamani,d.bitis_zamani,
                       d.iptal_isteme_zamani
                  from akis.calistirma r join akis.calistirma_durumu d on d.calistirma_id=r.id
                  join akis.is_talebi j on j.id=r.is_talebi_id join akis.yayin y on y.id=j.yayin_id
                 where r.id=:runId
                """).param("runId", runId).query((rs, row) -> mapRun(rs)).single();
    }

    private RunRow mapRun(ResultSet rs) throws SQLException {
        return new RunRow(rs.getLong("job_request_id"),rs.getLong("run_id"),rs.getLong("state_id"),
                rs.getObject("job_request_uuid",UUID.class),rs.getObject("run_uuid",UUID.class),
                rs.getObject("publication_uuid",UUID.class),rs.getInt("deneme_no"),
                rs.getString("baslatma_turu"),rs.getString("durum_kodu"),
                rs.getString("yayin_ozeti"),rs.getString("plan_ozeti"),rs.getLong("son_olay_no"),
                rs.getObject("olusturulma_zamani",OffsetDateTime.class),
                rs.getObject("baslama_zamani",OffsetDateTime.class),
                rs.getObject("bitis_zamani",OffsetDateTime.class),
                rs.getObject("iptal_isteme_zamani",OffsetDateTime.class));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Recovery evidence is invalid."); }
    }
}

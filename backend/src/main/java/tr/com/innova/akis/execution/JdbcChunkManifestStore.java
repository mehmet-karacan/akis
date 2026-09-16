package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
final class JdbcChunkManifestStore implements ChunkManifestStore {

    private final JdbcClient jdbc;
    JdbcChunkManifestStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public RecordedIntent record(Intent intent) {
        validate(intent);
        UUID chunkUuid = UUID.randomUUID();
        jdbc.sql("""
                insert into akis.yukleme_parcasi(
                    uuid,proje_id,yukleme_bolumu_id,workspace_uuid,
                    sira_no,work_unit_key,runtime_plan_ozeti,girdi_ozeti,
                    alt_sinir,ust_sinir,son_anahtar,payload_ozeti,
                    girdi_satir_sayisi,girdi_bayt_sayisi,durum)
                select :chunkUuid,j.proje_id,b.id,:workspaceUuid,
                       :sequence,:workUnitKey,:runtimePlanHash,:inputHash,
                       cast(:lowerBoundary as jsonb),cast(:upperBoundary as jsonb),cast(:lastKey as jsonb),:payloadHash,
                       :rowCount,:byteCount,'INTENT_RECORDED'
                  from akis.yukleme_bolumu b
                  join akis.veri_yukleme_plani p on p.id=b.veri_yukleme_plani_id
                  join akis.is_talebi j on j.id=p.is_talebi_id
                 where b.uuid=:partitionUuid and j.uuid=:jobRequestUuid
                   and p.adim_kodu=:stepCode
                   and j.proje_id=(select id from akis.proje where uuid=:projectUuid)
                on conflict (yukleme_bolumu_id,sira_no) do nothing
                """).param("chunkUuid", chunkUuid).param("workspaceUuid", intent.workspaceUuid())
                .param("sequence", intent.sequence()).param("workUnitKey", intent.workUnitKey())
                .param("runtimePlanHash", intent.runtimePlanHash()).param("inputHash", intent.inputHash())
                .param("lowerBoundary", boundary(intent.lowerExclusive()), java.sql.Types.VARCHAR)
                .param("upperBoundary", boundary(intent.upperInclusive()), java.sql.Types.VARCHAR)
                .param("lastKey", boundary(intent.lastKey()), java.sql.Types.VARCHAR)
                .param("payloadHash", intent.payloadHash()).param("rowCount", intent.rowCount())
                .param("byteCount", intent.byteCount()).param("partitionUuid", intent.partitionUuid())
                .param("jobRequestUuid", intent.jobRequestUuid()).param("stepCode", intent.stepCode())
                .param("projectUuid", intent.projectUuid()).update();
        RecordedIntent stored = jdbc.sql(select() + " where c.yukleme_bolumu_id=(select id from akis.yukleme_bolumu where uuid=:partitionUuid) and c.sira_no=:sequence")
                .param("partitionUuid", intent.partitionUuid()).param("sequence", intent.sequence())
                .query((rs, row) -> map(rs)).single();
        if (!stored.intent().equals(intent)) throw new ChunkManifestConflictException();
        return stored;
    }

    @Override
    @Transactional
    public boolean markDispatched(UUID chunkUuid, UUID runUuid, long generation, String worker) {
        if (generation < 1 || worker == null || worker.isBlank()) return false;
        int updated = jdbc.sql("""
                with locked as (
                  select c.id,c.proje_id,r.id as run_id,r.deneme_no
                    from akis.yukleme_parcasi c
                    join akis.yukleme_bolumu b on b.id=c.yukleme_bolumu_id
                    join akis.veri_yukleme_plani p on p.id=b.veri_yukleme_plani_id
                    join akis.calistirma r on r.is_talebi_id=p.is_talebi_id
                    join akis.calistirma_durumu d on d.calistirma_id=r.id
                   where c.uuid=:chunkUuid and r.uuid=:runUuid
                     and d.nesil_no=:generation and d.isleyici_referansi=:worker
                     and d.kiralama_bitis_zamani>clock_timestamp()
                     and c.durum in('INTENT_RECORDED','ROLLBACK_CONFIRMED') for update of c
                ), moved as (
                  update akis.yukleme_parcasi c set durum='DISPATCHED',son_hata_kodu=null
                    from locked l where c.id=l.id
                  returning c.id,c.proje_id
                )
                insert into akis.yukleme_parca_deneme(
                    proje_id,yukleme_parcasi_id,calistirma_id,deneme_no,
                    calistirma_nesil_no,isleyici_referansi,outcome)
                select m.proje_id,m.id,l.run_id,l.deneme_no,:generation,:worker,'NOT_ATTEMPTED'
                  from moved m join locked l on l.id=m.id
                """).param("chunkUuid", chunkUuid).param("runUuid", runUuid)
                .param("generation", generation).param("worker", worker).update();
        return updated == 1;
    }

    @Override @Transactional public boolean confirm(UUID chunkUuid, String receipt, BigDecimal lastKey) {
        return transition(chunkUuid, "DISPATCHED", "COMMIT_CONFIRMED", receipt, lastKey, null);
    }
    @Override @Transactional public boolean rollback(UUID chunkUuid, String errorCode) {
        return transition(chunkUuid, "DISPATCHED", "ROLLBACK_CONFIRMED", null, null, errorCode);
    }
    @Override @Transactional public boolean unknown(UUID chunkUuid, String errorCode) {
        return transition(chunkUuid, "DISPATCHED", "OUTCOME_UNKNOWN", null, null, errorCode);
    }

    @Override
    public Optional<RecordedIntent> find(UUID chunkUuid) {
        return jdbc.sql(select() + " where c.uuid=:chunkUuid").param("chunkUuid", chunkUuid)
                .query((rs, row) -> map(rs)).optional();
    }

    @Transactional
    boolean transition(UUID chunkUuid, String expected, String next, String receipt,
            BigDecimal lastKey, String errorCode) {
        int updated = jdbc.sql("""
                update akis.yukleme_parcasi set durum=:next,
                       hedef_defter_referansi=:receipt,
                       son_anahtar=coalesce(cast(:lastKey as jsonb),son_anahtar),
                       son_hata_kodu=:errorCode
                 where uuid=:chunkUuid and durum=:expected
                """).param("next", next).param("receipt", receipt, java.sql.Types.VARCHAR)
                .param("lastKey", boundary(lastKey), java.sql.Types.VARCHAR)
                .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                .param("chunkUuid", chunkUuid).param("expected", expected).update();
        if (updated == 1) {
            int attemptUpdated = updateAttempt(chunkUuid, next, errorCode);
            if (attemptUpdated != 1) throw new IllegalStateException(
                    "Chunk attempt projection could not be finalized.");
            return true;
        }
        Integer matched = jdbc.sql("""
                select count(*) from akis.yukleme_parcasi
                 where uuid=:uuid and durum=:next
                   and (:receipt is null or hedef_defter_referansi=:receipt)
                   and (:errorCode is null or son_hata_kodu=:errorCode)
                """).param("uuid", chunkUuid).param("next", next)
                .param("receipt", receipt, java.sql.Types.VARCHAR)
                .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                .query(Integer.class).single();
        return matched == 1;
    }

    private int updateAttempt(UUID chunkUuid, String next, String errorCode) {
        return jdbc.sql("""
                update akis.yukleme_parca_deneme set outcome=:outcome,
                       bitis_zamani=clock_timestamp(),hata_kodu=:errorCode
                 where id=(select d.id from akis.yukleme_parca_deneme d
                            join akis.yukleme_parcasi c on c.id=d.yukleme_parcasi_id
                           where c.uuid=:uuid order by d.id desc limit 1)
                   and outcome='NOT_ATTEMPTED'
                """).param("outcome", next.equals("COMMIT_CONFIRMED") ? "COMMIT_CONFIRMED"
                        : next.equals("ROLLBACK_CONFIRMED") ? "ROLLBACK_CONFIRMED" : "OUTCOME_UNKNOWN")
                .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                .param("uuid", chunkUuid).update();
    }

    private String select() {
        return """
                select c.uuid,c.durum,pr.uuid as project_uuid,j.uuid as job_request_uuid,p.adim_kodu,
                       b.uuid as partition_uuid,c.workspace_uuid,c.sira_no,c.work_unit_key,
                       c.runtime_plan_ozeti,c.girdi_ozeti,c.alt_sinir->>'value' as lower_value,
                       c.ust_sinir->>'value' as upper_value,c.son_anahtar->>'value' as last_value,
                       c.payload_ozeti,c.girdi_satir_sayisi,c.girdi_bayt_sayisi,
                       c.hedef_defter_referansi
                  from akis.yukleme_parcasi c join akis.yukleme_bolumu b on b.id=c.yukleme_bolumu_id
                  join akis.veri_yukleme_plani p on p.id=b.veri_yukleme_plani_id
                  join akis.is_talebi j on j.id=p.is_talebi_id join akis.proje pr on pr.id=j.proje_id
                """;
    }

    private RecordedIntent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Intent intent = new Intent(rs.getObject("project_uuid", UUID.class),
                rs.getObject("job_request_uuid", UUID.class), rs.getString("adim_kodu"),
                rs.getObject("partition_uuid", UUID.class), rs.getObject("workspace_uuid", UUID.class),
                rs.getLong("sira_no"), rs.getString("work_unit_key"),
                rs.getString("runtime_plan_ozeti"), rs.getString("girdi_ozeti"),
                decimal(rs.getString("lower_value")), decimal(rs.getString("upper_value")),
                decimal(rs.getString("last_value")), rs.getString("payload_ozeti"),
                rs.getLong("girdi_satir_sayisi"), rs.getLong("girdi_bayt_sayisi"));
        return new RecordedIntent(rs.getObject("uuid", UUID.class), intent, rs.getString("durum"),
                rs.getString("hedef_defter_referansi"));
    }

    private void validate(Intent value) {
        if (value == null || value.projectUuid() == null || value.jobRequestUuid() == null
                || value.stepCode() == null || !value.stepCode().matches("[A-Z][A-Z0-9_]{0,99}")
                || value.partitionUuid() == null
                || value.workspaceUuid() == null || value.sequence() < 1
                || !hash(value.workUnitKey()) || !hash(value.runtimePlanHash())
                || !hash(value.inputHash()) || !hash(value.payloadHash())
                || value.upperInclusive() == null || value.lastKey() == null
                || value.rowCount() < 0 || value.byteCount() < 0) {
            throw new IllegalArgumentException("Chunk intent is incomplete.");
        }
    }
    private static String boundary(BigDecimal value) {
        return value == null ? null : "{\"type\":\"NUMBER\",\"value\":\"" + value.toPlainString() + "\"}";
    }
    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}

final class ChunkManifestConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}

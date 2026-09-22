package tr.com.innova.akis.knowledge;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KmStepJournal {
    private final JdbcClient jdbc;
    public KmStepJournal(JdbcClient jdbc) { this.jdbc=jdbc; }
    public record Row(long generation,int ordinal,String stepCode,String operation,String site,String slot,String state,
            Long affectedRows,String errorCode,OffsetDateTime startedAt,OffsetDateTime completedAt) { }
    public record Reconciliation(String outcome,Long rows) { }
    @Transactional(readOnly=true)
    public Reconciliation reconciliation(UUID project,UUID run) {
        return jdbc.sql("""
                select m.sonuc,m.satir_sayisi from akis.mutabakat_kaniti m
                join akis.proje p on p.id=m.proje_id
                join akis.calistirma c on c.proje_id=p.id and c.id=m.calistirma_id
                where p.uuid=:project and c.uuid=:run
                """).param("project",project).param("run",run)
                .query((r,n)->new Reconciliation(r.getString("sonuc"),r.getObject("satir_sayisi",Long.class))).optional().orElse(null);
    }
    @Transactional
    public void prepare(WorkObjectStore.Owner owner,String planHash,AkisKmInterpreter.Plan plan) {
        int ordinal=0;
        for(var step:plan.steps()) {
            int written=jdbc.sql("""
                    insert into akis.km_step_journal(proje_id,calistirma_id,generation,worker_reference,ordinal,step_code,operation,site,slot,runtime_plan_hash)
                    select p.id,c.id,:generation,:worker,:ordinal,:code,:operation,:site,:slot,:hash
                    from akis.proje p join akis.calistirma c on c.proje_id=p.id
                    join akis.calistirma_durumu d on d.proje_id=p.id and d.calistirma_id=c.id
                    join akis.is_talebi t on t.proje_id=p.id and t.id=c.is_talebi_id
                    join akis.yayin y on y.proje_id=p.id and y.id=t.yayin_id
                    where p.uuid=:project and c.uuid=:run and d.nesil_no=:generation and d.isleyici_referansi=:worker
                      and d.kiralama_bitis_zamani>clock_timestamp() and d.durum='CALISIYOR'
                      and y.fiziksel_manifesto->>'runtimePlanHash'=:hash
                      and y.fiziksel_manifesto->>'runtimeCapability'='ORACLE_STAGED_MAPPING_V1'
                    """).param("project",owner.projectUuid()).param("run",owner.runUuid()).param("generation",owner.generation())
                    .param("worker",owner.worker()).param("ordinal",++ordinal).param("code",step.id()).param("operation",step.operation().name())
                    .param("site",step.site().name()).param("slot",step.slot()).param("hash",planHash).update();
            if(written!=1) throw new IllegalStateException("KM adım kaydı için geçerli çalışma yetkisi yok.");
        }
    }
    @Transactional
    public void transition(WorkObjectStore.Owner owner,int ordinal,String next,Long rows,String error) {
        if(!Set.of("RUNNING","SUCCEEDED","FAILED","UNKNOWN").contains(next) || rows!=null && rows<0
                || error!=null && !error.matches("[A-Z][A-Z0-9_]{0,79}")) throw new IllegalArgumentException();
        int changed=jdbc.sql("""
                update akis.km_step_journal j set state=:next,affected_rows=:rows,error_code=:error,
                  started_at=case when :next='RUNNING' then clock_timestamp() else j.started_at end,
                  completed_at=case when :next='RUNNING' then null else clock_timestamp() end
                from akis.calistirma c join akis.proje p on p.id=c.proje_id
                join akis.calistirma_durumu d on d.proje_id=p.id and d.calistirma_id=c.id
                where j.proje_id=p.id and j.calistirma_id=c.id and p.uuid=:project and c.uuid=:run
                  and j.generation=:generation and j.worker_reference=:worker and j.ordinal=:ordinal
                  and d.nesil_no=:generation and d.isleyici_referansi=:worker and d.kiralama_bitis_zamani>clock_timestamp()
                  and d.durum in ('CALISIYOR','YAYINLANIYOR')
                  and j.state=case when :next='RUNNING' then 'PENDING' else 'RUNNING' end
                """).param("project",owner.projectUuid()).param("run",owner.runUuid()).param("generation",owner.generation())
                .param("worker",owner.worker()).param("ordinal",ordinal).param("next",next)
                .param("rows",rows,java.sql.Types.BIGINT).param("error",error,java.sql.Types.VARCHAR).update();
        if(changed!=1) throw new IllegalStateException("KM adım kaydı güncellenemedi.");
    }
    /** RESUME: the step completed in the previous attempt and its effect (the sealed work table) was adopted. */
    @Transactional
    public void skipped(WorkObjectStore.Owner owner,int ordinal,Long rows) {
        int changed=jdbc.sql("""
                update akis.km_step_journal j set state='SKIPPED',affected_rows=:rows,started_at=clock_timestamp(),completed_at=clock_timestamp()
                from akis.calistirma c join akis.proje p on p.id=c.proje_id
                join akis.calistirma_durumu d on d.proje_id=p.id and d.calistirma_id=c.id
                where j.proje_id=p.id and j.calistirma_id=c.id and p.uuid=:project and c.uuid=:run
                  and j.generation=:generation and j.worker_reference=:worker and j.ordinal=:ordinal and j.state='PENDING'
                  and d.nesil_no=:generation and d.isleyici_referansi=:worker and d.kiralama_bitis_zamani>clock_timestamp() and d.durum='CALISIYOR'
                """).param("project",owner.projectUuid()).param("run",owner.runUuid()).param("generation",owner.generation())
                .param("worker",owner.worker()).param("ordinal",ordinal).param("rows",rows,java.sql.Types.BIGINT).update();
        if(changed!=1) throw new IllegalStateException("KM adımı atlanmış olarak kaydedilemedi.");
    }
    public AkisKmInterpreter.Observer observer(WorkObjectStore.Owner owner) {
        return new AkisKmInterpreter.Observer() {
            public void before(int ordinal,AkisKmLanguage.Step step) { transition(owner,ordinal,"RUNNING",null,null); }
            public void skipped(int ordinal,AkisKmLanguage.Step step,long rows) { KmStepJournal.this.skipped(owner,ordinal,step.operation()==AkisKmLanguage.Operation.TRANSFER_JDBC?rows:null); }
            public void succeeded(int ordinal,AkisKmInterpreter.StepResult result) { transition(owner,ordinal,"SUCCEEDED",result.affectedRows(),null); }
            public void failed(int ordinal,AkisKmLanguage.Step step,RuntimeException failure) {
                boolean unknown=step.operation()==AkisKmLanguage.Operation.ATOMIC_REPLACE && !(failure instanceof StagedKmRuntime.PublishFailure p && p.outcome()==StagedKmRuntime.PublishOutcome.ROLLED_BACK);
                transition(owner,ordinal,unknown?"UNKNOWN":"FAILED",null,unknown?"KM_RECONCILIATION_REQUIRED":"KM_STEP_FAILED");
            }
        };
    }
    @Transactional(readOnly=true)
    public List<Row> list(UUID project,UUID run) {
        return jdbc.sql("""
                select j.* from akis.km_step_journal j join akis.proje p on p.id=j.proje_id
                join akis.calistirma c on c.proje_id=p.id and c.id=j.calistirma_id
                where p.uuid=:project and c.uuid=:run order by j.generation,j.ordinal
                """).param("project",project).param("run",run).query((r,n)->new Row(r.getLong("generation"),r.getInt("ordinal"),r.getString("step_code"),
                        r.getString("operation"),r.getString("site"),r.getString("slot"),r.getString("state"),r.getObject("affected_rows",Long.class),
                        r.getString("error_code"),r.getObject("started_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class))).list();
    }
}

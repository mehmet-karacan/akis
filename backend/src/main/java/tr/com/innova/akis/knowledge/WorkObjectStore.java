package tr.com.innova.akis.knowledge;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;

@Service
public class WorkObjectStore {
    private final JdbcClient jdbc;
    public WorkObjectStore(JdbcClient jdbc) { this.jdbc=jdbc; }
    public record Owner(UUID projectUuid,UUID runUuid,long generation,String worker) { }
    public record WorkArea(UUID physicalSchemaUuid,long policyVersion) {
        public WorkArea { Objects.requireNonNull(physicalSchemaUuid); if(policyVersion<1) throw new IllegalArgumentException("Çalışma politikası sürümü gerekir."); }
    }
    public record ObjectRow(UUID uuid,String slot,String databaseIdentity,String owner,String name,Long objectId,
            String structureHash,State state,Long rows,Long bytes,String payloadHash) { }
    @Transactional
    public UUID allocate(Owner token,String slot,String databaseIdentity,String owner,String name,String structureHash,WorkArea workArea) {
        Objects.requireNonNull(token); StagedMappingDefinition.identifier(owner); StagedMappingDefinition.identifier(name);
        Objects.requireNonNull(workArea);
        if (!name.startsWith("AKIS_") || !slot.matches("[A-Z][A-Z0-9_]{0,63}") || databaseIdentity==null || databaseIdentity.isBlank()
                || !structureHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Çalışma nesnesi tahsis kimliği geçersiz.");
        // Serialize reservations across aliases/projects for the same actual Oracle work owner.
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .param("scope",databaseIdentity+"|"+owner).query((r,n)->true).single();
        int maximum=jdbc.sql("""
                select k.max_objects from akis.km_work_area_policy k join akis.proje p on p.id=k.proje_id
                join akis.fiziksel_sema f on f.id=k.fiziksel_sema_id
                join akis.baglanti b on b.id=f.baglanti_id
                where p.uuid=:project and f.uuid=:schema and f.calisma_sema_adi=:owner and k.enabled and k.version=:version
                  and p.arsivlenme_zamani is null and f.durum='ETKIN' and b.durum='ETKIN'
                  and b.saglayici_turu='ORACLE' for share of k
                """).param("project",token.projectUuid()).param("schema",workArea.physicalSchemaUuid()).param("owner",owner)
                .param("version",workArea.policyVersion()).query(Integer.class).optional()
                .orElseThrow(()->new IllegalStateException("Çalışma alanı politikası değişmiş veya kapatılmış."));
        long used=jdbc.sql("select count(*) from akis.km_work_object where database_identity=:db and owner_name=:owner and state<>'DROPPED'")
                .param("db",databaseIdentity).param("owner",owner).query(Long.class).single();
        if(used>=maximum) throw new IllegalStateException("Çalışma alanı nesne kotası dolu; bekleyen nesneler incelenmelidir.");
        return jdbc.sql("""
            insert into akis.km_work_object(proje_id,calistirma_id,generation,worker_reference,slot,database_identity,owner_name,object_name,structure_hash)
            select p.id,c.id,:generation,:worker,:slot,:db,:owner,:name,:hash
            from akis.proje p join akis.calistirma c on c.proje_id=p.id
            join akis.calistirma_durumu d on d.calistirma_id=c.id and d.proje_id=p.id
            where p.uuid=:project and c.uuid=:run and d.nesil_no=:generation and d.isleyici_referansi=:worker
              and d.kiralama_bitis_zamani>clock_timestamp() and d.durum in ('SAHIPLENILDI','CALISIYOR')
            returning uuid
            """).param("project",token.projectUuid()).param("run",token.runUuid()).param("generation",token.generation())
            .param("worker",token.worker()).param("slot",slot).param("db",databaseIdentity).param("owner",owner)
            .param("name",name).param("hash",structureHash).query(UUID.class).optional()
            .orElseThrow(() -> new IllegalStateException("Çalışma nesnesi için geçerli kiralama yok."));
    }
    @Transactional
    public void transition(Owner token,UUID object,State from,State to,Long objectId,JdbcStagingTransfer.Result sealed) {
        WorkObjectLifecycle.requireTransition(from,to);
        if ((objectId != null && (from != State.CREATING || to != State.READY || objectId <= 0))
                || (to == State.READY && objectId == null)
                || (sealed != null && (from != State.LOADING || to != State.SEALED))
                || (to == State.SEALED && sealed == null)) throw new IllegalArgumentException("Nesne kimliği ve mühür yalnız ilgili geçişte kaydedilebilir.");
        int count=jdbc.sql("""
            update akis.km_work_object w set state=:next,object_id=coalesce(:objectId,w.object_id),
              row_count=coalesce(:rows,w.row_count),logical_bytes=coalesce(:bytes,w.logical_bytes),
              payload_hash=coalesce(:hash,w.payload_hash),updated_at=clock_timestamp()
            from akis.calistirma c join akis.proje p on p.id=c.proje_id
            join akis.calistirma_durumu d on d.proje_id=p.id and d.calistirma_id=c.id
            where w.uuid=:object and w.proje_id=p.id and w.calistirma_id=c.id
              and p.uuid=:project and c.uuid=:run and w.generation=:generation and w.worker_reference=:worker
              and w.state=:previous and d.nesil_no=:generation and d.isleyici_referansi=:worker
              and ((d.kiralama_bitis_zamani>clock_timestamp() and d.durum in ('SAHIPLENILDI','CALISIYOR','YAYINLANIYOR'))
                or (d.durum='BASARILI' and :next in ('CLEANUP_PENDING','DROPPED','REVIEW_REQUIRED')))
            """).param("next",to.name()).param("previous",from.name()).param("object",object)
            .param("project",token.projectUuid()).param("run",token.runUuid()).param("generation",token.generation()).param("worker",token.worker())
            .param("objectId",objectId,java.sql.Types.BIGINT).param("rows",sealed==null?null:sealed.rows(),java.sql.Types.BIGINT)
            .param("bytes",sealed==null?null:sealed.logicalBytes(),java.sql.Types.BIGINT)
            .param("hash",sealed==null?null:sealed.payloadHash(),java.sql.Types.VARCHAR).update();
        if (count!=1) throw new IllegalStateException("Çalışma nesnesi geçişi reddedildi; kiralama veya nesil değişmiş.");
    }
    @Transactional(readOnly=true)
    public List<ObjectRow> list(UUID project,UUID run) {
        return jdbc.sql("""
            select w.* from akis.km_work_object w join akis.proje p on p.id=w.proje_id
            join akis.calistirma c on c.id=w.calistirma_id and c.proje_id=p.id where p.uuid=:project and c.uuid=:run
            order by w.generation,w.slot
            """).param("project",project).param("run",run).query((rs,n)->new ObjectRow(rs.getObject("uuid",UUID.class),rs.getString("slot"),
            rs.getString("database_identity"),rs.getString("owner_name"),rs.getString("object_name"),rs.getObject("object_id",Long.class),
            rs.getString("structure_hash"),State.valueOf(rs.getString("state")),rs.getObject("row_count",Long.class),
            rs.getObject("logical_bytes",Long.class),rs.getString("payload_hash"))).list();
    }
    /** A registered, not yet dropped work object already carries this physical name (an earlier attempt's table). */
    @Transactional(readOnly=true)
    public boolean nameInUse(String databaseIdentity,String owner,String name) {
        return Boolean.TRUE.equals(jdbc.sql("select exists(select 1 from akis.km_work_object where database_identity=:db and owner_name=:owner and object_name=:name and state<>'DROPPED')")
                .param("db",databaseIdentity).param("owner",owner).param("name",name).query(Boolean.class).single());
    }
    /** RESUME: the sealed work table of the failed attempt moves to the resuming run of the same job under its live lease. */
    @Transactional
    public ObjectRow adopt(Owner token,UUID previousRun,UUID object) {
        int count=jdbc.sql("""
            update akis.km_work_object w set calistirma_id=c.id,generation=:generation,worker_reference=:worker,
              devralinan_calistirma_id=w.calistirma_id,devralinma_zamani=clock_timestamp(),updated_at=clock_timestamp()
            from akis.calistirma c join akis.proje p on p.id=c.proje_id
            join akis.calistirma_durumu d on d.proje_id=p.id and d.calistirma_id=c.id
            join akis.calistirma prev on prev.proje_id=p.id and prev.uuid=:previous and prev.is_talebi_id=c.is_talebi_id
            where w.uuid=:object and w.proje_id=p.id and w.calistirma_id=prev.id and w.state='SEALED'
              and w.object_id is not null and w.payload_hash is not null
              and p.uuid=:project and c.uuid=:run and c.baslatma_turu='DEVAM_ET'
              and d.nesil_no=:generation and d.isleyici_referansi=:worker
              and d.kiralama_bitis_zamani>clock_timestamp() and d.durum in ('SAHIPLENILDI','CALISIYOR')
            """).param("object",object).param("previous",previousRun).param("project",token.projectUuid()).param("run",token.runUuid())
            .param("generation",token.generation()).param("worker",token.worker()).update();
        if(count!=1) throw new IllegalStateException("Mühürlü çalışma tablosu devralınamadı; önceki deneme veya kiralama uyuşmuyor.");
        return list(token.projectUuid(),token.runUuid()).stream().filter(row->row.uuid().equals(object)).findFirst().orElseThrow();
    }
    /** Single claimant after confirmed success. Unknown/failed runs and another generation cannot authorize DDL. */
    @Transactional
    public ObjectRow claimCleanup(Owner token, UUID object) {
        int count=jdbc.sql("""
            update akis.km_work_object w set state='CLEANUP_PENDING',updated_at=clock_timestamp()
            from akis.calistirma c join akis.proje p on p.id=c.proje_id
            join akis.calistirma_durumu d on d.proje_id=p.id and d.calistirma_id=c.id
            where w.uuid=:object and w.proje_id=p.id and w.calistirma_id=c.id
              and p.uuid=:project and c.uuid=:run and w.generation=:generation and w.worker_reference=:worker
              and d.nesil_no=:generation and d.isleyici_referansi=:worker and d.durum='BASARILI'
              and w.state='CONSUMED' and w.object_id is not null and w.payload_hash is not null
            """).param("object",object).param("project",token.projectUuid()).param("run",token.runUuid())
            .param("generation",token.generation()).param("worker",token.worker()).update();
        if(count!=1) throw new IllegalStateException("Temizleme için doğrulanmış başarılı çalışma/sahiplik bulunamadı.");
        return list(token.projectUuid(),token.runUuid()).stream().filter(row->row.uuid().equals(object)).findFirst().orElseThrow();
    }
}

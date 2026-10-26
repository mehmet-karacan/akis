package tr.com.innova.akis.knowledge;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.innova.akis.metadata.ApiException;

@Service
public class WorkAreaPolicyService {
    public record Policy(boolean enabled, boolean allowSameSchema, int maxObjects, long maxRowsPerRun,
            long maxBytesPerRun, int retentionHours) {
        public Policy {
            if (maxObjects<1 || maxObjects>1000 || maxRowsPerRun<1 || maxRowsPerRun>100_000_000
                    || maxBytesPerRun<1 || maxBytesPerRun>1_099_511_627_776L || retentionHours<1 || retentionHours>8760)
                throw new IllegalArgumentException("Çalışma alanı sınırları geçersiz.");
        }
    }
    public record View(Policy policy,long version) { }
    private record Scope(long project,long schema) { }
    private final JdbcClient jdbc;
    private final TransferResourcePolicy resources;
    public WorkAreaPolicyService(JdbcClient jdbc) { this(jdbc,new TransferResourcePolicy(100_000_000,1_099_511_627_776L,1000,256,1)); }
    @Autowired
    public WorkAreaPolicyService(JdbcClient jdbc,TransferResourcePolicy resources) { this.jdbc=jdbc;this.resources=resources; }
    public View automaticView(View view) { return new View(resources.managed(view.policy()),view.version()); }
    public Policy automaticPolicy(Policy policy) { return resources.managed(policy); }
    public StagedMappingDefinition.Options automaticOptions(StagedMappingDefinition.Options options) { return resources.resolve(options); }
    public StagedMappingDefinition.Options automaticOptions(StagedMappingDefinition.Options options,UUID environment) {
        return resources.resolve(options,environmentMemoryMiB(environment));
    }
    private long environmentMemoryMiB(UUID environment) {
        String policy=jdbc.sql("select politika::text from akis.ortam where uuid=:uuid and durum='ETKIN'")
                .param("uuid",environment).query(String.class).single();
        return TransferMemoryPolicy.fromPolicy(new tools.jackson.databind.ObjectMapper().readTree(policy));
    }
    public void requireEnvironmentMemory(StagedMappingDefinition.Options options,UUID environment) {
        if(options.automatic() && options.memoryBudgetBytes()!=null &&
                options.memoryBudgetBytes()>environmentMemoryMiB(environment)*TransferMemoryPolicy.MIB)
            throw new IllegalStateException("Ortam bellek bütçesi azaltılmış; aktarımı yeniden yayınlayın.");
    }
    public TransferResourcePolicy.Permit acquireTransfer(Runnable checkpoint) { return resources.acquire(checkpoint); }
    public TransferResourcePolicy.Permit acquireTransfer(StagedMappingDefinition.Options options,Runnable checkpoint) { return resources.acquire(options,checkpoint); }
    public void requireCurrentOptions(StagedMappingDefinition.Options options) { resources.validate(options); }
    private Scope scope(UUID project,UUID schema,boolean lock) {
        return jdbc.sql("""
                select p.id project_id,f.id schema_id from akis.proje p
                cross join akis.fiziksel_sema f join akis.baglanti b on b.id=f.baglanti_id
                where p.uuid=:project and f.uuid=:schema and p.arsivlenme_zamani is null
                  and f.durum='ETKIN' and b.durum='ETKIN' and b.saglayici_turu in ('ORACLE','POSTGRESQL')
                """+(lock?" for update of f":"")).param("project",project).param("schema",schema)
                .query((r,n)->new Scope(r.getLong("project_id"),r.getLong("schema_id"))).optional()
                .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Çalışma şeması bulunamadı."));
    }
    private View read(Scope scope) {
        return jdbc.sql("select * from akis.km_work_area_policy where proje_id=:p and fiziksel_sema_id=:s")
                .param("p",scope.project()).param("s",scope.schema()).query((r,n)->new View(new Policy(
                        r.getBoolean("enabled"),r.getBoolean("allow_same_schema"),r.getInt("max_objects"),
                        r.getLong("max_rows_per_run"),r.getLong("max_bytes_per_run"),r.getInt("retention_hours")),r.getLong("version")))
                .optional().orElse(new View(new Policy(false,false,10,100_000,268_435_456,24),0));
    }
    @Transactional(readOnly=true)
    public View get(UUID project,UUID schema) { return read(scope(project,schema,false)); }
    @Transactional
    public View save(UUID project,UUID schema,long expectedVersion,Policy policy) {
        Objects.requireNonNull(policy);
        Scope scope=scope(project,schema,true);
        if(read(scope).version()!=expectedVersion)
            throw new ApiException(HttpStatus.CONFLICT,"STALE_VERSION","Çalışma alanı ayarları değişmiş; yeniden yükleyin.");
        jdbc.sql("""
                insert into akis.km_work_area_policy(proje_id,fiziksel_sema_id,enabled,allow_same_schema,max_objects,
                  max_rows_per_run,max_bytes_per_run,retention_hours) values(:p,:s,:enabled,:same,:objects,:rows,:bytes,:retention)
                on conflict(proje_id,fiziksel_sema_id) do update set enabled=excluded.enabled,allow_same_schema=excluded.allow_same_schema,
                  max_objects=excluded.max_objects,max_rows_per_run=excluded.max_rows_per_run,max_bytes_per_run=excluded.max_bytes_per_run,
                  retention_hours=excluded.retention_hours,version=km_work_area_policy.version+1,updated_at=clock_timestamp()
                """).param("p",scope.project()).param("s",scope.schema()).param("enabled",policy.enabled())
                .param("same",policy.allowSameSchema()).param("objects",policy.maxObjects()).param("rows",policy.maxRowsPerRun())
                .param("bytes",policy.maxBytesPerRun()).param("retention",policy.retentionHours()).update();
        return read(scope);
    }
    public static void requireAllowed(View view, StagedMappingDefinition.Options options, boolean sameSchema) {
        if(view.version()<1 || !view.policy().enabled() || sameSchema && !view.policy().allowSameSchema()
                || options.maxRows()>view.policy().maxRowsPerRun() || options.maxBytes()>view.policy().maxBytesPerRun())
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"WORK_AREA_POLICY_REJECTED",
                    "Çalışma alanı kapalı veya mapping çalışma alanı sınırlarını aşıyor.");
    }
}

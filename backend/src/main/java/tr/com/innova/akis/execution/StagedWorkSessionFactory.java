package tr.com.innova.akis.execution;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;
import static tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;
import static tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;

/** No fake catalog object for a table that does not yet exist; exact published staging binding only. */
@Component
class StagedWorkSessionFactory {
    static final class WorkPermit { private WorkPermit() { } }
    private static final WorkPermit PERMIT=new WorkPermit();
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final RuntimeOracleConnectionProvider connections;
    StagedWorkSessionFactory(JdbcClient jdbc,ObjectMapper mapper,RuntimeOracleConnectionProvider connections) {
        this.jdbc=jdbc;this.mapper=mapper;this.connections=connections;
    }
    @Transactional(readOnly=true)
    public ConnectionProfile profile(StagedRuntimePlan plan) {
        var stage=plan.staging();
        return jdbc.sql("""
                select p.uuid project_uuid,b.uuid connection_uuid,b.baglanti_modu,b.jndi_adi,b.surucu_sinifi,
                  b.sunucu_adi,case when b.saglayici_turu='ORACLE' then b.servis_adi else b.veritabani_adi end as servis_adi,b.sid,b.port,
                  'DISABLED' tls_mode,
                  jsonb_build_object('connectTimeoutMs',b.baglanti_zaman_asimi_ms,'readTimeoutMs',b.okuma_zaman_asimi_ms,
                    'networkTimeoutMs',b.okuma_zaman_asimi_ms,'queryTimeoutSeconds',b.sorgu_zaman_asimi_saniye) policy,
                  case when b.baglanti_modu='JNDI' then null else 'TABLO' end provider,b.sifre secret_path
                from akis.proje p join akis.yayin y on y.proje_id=p.id
                join akis.sema_eslemesi se on se.ortam_id=y.ortam_id
                join akis.mantiksal_sema ms on ms.id=se.mantiksal_sema_id
                join akis.fiziksel_sema f on f.id=se.fiziksel_sema_id
                join akis.baglanti b on b.id=f.baglanti_id
                where p.uuid=:project and y.durum='AKTIF' and y.fiziksel_manifesto->>'releaseHash'=:release
                  and y.fiziksel_manifesto->>'runtimeCapability'='ORACLE_STAGED_MAPPING_V1'
                  and y.fiziksel_manifesto->'stagedPlan'->>'physicalPlanHash'=:plan
                  and se.uuid=:binding and ms.uuid=:logical
                  and f.uuid=:schema and f.calisma_sema_adi=:owner and b.uuid=:connection and b.durum='ETKIN'
                  and b.saglayici_turu in ('ORACLE','POSTGRESQL') and p.arsivlenme_zamani is null
                  and f.durum='ETKIN' and ms.durum='ETKIN' and (b.baglanti_modu='JNDI' or b.sifre is not null)
                """).param("project",plan.projectUuid()).param("release",plan.releaseHash()).param("plan",plan.runtimePlanHash())
                .param("binding",UUID.fromString(stage.path("bindingUuid").asText()))
                .param("logical",UUID.fromString(stage.path("logicalSchemaUuid").asText())).param("schema",UUID.fromString(stage.path("physicalSchemaUuid").asText()))
                .param("owner",stage.path("owner").asText()).param("connection",UUID.fromString(stage.path("connectionVersionUuid").asText()))
                .query((r,n)->new ConnectionProfile(r.getObject("project_uuid",UUID.class),r.getObject("connection_uuid",UUID.class),
                        r.getString("baglanti_modu"),r.getString("jndi_adi"),r.getString("surucu_sinifi"),r.getString("sunucu_adi"),r.getString("servis_adi"),
                        r.getString("sid"),r.getInt("port"),r.getString("tls_mode"),mapper.readTree(r.getString("policy")),r.getString("provider"),r.getString("secret_path")))
                .optional().orElseThrow(()->new IllegalStateException("Sabitlenmiş çalışma bağlantısı bulunamadı."));
    }
    RuntimeOracleSession open(StagedRuntimePlan plan,boolean control) {
        return connections.openWork(profile(plan),UUID.fromString(plan.staging().path("connectionVersionUuid").asText()),control,PERMIT);
    }
    static JdbcTransactionBoundary transaction(RuntimeOracleSession session) {
        return new JdbcTransactionBoundary() {
            public void commit() { session.commitWorkBatch(); }
            public void rollback() { session.rollbackConfirmed(); }
        };
    }
}

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class CleanProcedureExecutionJournalIT {
    private static final String COMMAND="INSERT INTO INNOVA_ODI.STG_HAKEDIS_TIPI (ID) VALUES (:ID)";
    private static final String RUNTIME="2".repeat(64), RELEASE="7".repeat(64), PLAN="5".repeat(64), TARGET="1".repeat(64);
    private static final UUID DATA_BINDING=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DATA_OBJECT=UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ENV_BINDING=UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID PHYSICAL=UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID CONNECTION=UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final UUID SNAPSHOT=UUID.fromString("10000000-0000-0000-0000-000000000006");
    private static JdbcClient jdbc; private static JdbcRunLeaseStore leases; private static JdbcProcedureExecutionJournalStore journals; private static RunLeasePort.WorkerIdentity worker;

    @BeforeAll static void connect(){
        String url=required("SPRING_DATASOURCE_URL");if(!url.matches(".*(/akis_worker_test_[0-9]+)(?:\\?.*)?$"))throw new IllegalStateException("Generated worker DB required.");
        DataSource ds=new DriverManagerDataSource(url,required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"));jdbc=JdbcClient.create(ds);leases=new JdbcRunLeaseStore(jdbc);journals=new JdbcProcedureExecutionJournalStore(jdbc,new DataSourceTransactionManager(ds));
        long p=jdbc.sql("insert into akis.proje(kod,ad) values ('PROC_IT','Procedure IT') returning id").query(Long.class).single();
        long o=jdbc.sql("insert into akis.ortam(proje_id,kod,ad) values (:p,'TEST','Test') returning id").param("p",p).query(Long.class).single();
        long f=jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id").param("p",p).query(Long.class).single();
        long t=jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','JOURNAL','Journal') returning id").param("p",p).param("f",f).query(Long.class).single();
        String content="{\"tasks\":[{\"id\":\"INSERT_TARGET\",\"name\":\"Insert target\",\"type\":\"SQL\",\"connectionRole\":\"TARGET\",\"riskClass\":\"DML\",\"onError\":\"STOP\",\"command\":\""+COMMAND+"\"}]}";
        long v=jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values (:p,:t,1,2,:h,cast(:c as jsonb)) returning id").param("p",p).param("t",t).param("h","4".repeat(64)).param("c",content).query(Long.class).single();
        long d=jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values (:p,:v,:h,'GECTI','{}') returning id").param("p",p).param("v",v).param("h","4".repeat(64)).query(Long.class).single();
        String scenario="{\"executable\":{\"kind\":\"PROCEDURE\",\"definition\":"+content+"}}";
        long s=jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values (:p,:v,:d,1,2,:h,cast(:j as jsonb)) returning id").param("p",p).param("v",v).param("d",d).param("h",PLAN).param("j",scenario).query(Long.class).single();
        String manifest="{\"releaseHash\":\""+RELEASE+"\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\",\"runtimePlanHash\":\""+RUNTIME+"\",\"bindings\":[{\"nodeCode\":\"INSERT_TARGET\",\"definitionDataObjectUuid\":\""+DATA_BINDING+"\",\"connectionVersionUuid\":\""+CONNECTION+"\",\"schemaSnapshotUuid\":\""+SNAPSHOT+"\",\"schemaSnapshotFingerprint\":\""+"8".repeat(64)+"\"}]}";
        long y=jdbc.sql("insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani) values (:p,:s,:o,1,'AKTIF','ready',cast(:m as jsonb),current_timestamp) returning id").param("p",p).param("s",s).param("o",o).param("m",manifest).query(Long.class).single();
        long it=jdbc.sql("insert into akis.is_talebi(proje_id,yayin_id,istek_ozeti,is_turu) values (:p,:y,:h,'CALISTIR') returning id").param("p",p).param("y",y).param("h","b".repeat(64)).query(Long.class).single();
        long c=jdbc.sql("insert into akis.calistirma(proje_id,is_talebi_id,deneme_no,yayin_ozeti,plan_ozeti,baslatma_turu) values (:p,:i,1,:r,:s,'ILK') returning id").param("p",p).param("i",it).param("r",RELEASE).param("s",PLAN).query(Long.class).single();
        jdbc.sql("insert into akis.calistirma_durumu(proje_id,calistirma_id) values (:p,:c)").param("p",p).param("c",c).update();
        UUID profile=jdbc.sql("insert into akis.worker_profili(kod,ad,yetenek) values ('PROC_WORKER','Procedure worker','{}') returning uuid").query(UUID.class).single();worker=new RunLeasePort.WorkerIdentity("procedure-worker",profile);
    }

    @Test void persistsMutatingStepIntentAndExactAcknowledgements(){
        var run=leases.claimForPreflight(worker,Duration.ofSeconds(60)).orElseThrow();var target=leases.acquireTarget(run.token(),TARGET,1);var active=new RunExecutionTransitionPort.ActiveExecutionToken(run.token(),target);var plan=plan();var journal=journals.forExecution(active,plan);var evidence=new ProcedureExecutionJournalPort.TaskEvidence(RUNTIME,1,plan.tasks().getFirst(),plan.bindings().get("INSERT_TARGET"));
        assertTrue(journal.prepareRun());assertTrue(journal.prepareRun());assertTrue(journal.started(evidence));assertTrue(journal.started(evidence));assertTrue(journal.succeeded(evidence,33,1024));assertTrue(journal.succeeded(evidence,33,1024));assertTrue(journal.completeRun());assertTrue(journal.completeRun());
        assertEquals("BASARILI",jdbc.sql("select durum from akis.calistirma_durumu cd join akis.calistirma c on c.id=cd.calistirma_id where c.uuid=:u").param("u",run.token().runUuid()).query(String.class).single());
        assertEquals(1,jdbc.sql("select count(*) from akis.prosedur_adim_niyeti").query(Integer.class).single());
    }

    private static ProcedureRuntimePlan plan(){
        var task=new ProcedureRuntimePlan.Task("INSERT_TARGET","Insert target",ProcedureRuntimePlan.TaskType.SQL,ProcedureRuntimePlan.ConnectionRole.TARGET,ProcedureRuntimePlan.RiskClass.DML,COMMAND,sha256(COMMAND),false,ProcedureRuntimePlan.ErrorPolicy.STOP,60,null,null,List.of());
        var binding=new ProcedureRuntimePlan.TaskBinding(task.id(),task.connectionRole(),DATA_BINDING,DATA_OBJECT,ENV_BINDING,PHYSICAL,CONNECTION,SNAPSHOT,1,"8".repeat(64),"TARGET|OWNER|OBJECT","OWNER","OBJECT","TABLE");var bindings=new LinkedHashMap<String,ProcedureRuntimePlan.TaskBinding>();bindings.put(task.id(),binding);
        return new ProcedureRuntimePlan(ProcedureRuntimePlan.CURRENT_VERSION,RUNTIME,RELEASE,PLAN,UUID.randomUUID(),UUID.randomUUID(),List.of(task),bindings,new ObjectMapper().createObjectNode());
    }
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required");return v;}
}

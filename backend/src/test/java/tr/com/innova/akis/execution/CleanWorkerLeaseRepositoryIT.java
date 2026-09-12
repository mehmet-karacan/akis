package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class CleanWorkerLeaseRepositoryIT {
    private static JdbcRunLeaseStore leases;
    private static JdbcRunExecutionTransitionStore transitions;
    private static RunLeasePort.WorkerIdentity worker;

    @BeforeAll static void connect() {
        String url=required("SPRING_DATASOURCE_URL");
        if(!url.matches(".*(/akis_worker_test_[0-9]+)(?:\\?.*)?$")) throw new IllegalStateException("Generated worker DB required.");
        var jdbc=JdbcClient.create(new DriverManagerDataSource(url,required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD")));
        var execution=new JdbcExecutionStore(jdbc,new ObjectMapper());
        UUID projectUuid=jdbc.sql("insert into akis.proje(kod,ad) values ('WORKER_IT','Worker IT') returning uuid").query(UUID.class).single();
        long p=jdbc.sql("select id from akis.proje where uuid=:u").param("u",projectUuid).query(Long.class).single();
        long actor=jdbc.sql("insert into akis.kullanici(gorunen_ad) values ('Worker developer') returning id").query(Long.class).single();
        jdbc.sql("insert into akis.harici_kimlik(kullanici_id,saglayici_turu,harici_kullanici_anahtari) values (:k,'YEREL','worker-developer')").param("k",actor).update();
        long ortam=jdbc.sql("insert into akis.ortam(proje_id,kod,ad) values (:p,'DEV','Development') returning id").param("p",p).query(Long.class).single();
        long klasor=jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id").param("p",p).query(Long.class).single();
        long tanim=jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','LOAD','Load') returning id").param("p",p).param("f",klasor).query(Long.class).single();
        long surum=jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values (:p,:t,1,1,:h,'{\"tasks\":[]}') returning id").param("p",p).param("t",tanim).param("h","a".repeat(64)).query(Long.class).single();
        long dogrulama=jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values (:p,:v,:h,'GECTI','{}') returning id").param("p",p).param("v",surum).param("h","a".repeat(64)).query(Long.class).single();
        long senaryo=jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values (:p,:v,:d,1,2,:h,'{}') returning id").param("p",p).param("v",surum).param("d",dogrulama).param("h","b".repeat(64)).query(Long.class).single();
        UUID yayinUuid=jdbc.sql("insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani) values (:p,:s,:o,1,'AKTIF','ready',cast(:m as jsonb),current_timestamp) returning uuid").param("p",p).param("s",senaryo).param("o",ortam).param("m","{\"releaseHash\":\""+"c".repeat(64)+"\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}").query(UUID.class).single();
        var publication=execution.lockPublication(projectUuid,yayinUuid).orElseThrow();
        var actorRow=execution.findActiveActor("LOCAL_BASIC","worker-developer").orElseThrow();
        execution.createQueuedRun(publication,actorRow,"d".repeat(64),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        UUID profile=jdbc.sql("insert into akis.worker_profili(kod,ad,yetenek) values ('LOCAL','Local worker','{\"ORACLE_PROCEDURE_V1\":true}') returning uuid").query(UUID.class).single();
        leases=new JdbcRunLeaseStore(jdbc);transitions=new JdbcRunExecutionTransitionStore(jdbc);worker=new RunLeasePort.WorkerIdentity("worker-1",profile);
    }

    @Test void claimsHeartbeatsAndFencesExactlyOneQueuedRun() {
        var claimed=leases.claimForPreflight(worker,Duration.ofSeconds(60)).orElseThrow();
        assertTrue(leases.claimForPreflight(worker,Duration.ofSeconds(60)).isEmpty());
        var heartbeat=leases.heartbeat(claimed.token(),Duration.ofSeconds(90));
        assertEquals(RunLeasePort.HeartbeatOutcome.ACCEPTED,heartbeat.outcome());
        var target=leases.acquireTarget(heartbeat.refreshedToken(),"e".repeat(64),1);
        assertEquals(1,target.targetGeneration());
        assertThrows(RuntimeException.class,()->leases.acquireTarget(claimed.token(),"f".repeat(64),1));
        var active=new RunExecutionTransitionPort.ActiveExecutionToken(heartbeat.refreshedToken(),target);
        assertEquals(RunExecutionTransitionPort.MutationOutcome.ACCEPTED,transitions.completePreflight(active).outcome());
        var evidence=new RunExecutionTransitionPort.PublishIntentEvidence("1".repeat(64),"2".repeat(64),"3".repeat(64),33,1024);
        assertEquals(RunExecutionTransitionPort.MutationOutcome.ACCEPTED,transitions.beginPublish(active,evidence).outcome());
        assertEquals(RunExecutionTransitionPort.MutationOutcome.ACCEPTED,transitions.completeSuccessfully(active,evidence).outcome());
    }

    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}

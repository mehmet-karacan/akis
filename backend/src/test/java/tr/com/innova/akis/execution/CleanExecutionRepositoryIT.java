package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class CleanExecutionRepositoryIT {
    private static JdbcExecutionStore store; private static JdbcPinnedExecutionContextStore pinnedStore;
    private static JdbcProcedurePreflightContextStore preflightStore;
    private static UUID projectUuid; private static UUID publicationUuid;

    @BeforeAll static void connect(){
        String url=required("SPRING_DATASOURCE_URL");if(!url.matches(".*(/akis_execution_test_[0-9]+)(?:\\?.*)?$"))throw new IllegalStateException("Generated execution DB required.");
        var ds=new DriverManagerDataSource(url,required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"));var jdbc=JdbcClient.create(ds);var mapper=new ObjectMapper();store=new JdbcExecutionStore(jdbc,mapper);pinnedStore=new JdbcPinnedExecutionContextStore(jdbc,mapper);preflightStore=new JdbcProcedurePreflightContextStore(jdbc,mapper);
        projectUuid=jdbc.sql("insert into akis.proje(kod,ad) values ('EXECUTION_IT','Execution IT') returning uuid").query(UUID.class).single();long p=jdbc.sql("select id from akis.proje where uuid=:u").param("u",projectUuid).query(Long.class).single();
        long actor=jdbc.sql("insert into akis.kullanici(gorunen_ad) values ('Developer') returning id").query(Long.class).single();jdbc.sql("insert into akis.harici_kimlik(kullanici_id,saglayici_turu,harici_kullanici_anahtari) values (:k,'YEREL','developer')").param("k",actor).update();
        long o=jdbc.sql("insert into akis.ortam(proje_id,kod,ad) values (:p,'DEV','Development') returning id").param("p",p).query(Long.class).single();long f=jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id").param("p",p).query(Long.class).single();long t=jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','LOAD','Load') returning id").param("p",p).param("f",f).query(Long.class).single();
        long v=jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values (:p,:t,1,1,:h,'{\"tasks\":[]}') returning id").param("p",p).param("t",t).param("h","a".repeat(64)).query(Long.class).single();long d=jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values (:p,:v,:h,'GECTI','{}') returning id").param("p",p).param("v",v).param("h","a".repeat(64)).query(Long.class).single();long s=jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values (:p,:v,:d,1,2,:h,'{}') returning id").param("p",p).param("v",v).param("d",d).param("h","b".repeat(64)).query(Long.class).single();
        publicationUuid=jdbc.sql("insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani) values (:p,:s,:o,1,'AKTIF','ready',cast(:m as jsonb),current_timestamp) returning uuid").param("p",p).param("s",s).param("o",o).param("m","{\"releaseHash\":\""+"c".repeat(64)+"\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}").query(UUID.class).single();
    }

    @Test void queuesListsAndCancelsWithIdempotentEvidence(){
        long p=store.findProjectId(projectUuid).orElseThrow();var actor=store.findActiveActor("LOCAL_BASIC","developer").orElseThrow();var publication=store.lockPublication(projectUuid,publicationUuid).orElseThrow();
        assertTrue(store.reserveIdempotency(p,actor.id(),"MANUAL_RUN","d".repeat(64),"e".repeat(64),UUID.randomUUID()));var reservation=store.lockIdempotency(p,actor.id(),"MANUAL_RUN","d".repeat(64)).orElseThrow();
        var run=store.createQueuedRun(publication,actor,"e".repeat(64),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());store.completeIdempotency(reservation.id(),run.jobRequestId(),run);
        assertEquals("BEKLIYOR",run.status());assertEquals(publicationUuid,pinnedStore.find(run.runUuid()).orElseThrow().publicationUuid());assertEquals("AKTIF",preflightStore.find(projectUuid,publicationUuid).orElseThrow().status());assertEquals("RUN_REQUESTED",store.listEvents(projectUuid,run.runUuid()).getFirst().type());var cancelled=store.cancelQueued(store.lock(projectUuid,run.runUuid()).orElseThrow(),actor,UUID.randomUUID());assertEquals("IPTAL",cancelled.status());assertEquals(2,store.listEvents(projectUuid,run.runUuid()).size());
    }
    private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required");return v;}
}

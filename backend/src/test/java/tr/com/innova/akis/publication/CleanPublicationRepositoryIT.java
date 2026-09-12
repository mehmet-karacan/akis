package tr.com.innova.akis.publication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.publication.PublicationModels.PublicationDraft;

class CleanPublicationRepositoryIT {
    private static JdbcPublicationStore store;
    private static UUID projectUuid;
    private static UUID scenarioUuid;
    private static UUID environmentUuid;
    private static long actorId;

    @BeforeAll
    static void connect() {
        String url=required("SPRING_DATASOURCE_URL");
        if(!url.matches(".*(/akis_release_test_[0-9]+)(?:\\?.*)?$")) throw new IllegalStateException("Generated release DB required.");
        var dataSource=new DriverManagerDataSource(url,required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"));
        JdbcClient jdbc=JdbcClient.create(dataSource); store=new JdbcPublicationStore(jdbc,new ObjectMapper());
        projectUuid=jdbc.sql("insert into akis.proje(kod,ad) values ('RELEASE_IT','Release IT') returning uuid").query(UUID.class).single();
        long p=jdbc.sql("select id from akis.proje where uuid=:u").param("u",projectUuid).query(Long.class).single();
        environmentUuid=jdbc.sql("insert into akis.ortam(proje_id,kod,ad,uretim_mi,risk,politika) values (:p,'PROD','Production',true,'URETIM','{}') returning uuid").param("p",p).query(UUID.class).single();
        long f=jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id").param("p",p).query(Long.class).single();
        long t=jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','LOAD','Load') returning id").param("p",p).param("f",f).query(Long.class).single();
        long v=jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values (:p,:t,1,1,:h,'{\"tasks\":[]}') returning id")
                .param("p",p).param("t",t).param("h","a".repeat(64)).query(Long.class).single();
        long d=jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values (:p,:v,:h,'GECTI','{}') returning id")
                .param("p",p).param("v",v).param("h","a".repeat(64)).query(Long.class).single();
        scenarioUuid=jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values (:p,:v,:d,1,2,:h,'{}') returning uuid")
                .param("p",p).param("v",v).param("d",d).param("h","b".repeat(64)).query(UUID.class).single();
        actorId=jdbc.sql("insert into akis.kullanici(gorunen_ad,eposta) values ('Operator','op@example.test') returning id").query(Long.class).single();
        jdbc.sql("insert into akis.harici_kimlik(kullanici_id,saglayici_turu,harici_kullanici_anahtari) values (:k,'YEREL','operator')").param("k",actorId).update();
    }

    @Test
    void pinsReleaseAndAppendsApprovalBeforeActivation() {
        assertTrue(store.projectExists(projectUuid));
        var context=store.lockContext(projectUuid,scenarioUuid,environmentUuid).orElseThrow();
        var manifest=new ObjectMapper().createObjectNode().put("releaseHash","c".repeat(64));
        var release=store.create(new PublicationDraft(context,"c".repeat(64),
                "scenario="+scenarioUuid+";bindingCount=0",manifest,List.of(),"ONAY_BEKLIYOR"),UUID.randomUUID());
        var actor=store.findActiveActor("LOCAL_BASIC","operator").orElseThrow();
        var approval=store.createApproval(release,actor,"ONAY",null,UUID.randomUUID());
        var active=store.transition(release.id(),"ONAY_BEKLIYOR","AKTIF");
        assertEquals("ONAY",approval.decision());
        assertEquals("AKTIF",active.status());
        assertEquals(2,active.version());
        assertEquals(release.uuid(),store.findByReleaseHash(context.scenarioId(),context.environmentId(),"c".repeat(64)).orElseThrow().uuid());
    }

    private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required");return v;}
}

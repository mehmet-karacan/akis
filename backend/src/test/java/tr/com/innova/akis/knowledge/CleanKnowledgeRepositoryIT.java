package tr.com.innova.akis.knowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import static org.junit.jupiter.api.Assertions.*;

class CleanKnowledgeRepositoryIT {
    @Test void journalUsesPinnedPublicationAndRejectsExpiredOrForeignLeases() {
        var jdbc=jdbc(); UUID project=UUID.randomUUID(),run=UUID.randomUUID(); String hash="a".repeat(64);
        long p=jdbc.sql("insert into akis.proje(uuid,kod,ad) values(:u,:k,'KM journal') returning id")
                .param("u",project).param("k","KM_"+project.toString().replace("-", "").toUpperCase()).query(Long.class).single();
        long o=jdbc.sql("insert into akis.ortam(kod,ad) values(:k,'Test') returning id").param("k","KM_"+project.toString().replace("-", "").toUpperCase()).query(Long.class).single();
        long t=jdbc.sql("insert into akis.tanim(proje_id,tur,kod,ad) values(:p,'MAPPING','MAP','Mapping') returning id").param("p",p).query(Long.class).single();
        long v=jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values(:p,:t,1,3,:h,'{}') returning id").param("p",p).param("t",t).param("h",hash).query(Long.class).single();
        long d=jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values(:p,:v,:h,'GECTI','{}') returning id").param("p",p).param("v",v).param("h",hash).query(Long.class).single();
        long s=jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values(:p,:v,:d,1,2,:h,'{}') returning id").param("p",p).param("v",v).param("d",d).param("h",hash).query(Long.class).single();
        long y=jdbc.sql("insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani) values(:p,:s,:o,1,'AKTIF','ready',cast(:m as jsonb),current_timestamp) returning id")
                .param("p",p).param("s",s).param("o",o).param("m","{\"runtimeCapability\":\"ORACLE_STAGED_MAPPING_V1\",\"runtimePlanHash\":\""+hash+"\"}").query(Long.class).single();
        long job=jdbc.sql("insert into akis.is_talebi(proje_id,yayin_id,istek_ozeti) values(:p,:y,:h) returning id").param("p",p).param("y",y).param("h",hash).query(Long.class).single();
        long c=jdbc.sql("insert into akis.calistirma(proje_id,is_talebi_id,uuid,deneme_no,yayin_ozeti,plan_ozeti,baslatma_turu) values(:p,:j,:u,1,:h,:h,'ILK') returning id").param("p",p).param("j",job).param("u",run).param("h",hash).query(Long.class).single();
        jdbc.sql("insert into akis.calistirma_durumu(proje_id,calistirma_id,durum,nesil_no,isleyici_referansi,kiralama_bitis_zamani) values(:p,:c,'CALISIYOR',1,'km-test',clock_timestamp()+interval '5 minutes')").param("p",p).param("c",c).update();
        var owner=new WorkObjectStore.Owner(project,run,1,"km-test");
        var journal=new KmStepJournal(jdbc);
        var plan=AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(AkisKmLanguage.example(AkisKmLanguage.Kind.LKM),null,AkisKmLanguage.example(AkisKmLanguage.Kind.IKM)));
        assertThrows(IllegalStateException.class,()->journal.prepare(owner,"b".repeat(64),plan));
        journal.prepare(owner,hash,plan);
        assertEquals(4,journal.list(project,run).size());
        assertNull(journal.reconciliation(project,run));
        assertTrue(journal.list(UUID.randomUUID(),run).isEmpty());
        journal.transition(owner,1,"RUNNING",null,null);
        journal.transition(owner,1,"SUCCEEDED",1201L,null);
        assertEquals(1201L,journal.list(project,run).getFirst().affectedRows());
        assertThrows(IllegalStateException.class,()->journal.transition(new WorkObjectStore.Owner(project,run,1,"foreign"),2,"RUNNING",null,null));
        jdbc.sql("update akis.calistirma_durumu set kiralama_bitis_zamani=clock_timestamp()-interval '1 second' where calistirma_id=:c").param("c",c).update();
        assertThrows(IllegalStateException.class,()->journal.transition(owner,2,"RUNNING",null,null));
    }
    private JdbcClient jdbc() {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(url==null || !url.matches(".*[/]akis_km_test_[0-9]+$")) throw new IllegalStateException("Isolated KM database required.");
        return JdbcClient.create(new DriverManagerDataSource(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }
    @Test void workAreaPolicyRejectsStaleAndCrossProjectChanges() {
        var jdbc=jdbc(); UUID project=UUID.randomUUID(),connection=UUID.randomUUID(),schema=UUID.randomUUID();
        long projectId=jdbc.sql("insert into akis.proje(uuid,kod,ad) values(:u,:code,'KM test') returning id")
                .param("u",project).param("code","KM_"+project.toString().replace("-", "").toUpperCase()).query(Long.class).single();
        long connectionId=jdbc.sql("insert into akis.baglanti(uuid,kod,ad,saglayici_turu,baglanti_modu,surucu_sinifi,sunucu_adi,port,servis_adi,kullanici_adi) values(:u,:k,'DB','ORACLE','JDBC','oracle.jdbc.OracleDriver','db.local',1521,'ORCL','APP') returning id")
                .param("k","KM_"+connection.toString().replace("-", "").toUpperCase()).param("u",connection).query(Long.class).single();
        jdbc.sql("insert into akis.fiziksel_sema(baglanti_id,saglayici_turu,uuid,kod,ad,sema_adi,calisma_sema_adi) values(:b,'ORACLE',:u,:k,'Work','WORK','WORK')")
                .param("b",connectionId).param("u",schema).param("k","W_"+schema.toString().replace("-", "").toUpperCase()).update();
        var areas=new WorkAreaPolicyService(jdbc);
        assertFalse(areas.get(project,schema).policy().enabled());
        var enabled=new WorkAreaPolicyService.Policy(true,false,5,10000,1000000,24);
        assertEquals(1,areas.save(project,schema,0,enabled).version());
        assertThrows(RuntimeException.class,()->areas.save(project,schema,0,enabled));
        assertThrows(RuntimeException.class,()->areas.get(UUID.randomUUID(),schema));
        WorkAreaPolicyService.requireAllowed(areas.get(project,schema),new StagedMappingDefinition.Options(500,500,10000,1000000,false),false);
        assertThrows(RuntimeException.class,()->WorkAreaPolicyService.requireAllowed(areas.get(project,schema),new StagedMappingDefinition.Options(500,500,10001,1000000,false),false));
        assertThrows(RuntimeException.class,()->WorkAreaPolicyService.requireAllowed(areas.get(project,schema),new StagedMappingDefinition.Options(500,500,10000,1000000,false),true));
    }
    @Test void resolvesOnlyPinnedSameProjectModuleVersions() {
        var jdbc=jdbc(); var mapper=new JsonMapper();
        UUID project=UUID.randomUUID();
        long projectId=jdbc.sql("insert into akis.proje(uuid,kod,ad) values(:u,:code,'KM versions') returning id")
                .param("u",project).param("code","KM_"+project.toString().replace("-", "").toUpperCase()).query(Long.class).single();
        Map<String,StagedMappingDefinition.Pin> pins=new HashMap<>();
        for(var kind:List.of(AkisKmLanguage.Kind.LKM,AkisKmLanguage.Kind.IKM)) {
            long id=jdbc.sql("insert into akis.tanim(proje_id,tur,kod,ad) values(:p,'KNOWLEDGE_MODULE',:code,:code) returning id")
                    .param("p",projectId).param("code",kind.name()).query(Long.class).single();
            var content=mapper.createObjectNode().put("language",AkisKmLanguage.VERSION).put("kmType",kind.name()).put("source",AkisKmLanguage.example(kind));
            content.putArray("tasks"); content.putArray("options");
            String hash=KmCanonical.hash(mapper,content);
            UUID version=jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values(:p,:t,1,2,:hash,cast(:body as jsonb)) returning uuid")
                    .param("p",projectId).param("t",id).param("hash",hash).param("body",content.toString()).query(UUID.class).single();
            pins.put(kind==AkisKmLanguage.Kind.LKM?"loading":"integration",new StagedMappingDefinition.Pin(version,hash));
        }
        var definition=new StagedMappingDefinition(UUID.randomUUID(),pins,new StagedMappingDefinition.Options(500,500,10000,1000000,false));
        var registry=new KnowledgeModuleRegistry(jdbc,mapper,new DefinitionContentValidator());
        assertEquals(4,registry.resolve(projectId,definition).plan().steps().size());
        assertThrows(RuntimeException.class,()->registry.resolve(projectId+999,definition));
        pins.put("loading",new StagedMappingDefinition.Pin(pins.get("loading").versionUuid(),"0".repeat(64)));
        assertThrows(RuntimeException.class,()->registry.resolve(projectId,new StagedMappingDefinition(definition.logicalSchemaUuid(),pins,definition.options())));
    }
}

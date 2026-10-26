package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated PostgreSQL only; never reads connection credentials or writes application data. */
@Testcontainers(disabledWithoutDocker=true)
class AutomaticTransferContainerIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:18.6-bookworm");

    @Test void environmentBudgetIsInheritedAndReducedCeilingRequiresRepublication() {
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())
                .locations("classpath:db/akis").defaultSchema("akis").schemas("akis").load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        UUID environment=jdbc.sql("insert into akis.ortam(kod,ad) values(:code,'Memory acceptance') returning uuid")
                .param("code","MEM_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT)).query(UUID.class).single();
        var service=new WorkAreaPolicyService(jdbc);
        var auto=new StagedMappingDefinition.Options(1000,256,1000,10000000,false,"AUTO");
        var pinned=service.automaticOptions(auto,environment);
        assertEquals(1073741824L,pinned.memoryBudgetBytes());
        service.requireEnvironmentMemory(pinned,environment);
        jdbc.sql("update akis.ortam set politika=jsonb_set(politika,'{transferMemoryBudgetMiB}','64') where uuid=:uuid")
                .param("uuid",environment).update();
        assertThrows(IllegalStateException.class,()->service.requireEnvironmentMemory(pinned,environment));
        assertEquals(67108864L,service.automaticOptions(auto,environment).memoryBudgetBytes());
    }

    @Test void autoCopyPreservesFiftyMixedColumnsAcrossBoundedBatches() throws Exception {
        try(Connection source=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
                Connection stage=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
            var ddl=new ArrayList<String>();var values=new ArrayList<String>();var columns=new ArrayList<JdbcStagingTransfer.Column>();
            for(int i=0;i<50;i++) {
                String name="C"+i;String type,expression;JdbcStagingTransfer.Type readType;
                switch(i%5) {
                    case 0 -> {type="numeric(38,6)";expression="n * 1.234567";readType=JdbcStagingTransfer.Type.NUMBER;}
                    case 1 -> {type="text";expression="case when n%7=0 then null else repeat('İş 😀, \"alıntı\"', 200) end";readType=JdbcStagingTransfer.Type.VARCHAR2;}
                    case 2 -> {type="bytea";expression="decode('007fff','hex')";readType=JdbcStagingTransfer.Type.RAW;}
                    case 3 -> {type="timestamp(6)";expression="timestamp '2026-10-22 12:34:56.123456' + n * interval '1 second'";readType=JdbcStagingTransfer.Type.TIMESTAMP;}
                    default -> {type="varchar(100)";expression="case when n%3=0 then '' else E'satır\\nsonu' end";readType=JdbcStagingTransfer.Type.VARCHAR2;}
                }
                ddl.add('"'+name+'"'+" "+type);values.add(expression);columns.add(new JdbcStagingTransfer.Column(name,name,readType));
            }
            try(Statement sql=source.createStatement()) {
                sql.execute("create table public.source_rows ("+String.join(",",ddl)+")");
                sql.execute("create table public.stage_rows (like public.source_rows)");
                sql.execute("insert into public.source_rows select "+String.join(",",values)+" from generate_series(1,1000) n");
            }
            source.setAutoCommit(false);
            stage.setAutoCommit(false);
            var progress=new ArrayList<TransferProgress>();
            TransferProgress.Listener monitor=new TransferProgress.Listener(){public void run(){}public void report(TransferProgress p){progress.add(p);}};
            var options=new TransferResourcePolicy(5_000_000,34_359_738_368L,1000,256,1).resolve(
                    new StagedMappingDefinition.Options(1,1,1,1,false,"AUTO"));
            var result=new PostgresCopyStagingTransfer(false).transfer(source,stage,
                    new JdbcStagingTransfer.Table("public","source_rows"),new JdbcStagingTransfer.Table("public","stage_rows"),
                    columns,options,30,monitor,JdbcTransactionBoundary.direct(stage),JdbcStagingTransfer.QueryOptions.defaults());
            assertEquals(1000,result.rows());assertEquals(1000,progress.getLast().rows());assertTrue(result.logicalBytes()>1_000_000);
            String diagnostic;
            try(Statement sql=source.createStatement();ResultSet check=sql.executeQuery("select count(*) filter(where s.\"C1\" is distinct from t.\"C1\"),count(*) filter(where position('?' in t.\"C1\")>0),max(length(s.\"C1\")),max(length(t.\"C1\")) from public.source_rows s join public.stage_rows t on s.\"C0\"=t.\"C0\"")) {
                check.next();diagnostic="textMismatches="+check.getLong(1)+", replacementCharacters="+check.getLong(2)+", sourceLength="+check.getLong(3)+", targetLength="+check.getLong(4);
            }
            try(Statement sql=source.createStatement();ResultSet difference=sql.executeQuery("select count(*) from ((select * from public.source_rows except all select * from public.stage_rows) union all (select * from public.stage_rows except all select * from public.source_rows)) differences")) {
                assertTrue(difference.next());assertEquals(0,difference.getLong(1),diagnostic);
            }
        }
    }

    @Test void baselineMigrationsInstallLiveProgressColumn() {
        var flyway=Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())
                .locations("classpath:db/akis").defaultSchema("akis").schemas("akis").load();
        var result=flyway.migrate();assertTrue(result.success);
        assertDoesNotThrow(()->{
            try(Connection connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
                    Statement query=connection.createStatement();ResultSet field=query.executeQuery("select data_type,is_nullable from information_schema.columns where table_schema='akis' and table_name='km_step_journal' and column_name='transfer_progress'")) {
                assertTrue(field.next());assertEquals("jsonb",field.getString(1));assertEquals("YES",field.getString(2));
            }
        });
    }

    @Test void cancellationAcknowledgementExecutesAgainstTheCompleteBaselineSchema() {
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())
                .locations("classpath:db/akis").defaultSchema("akis").schemas("akis").load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        String suffix=UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);String hash="a".repeat(64);UUID run=UUID.randomUUID();
        long project=jdbc.sql("insert into akis.proje(kod,ad) values(:code,'Cancellation acceptance') returning id").param("code","AUTO_"+suffix).query(Long.class).single();
        long environment=jdbc.sql("insert into akis.ortam(kod,ad) values(:code,'Acceptance') returning id").param("code","AUTO_"+suffix).query(Long.class).single();
        long definition=jdbc.sql("insert into akis.tanim(proje_id,tur,kod,ad) values(:p,'MAPPING','MAP','Mapping') returning id").param("p",project).query(Long.class).single();
        long version=jdbc.sql("insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik) values(:p,:t,1,3,:h,'{}') returning id").param("p",project).param("t",definition).param("h",hash).query(Long.class).single();
        long validation=jdbc.sql("insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi) values(:p,:v,:h,'GECTI','{}') returning id").param("p",project).param("v",version).param("h",hash).query(Long.class).single();
        long scenario=jdbc.sql("insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan) values(:p,:v,:d,1,2,:h,'{}') returning id").param("p",project).param("v",version).param("d",validation).param("h",hash).query(Long.class).single();
        long publication=jdbc.sql("insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani) values(:p,:s,:o,1,'AKTIF','ready','{}',current_timestamp) returning id").param("p",project).param("s",scenario).param("o",environment).query(Long.class).single();
        long job=jdbc.sql("insert into akis.is_talebi(proje_id,yayin_id,istek_ozeti) values(:p,:y,:h) returning id").param("p",project).param("y",publication).param("h",hash).query(Long.class).single();
        long execution=jdbc.sql("insert into akis.calistirma(proje_id,is_talebi_id,uuid,deneme_no,yayin_ozeti,plan_ozeti,baslatma_turu) values(:p,:j,:u,1,:h,:h,'ILK') returning id").param("p",project).param("j",job).param("u",run).param("h",hash).query(Long.class).single();
        jdbc.sql("insert into akis.calistirma_durumu(proje_id,calistirma_id,durum,nesil_no,isleyici_referansi,kiralama_bitis_zamani,iptal_isteme_zamani) values(:p,:r,'IPTAL_ISTENDI',1,'auto-acceptance',clock_timestamp()-interval '1 minute',clock_timestamp())").param("p",project).param("r",execution).update();
        assertEquals(1,jdbc.sql("select akis.calistirma_suresi_dolan_iptalleri_tamamla(10)").query(Integer.class).single());
        assertTrue(jdbc.sql("select akis.calistirma_iptal_tamamla(:run,'auto-acceptance',1)").param("run",run).query(Boolean.class).single());
        assertEquals("IPTAL",jdbc.sql("select durum from akis.calistirma_durumu where calistirma_id=:r").param("r",execution).query(String.class).single());
    }
}

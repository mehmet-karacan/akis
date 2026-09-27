package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.RunExportReader.EventRef;
import tr.com.innova.akis.export.RunExportReader.RunQuery;
import tr.com.innova.akis.export.RunExportReader.RunRef;
import tr.com.innova.akis.export.RunExportReader.StepRef;
import tr.com.innova.akis.knowledge.KmStepJournal;
import tr.com.innova.akis.knowledge.WorkObjectStore;

/** Opt-in real PostgreSQL load gate, only on a fresh generated akis_bundle_test_* database. */
class RunExportPostgresScaleIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void streamsLargePostgresFixtureThroughOneSnapshot() throws Exception {
        assumeTrue(Boolean.getBoolean("akis.export.pgscale"));
        assertTrue(Runtime.getRuntime().maxMemory() <= 160L * 1024 * 1024,
                "PostgreSQL scale gate must run with a constrained heap.");
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_bundle_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Generated isolated test DB required.");
        }
        int runCount = Integer.getInteger("akis.export.pgscale.runs", 100_000);
        assertTrue(runCount > 0);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/akis")
                .defaultSchema("akis").schemas("akis").createSchemas(true)
                .validateMigrationNaming(true).load().migrate();
        UUID projectUuid = seed(dataSource, runCount, "EXPORT_SCALE");

        CountingReader reader = new CountingReader(dataSource);
        RunExportProvider provider = new RunExportProvider(reader,
                new KmStepJournal(JdbcClient.create(dataSource), MAPPER, dataSource, 1000),
                new WorkObjectStore(JdbcClient.create(dataSource), dataSource, 1000), MAPPER);
        ExportContext context = new ExportContext(projectUuid, 1L, 1L, "runs", ExportScope.ALL,
                List.of(), List.of("status"), false, "tr-TR", "UTC",
                System.currentTimeMillis(), runCount);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(1800);

        try (JsonExportWriter writer = JsonExportWriterImpl.builder(MAPPER)
                .outputStream(OutputStream.nullOutputStream()).build()) {
            transaction.executeWithoutResult(ignored -> {
                try {
                    provider.streamRecords(context, writer);
                } catch (IOException exception) {
                    throw new IllegalStateException("Fixture export failed", exception);
                }
            });
            assertEquals(runCount, ((JsonExportWriterImpl) writer).recordCount());
            writer.writeSummary(runCount, null, null, null, null);
        }
        assertEquals(1, reader.runCalls);
        assertEquals((runCount + 15) / 16, reader.stepBatchCalls);
        assertEquals(reader.stepBatchCalls, reader.eventBatchCalls);
        assertEquals(0, reader.singleStepCalls);
        assertEquals(0, reader.childLookupCalls);
    }

    @Test
    void batchesPackageChildrenInRealPostgresAndPreservesDepthFirstOrder() throws Exception {
        assumeTrue(Boolean.getBoolean("akis.export.pgscale"));
        assertTrue(Runtime.getRuntime().maxMemory() <= 160L * 1024 * 1024);
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_bundle_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Generated isolated test DB required.");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/akis")
                .defaultSchema("akis").schemas("akis").createSchemas(true)
                .validateMigrationNaming(true).load().migrate();
        UUID projectUuid = seed(dataSource, 32, "EXPORT_CHILD_SCALE");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        long projectId = jdbc.sql("select id from akis.proje where uuid=:uuid")
                .param("uuid", projectUuid).query(Long.class).single();
        List<UUID> ordered = jdbc.sql("select uuid from akis.calistirma where proje_id=:p order by id desc")
                .param("p", projectId).query(UUID.class).list();
        assertEquals(32, ordered.size());
        for (int index = 0; index < 16; index++) {
            jdbc.sql("""
                    insert into akis.paket_adim_durumu
                        (proje_id, calistirma_id, calistirma_adimi_id, durum, alt_calistirma_id)
                    select :p, parent.id, step.id, 'BASARILI', child.id
                      from akis.calistirma parent
                      join akis.calistirma_adimi step on step.calistirma_id=parent.id
                      join akis.calistirma child on child.uuid=:child
                     where parent.uuid=:parent and step.adim_kodu='STEP_1'
                    """).param("p", projectId).param("parent", ordered.get(index))
                    .param("child", ordered.get(index + 16)).update();
        }

        CountingReader reader = new CountingReader(dataSource);
        RunExportProvider provider = new RunExportProvider(reader,
                new KmStepJournal(jdbc, MAPPER, dataSource, 1000),
                new WorkObjectStore(jdbc, dataSource, 1000), MAPPER);
        ExportContext context = new ExportContext(projectUuid, 1L, 1L, "runs", ExportScope.ALL,
                List.of(), List.of("status"), false, "tr-TR", "UTC",
                System.currentTimeMillis(), 32);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (JsonExportWriter writer = JsonExportWriterImpl.builder(MAPPER).outputStream(output).build()) {
            transaction.executeWithoutResult(ignored -> {
                try {
                    provider.streamRecords(context, writer);
                } catch (IOException exception) {
                    throw new IllegalStateException("Child fixture export failed", exception);
                }
            });
            assertEquals(32, ((JsonExportWriterImpl) writer).recordCount());
            writer.writeSummary(32, null, null, null, null);
        }
        JsonNode records = MAPPER.readTree(output.toByteArray()).path("records");
        assertEquals(32, records.size());
        for (int index = 0; index < 16; index++) {
            assertEquals(ordered.get(index).toString(), records.get(index * 2).path("runUuid").asText());
            assertEquals(ordered.get(index + 16).toString(), records.get(index * 2 + 1).path("runUuid").asText());
            assertEquals(ordered.get(index).toString(), records.get(index * 2 + 1).path("parentRunUuid").asText());
        }
        assertEquals(1, reader.runCalls);
        assertEquals(2, reader.stepBatchCalls);
        assertEquals(2, reader.eventBatchCalls);
        assertEquals(1, reader.childLookupCalls);
        assertEquals(0, reader.singleStepCalls);
    }

    private static UUID seed(DataSource dataSource, int runCount, String projectCode) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID projectUuid = jdbc.sql("insert into akis.proje(kod,ad) values (:code,:code) returning uuid")
                .param("code", projectCode)
                .query(UUID.class).single();
        long p = jdbc.sql("select id from akis.proje where uuid=:uuid")
                .param("uuid", projectUuid).query(Long.class).single();
        long o = jdbc.sql("insert into akis.ortam(kod,ad) values (:code,:code) returning id")
                .param("code", projectCode)
                .query(Long.class).single();
        long f = jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id")
                .param("p", p).query(Long.class).single();
        long t = jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','LOAD','Load') returning id")
                .param("p", p).param("f", f).query(Long.class).single();
        long v = jdbc.sql("""
                insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik)
                values (:p,:t,1,1,:hash,'{"tasks":[]}') returning id
                """).param("p", p).param("t", t).param("hash", "a".repeat(64)).query(Long.class).single();
        long d = jdbc.sql("""
                insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi)
                values (:p,:v,:hash,'GECTI','{}') returning id
                """).param("p", p).param("v", v).param("hash", "a".repeat(64)).query(Long.class).single();
        long s = jdbc.sql("""
                insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan)
                values (:p,:v,:d,1,2,:hash,'{}') returning id
                """).param("p", p).param("v", v).param("d", d).param("hash", "b".repeat(64)).query(Long.class).single();
        long publication = jdbc.sql("""
                insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani)
                values (:p,:s,:o,1,'AKTIF','ready',cast(:manifest as jsonb),current_timestamp) returning id
                """).param("p", p).param("s", s).param("o", o)
                .param("manifest", "{\"releaseHash\":\"" + "c".repeat(64)
                        + "\",\"runtimeCapability\":\"ORACLE_PROCEDURE_V1\"}")
                .query(Long.class).single();
        jdbc.sql("""
                insert into akis.is_talebi(proje_id,yayin_id,istek_ozeti)
                select :p,:publication,repeat('a',64) from generate_series(1,:count)
                """).param("p", p).param("publication", publication).param("count", runCount).update();
        jdbc.sql("""
                insert into akis.calistirma(proje_id,is_talebi_id,deneme_no,yayin_ozeti,plan_ozeti,baslatma_turu)
                select :p,id,1,repeat('b',64),repeat('c',64),'ILK'
                from akis.is_talebi where proje_id=:p
                """).param("p", p).update();
        jdbc.sql("""
                insert into akis.calistirma_durumu(proje_id,calistirma_id)
                select :p,id from akis.calistirma where proje_id=:p
                """).param("p", p).update();
        jdbc.sql("""
                insert into akis.calistirma_adimi(proje_id,calistirma_id,adim_kodu,tur,sira_no,ad)
                select :p,r.id,'STEP_' || ordinal,'PROSEDUR',ordinal,'Scale step'
                from akis.calistirma r cross join generate_series(1,3) ordinal
                where r.proje_id=:p
                """).param("p", p).update();
        assertEquals(runCount, jdbc.sql("select count(*) from akis.calistirma where proje_id=:p")
                .param("p", p).query(Long.class).single());
        assertEquals(3L * runCount, jdbc.sql("select count(*) from akis.calistirma_adimi where proje_id=:p")
                .param("p", p).query(Long.class).single());
        return projectUuid;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required.");
        return value;
    }

    private static final class CountingReader extends RunExportReader {
        int runCalls;
        int stepBatchCalls;
        int eventBatchCalls;
        int singleStepCalls;
        int childLookupCalls;

        CountingReader(DataSource dataSource) {
            super(dataSource, MAPPER, new ExportConfiguration(null, null, null, null, null, null, 1000));
        }

        @Override
        void forEachRun(UUID projectUuid, RunQuery query, Consumer<RunRef> consumer) {
            runCalls++;
            super.forEachRun(projectUuid, query, consumer);
        }

        @Override
        void forEachStepBatch(UUID projectUuid, List<UUID> runUuids,
                BiConsumer<UUID, StepRef> consumer) {
            stepBatchCalls++;
            super.forEachStepBatch(projectUuid, runUuids, consumer);
        }

        @Override
        void forEachEventBatch(UUID projectUuid, List<UUID> runUuids,
                BiConsumer<UUID, EventRef> consumer) {
            eventBatchCalls++;
            super.forEachEventBatch(projectUuid, runUuids, consumer);
        }

        @Override
        void forEachStep(UUID projectUuid, UUID runUuid, Consumer<StepRef> consumer) {
            singleStepCalls++;
            super.forEachStep(projectUuid, runUuid, consumer);
        }

        @Override
        java.util.Map<UUID, RunRef> findBatch(UUID projectUuid, List<UUID> runUuids) {
            childLookupCalls++;
            return super.findBatch(projectUuid, runUuids);
        }
    }
}

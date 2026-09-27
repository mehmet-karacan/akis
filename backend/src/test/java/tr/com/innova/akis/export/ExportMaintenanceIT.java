package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.ExportModels.ExportStatus;
import tr.com.innova.akis.knowledge.KmStepJournal;
import tr.com.innova.akis.knowledge.WorkObjectStore;

class ExportMaintenanceIT {

    @Test
    void maintenanceQueriesExecuteAgainstFreshMigratedPostgres() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_bundle_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Generated isolated test DB required.");
        }
        var dataSource = new DriverManagerDataSource(url,
                required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/akis")
                .defaultSchema("akis").schemas("akis")
                .createSchemas(true).validateMigrationNaming(true)
                .load().migrate();
        var repository = new ExportJobRepository(JdbcClient.create(dataSource), new ObjectMapper());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertEquals(0, repository.expireOlderThan(now));
        assertEquals(0, repository.failStaleRunning(now.minusMinutes(30)));
        assertTrue(repository.listCleanupOutputs(now.minusDays(1), 100).isEmpty());
        assertEquals(false, repository.removeCleanupOutput(
                new ExportJobRepository.ExpiredOutput(1, UUID.randomUUID(), "akis_test.json"),
                now.minusDays(1)));
        var runs = new RunExportReader(JdbcClient.create(dataSource), new ObjectMapper());
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        runs.forEachStepBatch(UUID.randomUUID(), ids, (runUuid, step) -> {});
        runs.forEachEventBatch(UUID.randomUUID(), ids, (runUuid, event) -> {});
        assertTrue(runs.findBatch(UUID.randomUUID(), ids).isEmpty());
        var journal = new KmStepJournal(JdbcClient.create(dataSource), new ObjectMapper(), dataSource, 1000);
        journal.forEachRowBatch(UUID.randomUUID(), ids, (runUuid, row) -> {});
        assertTrue(journal.reconciliationBatch(UUID.randomUUID(), ids).isEmpty());
        var workObjects = new WorkObjectStore(JdbcClient.create(dataSource), dataSource, 1000);
        workObjects.forEachObjectRowBatch(UUID.randomUUID(), ids, (runUuid, row) -> {});

        JdbcClient jdbc = JdbcClient.create(dataSource);
        long projectId = jdbc.sql("insert into akis.proje(kod,ad) values ('EXPORT_CRASH_IT','Export crash IT') returning id")
                .query(Long.class).single();
        long actorId = jdbc.sql("""
                insert into akis.kullanici(kullanici_kodu,ad,gorunen_ad,durum,parola,parola_degistirilme_zamani)
                values ('export_crash_it','Export','Export','AKTIF','{argon2}test',current_timestamp)
                returning id
                """).query(Long.class).single();
        var job = repository.create(projectId, actorId,
                ExportProviderRegistry.DATASET_RUNS, ExportProviderRegistry.RESOURCE_RUN_HISTORY,
                ExportScope.ALL, new ObjectMapper().createObjectNode(), List.of(), false,
                now.plusDays(1));
        assertTrue(repository.claim(job.uuid()).isPresent());
        jdbc.sql("update akis.veri_export_isi set baslama_zamani=:old where uuid=:uuid")
                .param("old", now.minusHours(2)).param("uuid", job.uuid()).update();
        assertEquals(1, repository.failStaleRunning(now.minusMinutes(30)));
        var failed = repository.findByUuid(job.uuid()).orElseThrow();
        assertEquals(ExportStatus.FAILED, failed.status());
        assertEquals("EXPORT_DURATION_EXCEEDED", failed.errorCode());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

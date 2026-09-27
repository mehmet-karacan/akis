package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.execution.PendingRecipeConsumer;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProducerEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.PublicationEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ScheduleEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;

class PendingRecipeImportWriterIT {

    @Test
    void stagesOnlyDeclarativeRecipesAgainstTargetIds() {
        var dataSource = setUp();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var repository = new ProjectBundleRepository(jdbc, new ObjectMapper());
        var pending = new PendingRecipeRepository(jdbc);
        var writer = new PendingRecipeImportWriter(jdbc, pending);
        long actorId = jdbc.sql("""
                insert into akis.kullanici(gorunen_ad, kullanici_kodu, ad, durum)
                values ('Recipe Writer Test', :code, 'Recipe', 'PAROLA_BEKLIYOR') returning id
                """).param("code", "recipe_writer_" + UUID.randomUUID().toString().substring(0, 8))
                .query(Long.class).single();
        long environmentId = jdbc.sql("""
                insert into akis.ortam(kod, ad) values ('RECIPE_ENV', 'Recipe Environment') returning id
                """).query(Long.class).single();
        UUID environmentUuid = jdbc.sql("select uuid from akis.ortam where id = :id")
                .param("id", environmentId).query(UUID.class).single();
        var project = repository.insertProject(
                UUID.randomUUID(), "RECIPE_WRITER", "AKTIF", "Recipe Writer", null);
        long folderId = repository.insertFolder(project.id(), null, "ROOT", "AKTIF", "Root", null);
        long definitionId = repository.insertDefinition(
                project.id(), folderId, DefinitionType.SEQUENCE,
                "ORDER_SEQUENCE", "AKTIF", "Order sequence", null);
        var content = new ObjectMapper().createObjectNode();
        content.put("implementation", "REPOSITORY");
        content.put("start", 1);
        content.put("increment", 1);
        content.put("cycle", false);
        repository.insertVersion(definitionId, new ProjectBundleRepository.VersionRow(
                definitionId, 1, 1, "b".repeat(64), content, "v1", OffsetDateTime.now()));
        long versionId = jdbc.sql("""
                select id from akis.tanim_surumu where tanim_id = :definition and surum_no = 1
                """).param("definition", definitionId).query(Long.class).single();

        var publication = new PublicationEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, "RECIPE_ENV");
        OffsetDateTime startsAt = OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2030, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var schedule = new ScheduleEntry(
                "ORDER_DAILY", "Daily order", "0 0 2 * * *", "Europe/Istanbul",
                "SKIP", "RUN_ONCE", "PINNED", publication, startsAt, endsAt);
        var bundle = new ProjectBundle(
                ProjectBundleModels.FORMAT, ProjectBundleModels.FORMAT_VERSION,
                ProjectBundleModels.SCHEMA_VERSION, "a".repeat(64), OffsetDateTime.now(),
                new ProjectEntry("SOURCE", "AKTIF", "Source", null), List.of(), List.of(),
                new TopologyEntry(true, new ObjectMapper().createObjectNode()),
                new ProducerEntry("akis-backend", "0.1.0-SNAPSHOT", "test"),
                ProjectBundleModels.INCLUDED_SECTIONS, List.of(publication), List.of(schedule));
        assertThrows(ProjectBundleException.class, () -> writer.stage(
                project.id(), bundle,
                Map.of(DefinitionType.SEQUENCE, Map.of("ORDER_SEQUENCE", definitionId)),
                List.of(), actorId));
        assertEquals(0, pending.findPendingPublicationRecipes(project.id()).size());
        writer.stage(project.id(), bundle,
                Map.of(DefinitionType.SEQUENCE, Map.of("ORDER_SEQUENCE", definitionId)),
                List.of(new GlobalBinding(GlobalResourceType.ENVIRONMENT, "RECIPE_ENV",
                        GlobalBindingMode.BIND_EXISTING, environmentUuid, null, null)),
                actorId);

        var publications = pending.findPendingPublicationRecipes(project.id());
        var schedules = pending.findPendingScheduleRecipes(project.id());
        assertEquals(1, publications.size());
        assertEquals(definitionId, publications.getFirst().definitionId());
        assertEquals(versionId, publications.getFirst().definitionVersionId());
        assertEquals(environmentId, publications.getFirst().environmentId());
        assertEquals(1, schedules.size());
        assertEquals(publications.getFirst().id(), schedules.getFirst().publicationRecipeId());
        assertEquals("ORDER_DAILY", schedules.getFirst().code());
        assertEquals(startsAt, schedules.getFirst().startsAt());
        assertEquals(endsAt, schedules.getFirst().endsAt());
        assertEquals(0L, jdbc.sql("select count(*) from akis.yayin where proje_id = :project")
                .param("project", project.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.zamanlama where proje_id = :project")
                .param("project", project.id()).query(Long.class).single());

        String versionHash = jdbc.sql("select icerik_ozeti from akis.tanim_surumu where id = :version")
                .param("version", versionId).query(String.class).single();
        long validationId = jdbc.sql("""
                insert into akis.dogrulama(
                    proje_id, tanim_surumu_id, icerik_ozeti, sonuc, sonuc_ayrintisi)
                values (:project, :version, :hash, 'GECTI', '{}') returning id
                """)
                .param("project", project.id()).param("version", versionId)
                .param("hash", versionHash).query(Long.class).single();
        long scenarioId = jdbc.sql("""
                insert into akis.senaryo(
                    proje_id, tanim_surumu_id, dogrulama_id, surum_no,
                    plan_sema_surumu, plan_ozeti, plan)
                values (:project, :version, :validation, 1, 2, :planHash, '{}') returning id
                """)
                .param("project", project.id()).param("version", versionId)
                .param("validation", validationId).param("planHash", "c".repeat(64))
                .query(Long.class).single();
        long publicationId = jdbc.sql("""
                insert into akis.yayin(
                    proje_id, senaryo_id, ortam_id, yayin_no, durum,
                    bagimlilik_ozeti, fiziksel_manifesto)
                values (:project, :scenario, :environment, 1, 'ONAY_BEKLIYOR', 'test', '{}')
                returning id
                """)
                .param("project", project.id()).param("scenario", scenarioId)
                .param("environment", environmentId).query(Long.class).single();
        var consumer = new PendingRecipeConsumer(jdbc);
        assertThrows(ApiException.class, () -> consumer.consume(
                project.id(), scenarioId, environmentId + 1, publicationId));
        assertThrows(ApiException.class, () -> consumer.consume(
                project.id(), scenarioId, environmentId, publicationId));
        assertEquals(0L, jdbc.sql("select count(*) from akis.zamanlama where proje_id = :project")
                .param("project", project.id()).query(Long.class).single());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(ApiException.class, () -> transaction.executeWithoutResult(ignored -> {
            long rolledBackPublicationId = jdbc.sql("""
                    insert into akis.yayin(
                        proje_id, senaryo_id, ortam_id, yayin_no, durum,
                        bagimlilik_ozeti, fiziksel_manifesto)
                    values (:project, :scenario, :environment, 3, 'ONAY_BEKLIYOR',
                            'rollback-test', '{}') returning id
                    """)
                    .param("project", project.id()).param("scenario", scenarioId)
                    .param("environment", environmentId).query(Long.class).single();
            consumer.consume(project.id(), scenarioId, environmentId, rolledBackPublicationId);
        }));
        assertEquals(0L, jdbc.sql("""
                select count(*) from akis.yayin
                 where proje_id = :project and bagimlilik_ozeti = 'rollback-test'
                """).param("project", project.id()).query(Long.class).single());
        jdbc.sql("""
                update akis.kullanici
                   set durum = 'AKTIF', parola = '{argon2}test-only',
                       parola_degistirilme_zamani = current_timestamp
                 where id = :actor
                """).param("actor", actorId).update();
        consumer.consume(project.id(), scenarioId, environmentId, publicationId);
        consumer.consume(project.id(), scenarioId, environmentId, publicationId);
        var actualWindow = jdbc.sql("""
                select baslangic_zamani, bitis_zamani from akis.zamanlama
                 where proje_id = :project and kod = 'ORDER_DAILY'
                """).param("project", project.id())
                .query((rs, row) -> List.of(
                        rs.getObject("baslangic_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class)))
                .single();
        assertEquals(List.of(startsAt, endsAt), actualWindow);
        assertEquals(1L, jdbc.sql("""
                select count(*) from akis.zamanlama
                 where proje_id = :project and yayin_id = :publication
                   and kod = 'ORDER_DAILY' and durum_kodu = 'ASKIDA'
                   and sonraki_tetikleme_zamani is null
                   and son_tetikleme_zamani is null
                """)
                .param("project", project.id())
                .param("publication", publicationId)
                .query(Long.class).single());
        long differentPublicationId = jdbc.sql("""
                insert into akis.yayin(
                    proje_id, senaryo_id, ortam_id, yayin_no, durum,
                    bagimlilik_ozeti, fiziksel_manifesto)
                values (:project, :scenario, :environment, 2, 'ONAY_BEKLIYOR', 'test-2', '{}')
                returning id
                """)
                .param("project", project.id()).param("scenario", scenarioId)
                .param("environment", environmentId).query(Long.class).single();
        assertThrows(ApiException.class, () -> consumer.consume(
                project.id(), scenarioId, environmentId, differentPublicationId));
        jdbc.sql("""
                update akis.zamanlama set arsivlenme_zamani = current_timestamp
                 where proje_id = :project and kod = 'ORDER_DAILY'
                """).param("project", project.id()).update();
        assertThrows(ApiException.class, () -> consumer.consume(
                project.id(), scenarioId, environmentId, publicationId));
    }

    private DriverManagerDataSource setUp() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_bundle_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Generated bundle DB required.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/akis")
                .defaultSchema("akis").schemas("akis").createSchemas(true)
                .validateMigrationNaming(true).load().migrate();
        return dataSource;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for this integration test.");
        }
        return value;
    }
}

package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionType;

/**
 * Focused round-trip tests for {@link PendingRecipeRepository}: imported publication
 * and schedule recipes must stay attached to real target-side definition/version/
 * environment rows, with no fake publication row and no activation state.
 */
class PendingRecipeRepositoryIT {

    private JdbcClient setUp() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_bundle_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Generated bundle DB required.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"));
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/akis")
                .defaultSchema("akis")
                .schemas("akis")
                .createSchemas(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        return JdbcClient.create(dataSource);
    }

    @Test
    void pendingRecipesStayAttachedToTargetDefinitionVersionAndEnvironmentWithNoActivation() {
        JdbcClient jdbc = setUp();
        var mapper = new ObjectMapper();
        var bundleRepository = new ProjectBundleRepository(jdbc, mapper);
        var repository = new PendingRecipeRepository(jdbc);
        String checksum = "a".repeat(64);

        long actorId = insertActor(jdbc);
        long environmentId = jdbc.sql("insert into akis.ortam(kod, ad) values ('PROD', 'Production') returning id")
                .query(Long.class).single();
        var project = bundleRepository.insertProject(
                UUID.randomUUID(), "PENDING_RECIPE", "AKTIF", "Pending Recipe", null);
        long folder = bundleRepository.insertFolder(project.id(), null, "ROOT", "AKTIF", "Root", null);
        long definition = bundleRepository.insertDefinition(
                project.id(), folder, DefinitionType.SEQUENCE, "ORDER_SEQUENCE", "AKTIF", "Order sequence", null);
        var content = mapper.createObjectNode();
        content.put("implementation", "REPOSITORY");
        content.put("start", 1);
        content.put("increment", 1);
        content.put("cycle", false);
        bundleRepository.insertVersion(definition, new ProjectBundleRepository.VersionRow(
                definition, 1, 1, "b".repeat(64), content, "v1", OffsetDateTime.now()));
        long definitionVersionId = jdbc.sql(
                        "select id from akis.tanim_surumu where tanim_id = :definition and surum_no = 1")
                .param("definition", definition).query(Long.class).single();

        long publicationRecipeId = repository.insertPendingPublicationRecipe(
                project.id(), definition, definitionVersionId, environmentId, checksum, actorId);
        long scheduleRecipeId = repository.insertPendingScheduleRecipe(
                project.id(), publicationRecipeId, "DAILY_RUN", "Daily Run",
                "0 0 2 * * *", "Europe/Istanbul", "SKIP", "RUN_ONCE", "LATEST_ACTIVE", checksum, actorId);

        var publications = repository.findPendingPublicationRecipes(project.id());
        var schedules = repository.findPendingScheduleRecipes(project.id());

        assertEquals(1, publications.size());
        var publication = publications.getFirst();
        assertEquals(publicationRecipeId, publication.id());
        assertNotNull(publication.uuid());
        assertEquals(project.id(), publication.projectId());
        assertEquals(definition, publication.definitionId());
        assertEquals(definitionVersionId, publication.definitionVersionId());
        assertEquals(environmentId, publication.environmentId());
        assertEquals(checksum, publication.bundleChecksum());
        assertNotNull(publication.createdAt());

        assertEquals(1, schedules.size());
        var schedule = schedules.getFirst();
        assertEquals(scheduleRecipeId, schedule.id());
        assertEquals(publicationRecipeId, schedule.publicationRecipeId());
        assertEquals("DAILY_RUN", schedule.code());
        assertEquals("Daily Run", schedule.name());
        assertEquals("0 0 2 * * *", schedule.cronExpression());
        assertEquals("Europe/Istanbul", schedule.timeZone());
        assertEquals("SKIP", schedule.conflictPolicy());
        assertEquals("RUN_ONCE", schedule.misfirePolicy());
        assertEquals("LATEST_ACTIVE", schedule.publicationViewPolicy());

        // No activation/runtime-evidence columns exist to copy: durum_kodu,
        // sonraki/son tetikleme, fiziksel_manifesto have no counterpart here.
        long recipeColumnCount = jdbc.sql("""
                        select count(*) from information_schema.columns
                         where table_schema = 'akis' and table_name = 'ithal_zamanlama_tarifi'
                           and column_name in ('durum_kodu', 'sonraki_tetikleme_zamani', 'son_tetikleme_zamani')
                        """)
                .query(Long.class).single();
        assertEquals(0L, recipeColumnCount);

        long otherDefinition = bundleRepository.insertDefinition(
                project.id(), folder, DefinitionType.SEQUENCE, "OTHER_SEQUENCE", "AKTIF", "Other sequence", null);
        bundleRepository.insertVersion(otherDefinition, new ProjectBundleRepository.VersionRow(
                otherDefinition, 1, 1, "e".repeat(64), content, "v1", OffsetDateTime.now()));
        long otherVersionId = jdbc.sql(
                        "select id from akis.tanim_surumu where tanim_id = :definition and surum_no = 1")
                .param("definition", otherDefinition).query(Long.class).single();
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertPendingPublicationRecipe(
                project.id(), definition, otherVersionId, environmentId, checksum, actorId));
    }

    @Test
    void duplicatePublicationRecipeForSameTargetVersionAndEnvironmentIsRejected() {
        JdbcClient jdbc = setUp();
        var mapper = new ObjectMapper();
        var bundleRepository = new ProjectBundleRepository(jdbc, mapper);
        var repository = new PendingRecipeRepository(jdbc);

        long actorId = insertActor(jdbc);
        long environmentId = jdbc.sql("insert into akis.ortam(kod, ad) values ('STAGING', 'Staging') returning id")
                .query(Long.class).single();
        var project = bundleRepository.insertProject(
                UUID.randomUUID(), "PENDING_RECIPE_DUP", "AKTIF", "Pending Recipe Dup", null);
        long folder = bundleRepository.insertFolder(project.id(), null, "ROOT", "AKTIF", "Root", null);
        long definition = bundleRepository.insertDefinition(
                project.id(), folder, DefinitionType.SEQUENCE, "ORDER_SEQUENCE", "AKTIF", "Order sequence", null);
        var content = mapper.createObjectNode();
        content.put("implementation", "REPOSITORY");
        content.put("start", 1);
        content.put("increment", 1);
        content.put("cycle", false);
        bundleRepository.insertVersion(definition, new ProjectBundleRepository.VersionRow(
                definition, 1, 1, "c".repeat(64), content, "v1", OffsetDateTime.now()));
        long definitionVersionId = jdbc.sql(
                        "select id from akis.tanim_surumu where tanim_id = :definition and surum_no = 1")
                .param("definition", definition).query(Long.class).single();
        String checksum = "d".repeat(64);
        repository.insertPendingPublicationRecipe(
                project.id(), definition, definitionVersionId, environmentId, checksum, actorId);

        assertThrows(DataIntegrityViolationException.class, () -> repository.insertPendingPublicationRecipe(
                project.id(), definition, definitionVersionId, environmentId, checksum, actorId));
    }

    private long insertActor(JdbcClient jdbc) {
        return jdbc.sql("""
                        insert into akis.kullanici(gorunen_ad, kullanici_kodu, ad, durum)
                        values ('Pending Recipe Test', :code, 'Pending', 'PAROLA_BEKLIYOR')
                        returning id
                        """)
                .param("code", "pending_recipe_" + UUID.randomUUID().toString().substring(0, 8))
                .query(Long.class).single();
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for this integration test.");
        }
        return value;
    }
}

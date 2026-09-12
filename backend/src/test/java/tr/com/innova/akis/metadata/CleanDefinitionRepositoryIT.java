package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class CleanDefinitionRepositoryIT {

    private static MetadataRepository repository;
    private static long projectId;

    @BeforeAll
    static void connectToCleanBaseline() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_definitions_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean definition test requires its generated test database.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        repository = new MetadataRepository(jdbc, new ObjectMapper());
        projectId = jdbc.sql("insert into akis.proje(kod, ad) values ('DEFINITION_IT', 'Definition IT') returning id")
                .query(Long.class).single();
    }

    @Test
    void createsMovesDraftsAndVersionsProjectDefinitions() {
        var root = repository.createFolder(
                projectId, UUID.randomUUID(), null, "ROOT", "GELISTIRME", "Root", null);
        var child = repository.createFolder(
                projectId, UUID.randomUUID(), root.id(), "CHILD", "GELISTIRME", "Child", null);
        var definition = repository.createDefinition(
                projectId, UUID.randomUUID(), child.id(), DefinitionType.PROCEDURE,
                "LOAD_ORDERS", "Load Orders", null);
        var content = new ObjectMapper().createObjectNode();
        content.putArray("tasks");
        var draft = repository.createDraft(definition.id(), 1, content);
        var changedContent = new ObjectMapper().createObjectNode();
        changedContent.putArray("tasks").addObject().put("id", "READ");
        var updated = repository.updateDraft(
                definition.id(), draft.version(), 1, changedContent);
        var version = repository.createVersion(
                definition.id(), 1, "a".repeat(64), updated.content(), "First runnable input");

        assertEquals(2, repository.listFolders(projectId).size());
        assertEquals(DefinitionType.PROCEDURE, repository.listDefinitions(projectId, null).getFirst().type());
        assertEquals(2, updated.version());
        assertEquals(1, version.versionNumber());
        assertThrows(ApiException.class, () -> repository.updateDraft(
                definition.id(), draft.version(), 1, content));
        assertEquals(root.uuid(), repository.moveDefinition(
                projectId, definition.id(), root.id(), definition.version()).folderUuid());
    }

    @Test
    void keepsAllowedSystemLibraryDefinitionsOutsideProjectFolders() {
        var global = repository.createGlobalDefinition(
                UUID.randomUUID(), DefinitionType.VARIABLE,
                "BUSINESS_DATE", "Business Date", null);
        var content = new ObjectMapper().createObjectNode();
        content.put("type", "DATE");
        var draft = repository.createDraft(global.id(), 1, content);
        var version = repository.createVersion(
                global.id(), 1, "b".repeat(64), draft.content(), "System library variable");

        assertEquals(null, global.projectId());
        assertEquals(null, global.folderUuid());
        assertEquals(DefinitionType.VARIABLE, repository.listGlobalDefinitions(null).getFirst().type());
        assertEquals(1, version.versionNumber());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

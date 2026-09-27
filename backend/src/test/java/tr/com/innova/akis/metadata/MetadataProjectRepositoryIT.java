package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.MetadataModels.ProjectRow;
import tr.com.innova.akis.security.AuthorizationRepository;
import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;

class MetadataProjectRepositoryIT {

    private static JdbcClient jdbc;
    private static MetadataRepository repository;

    @BeforeAll
    static void connectToCleanBaseline() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_[a-z_]*test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean project test requires its generated test database.");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        repository = new MetadataRepository(jdbc, new ObjectMapper());
    }

    @Test
    void createsListsAndFindsProjectFromAkisSchema() {
        UUID uuid = UUID.randomUUID();
        String code = "PROJECT_" + uuid.toString().replace("-", "").toUpperCase();

        ProjectRow created = repository.createProject(
                uuid, code, "Project Gate", "Clean schema", null);

        assertEquals(uuid, created.uuid());
        assertEquals("AKTIF", created.status());
        assertEquals(1L, created.version());
        assertEquals(created, repository.findProject(uuid).orElseThrow());
        assertTrue(repository.listProjects().stream().anyMatch(project -> project.uuid().equals(uuid)));
    }

    @Test
    void derivesArchivedStateFromTimestampInsteadOfGenericStatusCode() {
        UUID uuid = UUID.randomUUID();
        String code = "ARCHIVE_" + uuid.toString().replace("-", "").toUpperCase();
        ProjectRow created = repository.createProject(
                uuid, code, "Archived Project", null, null);
        jdbc.sql("update akis.proje set arsivlenme_zamani = current_timestamp where id = :id")
                .param("id", created.id())
                .update();

        assertEquals("ARSIVLENDI", repository.findProject(uuid).orElseThrow().status());
    }

    @Test
    void assignsCreatorAsProjectManagerAndWritesAuditIdentity() {
        String userCode = "creator-" + UUID.randomUUID();
        UUID creatorUuid = UUID.randomUUID();
        long creatorId = jdbc.sql("""
                        insert into akis.kullanici(
                            uuid, kullanici_kodu, ad, gorunen_ad, durum,
                            parola, parola_degistirilme_zamani)
                        values (:uuid, :userCode, 'Project', 'Project Creator', 'AKTIF',
                                '{argon2}test', current_timestamp)
                        returning id
                        """)
                .param("uuid", creatorUuid)
                .param("userCode", userCode)
                .query(Long.class)
                .single();
        UUID projectUuid = UUID.randomUUID();
        ProjectRow project = repository.createProject(
                projectUuid,
                "OWNED_" + projectUuid.toString().replace("-", "").toUpperCase(),
                "Owned Project",
                null,
                creatorId);

        var access = new AuthorizationRepository(jdbc).projectAccess(
                new PrincipalIdentity(creatorId, creatorUuid, userCode, "creator"),
                projectUuid,
                "UYE_YONET");

        assertTrue(access.visible());
        assertTrue(access.permitted());
        assertEquals(creatorId, jdbc.sql(
                        "select olusturan_kullanici_id from akis.proje where id = :id")
                .param("id", project.id())
                .query(Long.class)
                .single());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the clean project test.");
        }
        return value;
    }
}

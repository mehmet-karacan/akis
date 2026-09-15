package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

class CleanTopologyRepositoryIT {

    @Test
    void recordAttributionUsesSuccessfulEventsOnly() {
        var record = repository.createLogicalSchema(projectId, UUID.randomUUID(), "AUDIT_TEST", "Audit", null);
        var authorization = new tr.com.innova.akis.security.AuthorizationService(null, "fail-closed") {
            @Override public void requireProjectPermission(UUID project, String permission) { assertEquals(projectUuid, project); }
        };
        var controller = new tr.com.innova.akis.web.RecordAuditController(jdbc, authorization);
        var path = "/api/v1/projects/" + projectUuid + "/logical-schemas";
        for (int index = 0; index < 3; index++) {
            var detail = new ObjectMapper().createObjectNode().put("path", index == 0 ? path : path + "/" + record.uuid()).put("principal", index == 0 ? "creator" : index == 1 ? "editor" : "failed-editor");
            jdbc.sql("insert into akis.denetim_olayi(proje_id, dis_nesne_uuid, korelasyon_kodu, aktor_turu, eylem_kodu, sonuc, olay_zamani, ayrinti) values (:project, :record, 'test', 'KULLANICI', :action, :result, current_timestamp, cast(:detail as jsonb))")
                .param("project", projectId).param("record", record.uuid()).param("action", index == 0 ? "HTTP_POST" : "HTTP_PATCH").param("result", index == 2 ? "BASARISIZ" : "BASARILI").param("detail", detail.toString()).update();
        }
        var audit = controller.list(projectUuid, "logical-schemas").stream().filter(item -> item.uuid().equals(record.uuid())).findFirst().orElseThrow();
        assertEquals("creator", audit.createdBy());
        assertEquals("editor", audit.updatedBy());
        assertTrue(audit.createdAt() != null && audit.updatedAt() != null);
        for (String kind : java.util.List.of("connections", "environments", "physical-schemas", "models", "definitions", "folders")) controller.list(projectUuid, kind);
        assertThrows(RuntimeException.class, () -> controller.list(projectUuid, "unsafe-table"));
    }

    @Test
    void contextEditsCheckVersionsAndArchiveWithoutDeletingHistory() {
        var logical = repository.createLogicalSchema(projectId, UUID.randomUUID(), "EDIT_CONTEXT", "Before", null);
        assertTrue(repository.changeContext(projectId, logical.uuid(), true, "After", "Description", logical.version(), false));
        assertTrue(!repository.changeContext(projectId, logical.uuid(), true, "Stale", null, logical.version(), false));
        var updated = repository.findLogicalSchema(projectId, logical.uuid()).orElseThrow();
        assertEquals("After", updated.name());
        assertTrue(!repository.contextInUse(projectId, logical.uuid(), true));
        service.changeContext(projectUuid, logical.uuid(), true, null, null, updated.version(), true);
        assertTrue(repository.findLogicalSchema(projectId, logical.uuid()).isEmpty());
        var environment = repository.createEnvironment(projectId, UUID.randomUUID(), "EDIT_ENV", "DUSUK", 1, new ObjectMapper().createObjectNode(), "Before");
        service.changeContext(projectUuid, environment.uuid(), false, "After", null, environment.version(), false);
        assertTrue(!repository.contextInUse(projectId, environment.uuid(), false));
        assertThrows(RuntimeException.class, () -> service.changeContext(projectUuid, environment.uuid(), false, null, null, environment.version(), true));
        service.changeContext(projectUuid, environment.uuid(), false, null, null, environment.version() + 1, true);
        assertTrue(repository.findEnvironment(projectId, environment.uuid()).isEmpty());
    }

    private static TopologyRepository repository;
    private static TopologyService service;
    private static JdbcClient jdbc;
    private static long projectId;
    private static UUID projectUuid;

    @BeforeAll
    static void connectToCleanBaseline() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_connections_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean topology test requires its generated test database.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        repository = new TopologyRepository(jdbc, new ObjectMapper());
        service = new TopologyService(repository, new ObjectMapper(), new SecretValueSanitizer());
        projectId = jdbc.sql("insert into akis.proje(kod, ad) values ('TOPOLOGY_IT', 'Topology IT') returning id")
                .query(Long.class).single();
        projectUuid = jdbc.sql("select uuid from akis.proje where id = :id")
                .param("id", projectId).query(UUID.class).single();
    }

    @Test
    void persistsTypedConnectionPolicyAndSchemaBindingInAkis() {
        var connection = repository.createConnection(
                projectId, UUID.randomUUID(), "ORACLE_MAIN", "ORACLE", "Oracle Main", null);
        var policy = new ObjectMapper().createObjectNode()
                .put("connectTimeoutMs", 12000)
                .put("readTimeoutMs", 45000)
                .put("networkTimeoutMs", 50000)
                .put("queryTimeoutSeconds", 90)
                .put("purpose", "ETL");
        var version = repository.createConnectionVersion(
                projectId, connection.id(), UUID.randomUUID(), 1, "JDBC",
                "oracle.jdbc.OracleDriver", "db.example", "ORCL", null, null,
                null, "DISABLED", 1521, 2, policy);
        repository.bindCredential(projectId, version.id(), "ENV", "AKIS_TEST_PASSWORD", "KIMLIK", "reader");
        var physical = repository.createPhysicalSchema(
                projectId, connection.id(), UUID.randomUUID(), "MAIN_APP", "APP", "App Schema");
        var logical = repository.createLogicalSchema(
                projectId, UUID.randomUUID(), "ORDERS", "Orders", null);
        var environment = repository.createEnvironment(
                projectId, UUID.randomUUID(), "TEST", "DUSUK", 1,
                new ObjectMapper().createObjectNode(), "Test");
        var binding = repository.createSchemaBinding(
                projectId, UUID.randomUUID(), logical.id(), environment.id(),
                physical.id(), version.id());

        assertTrue(repository.contextInUse(projectId, logical.uuid(), true));
        assertTrue(repository.contextInUse(projectId, environment.uuid(), false));
        assertThrows(RuntimeException.class, () -> service.changeContext(projectUuid, logical.uuid(), true, null, null, logical.version(), true));
        assertThrows(RuntimeException.class, () -> service.changeContext(projectUuid, environment.uuid(), false, null, null, environment.version(), true));
        var stored = repository.listConnectionVersions(projectId, connection.id()).getFirst();
        assertEquals(12000, stored.policy().get("connectTimeoutMs").intValue());
        assertEquals("ETL", stored.policy().get("purpose").stringValue());
        assertEquals("DISABLED", stored.tlsMode());
        assertEquals("AKTIF", repository.listConnections(projectId).getFirst().status());
        assertEquals(binding.uuid(), repository.listSchemaBindings(projectId).stream()
                .filter(item -> item.uuid().equals(binding.uuid())).findFirst().orElseThrow().uuid());
        assertTrue(repository.findPhysicalSchema(projectId, physical.uuid()).isPresent());
        var catalog = repository.listConnectionCatalog(projectId).stream()
                .filter(item -> item.connection().uuid().equals(connection.uuid()))
                .findFirst().orElseThrow();
        assertEquals(version.uuid(), catalog.displayedVersion().uuid());
        assertEquals(1, catalog.latestVersionNumber());
        assertEquals(1, catalog.physicalSchemaCount());
        assertEquals(1, catalog.logicalSchemaCount());

        var nextVersion = repository.createConnectionVersion(
                projectId, connection.id(), UUID.randomUUID(), 2, "JDBC",
                "oracle.jdbc.OracleDriver", "db-next.example", "ORCL", null, null,
                null, "DISABLED", 1521, 2, policy);
        UUID successfulTestUuid = UUID.randomUUID();
        jdbc.sql("""
                insert into akis.baglanti_testi(
                    proje_id, baglanti_surumu_id, deneme_no, sonuc,
                    urun_adi, urun_surumu, hedef_kimlik_surumu, hedef_parmak_izi,
                    baslama_zamani, tamamlanma_zamani, sure_milisaniye, uuid)
                values (:projectId, :versionId, 1, 'BASARILI',
                        'Oracle', '19c', 1, repeat('a', 64),
                        current_timestamp, current_timestamp, 0, :uuid)
                """)
                .param("projectId", projectId)
                .param("versionId", nextVersion.id())
                .param("uuid", successfulTestUuid)
                .update();
        jdbc.sql("""
                update akis.baglanti_surumu
                   set durum = 'TEST_EDILDI',
                       son_basarili_test_uuid = :testUuid,
                       hedef_kimlik_surumu = 1,
                       hedef_parmak_izi = repeat('a', 64),
                       test_edilme_zamani = current_timestamp
                 where id = :id
                """)
                .param("id", nextVersion.id())
                .param("testUuid", successfulTestUuid)
                .update();
        var updatedBinding = service.updateSchemaBinding(
                projectUuid, binding.uuid(), logical.uuid(), environment.uuid(),
                physical.uuid(), binding.version());
        assertEquals(binding.uuid(), updatedBinding.uuid());
        assertEquals(nextVersion.uuid(), updatedBinding.connectionVersionUuid());
        assertEquals(2, updatedBinding.version());

        var createdLogical = service.createLogicalSchema(
                projectUuid, "CURRENT_TARGET", "Current Target", null,
                environment.uuid(), physical.uuid());
        var createdMapping = repository.listSchemaBindings(projectId).stream()
                .filter(item -> item.logicalSchemaUuid().equals(createdLogical.uuid()))
                .findFirst().orElseThrow();
        assertEquals(physical.uuid(), createdMapping.physicalSchemaUuid());
        assertEquals(nextVersion.uuid(), createdMapping.connectionVersionUuid());
    }

    @Test
    void createsOracleDefinitionWithItsInitialVersionAsOneServiceBoundary() throws Exception {
        var policy = new ObjectMapper().createObjectNode()
                .put("connectTimeoutMs", 10000)
                .put("readTimeoutMs", 30000)
                .put("networkTimeoutMs", 30000)
                .put("queryTimeoutSeconds", 300);

        var created = service.createOracleConnectionWithInitialVersion(
                projectUuid, "ORACLE_ATOMIC", "Oracle Atomic", null,
                "JDBC", "oracle.example", "ORCL", null, 1521, null,
                2, policy, "ENV", "AKIS_ORACLE_ATOMIC_CREDENTIAL", "reader");

        assertEquals(created.connection().id(), created.initialVersion().connectionId());
        assertEquals("oracle.example", created.initialVersion().host());
        assertEquals("reader", repository.listConnectionVersions(
                projectId, created.connection().id()).getFirst().username());
        assertEquals(1, repository.listConnectionVersions(
                projectId, created.connection().id()).size());
        assertTrue(TopologyService.class.getDeclaredMethod(
                        "createOracleConnectionWithInitialVersion",
                        UUID.class, String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, Integer.class, String.class,
                        int.class, tools.jackson.databind.JsonNode.class, String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void blocksArchivingAConnectionWithLogicalDependencies() {
        var connection = repository.createConnection(
                projectId, UUID.randomUUID(), "ARCHIVE_ME", "ORACLE", "Archive Me", null);
        var version = repository.createConnectionVersion(
                projectId, connection.id(), UUID.randomUUID(), 1, "JDBC",
                "oracle.jdbc.OracleDriver", "archive.example", "ORCL", null, null,
                null, "DISABLED", 1521, 2, new ObjectMapper().createObjectNode());
        var physical = repository.createPhysicalSchema(
                projectId, connection.id(), UUID.randomUUID(), "ARCHIVE_APP", "APP", "App");
        var logical = repository.createLogicalSchema(
                projectId, UUID.randomUUID(), "ARCHIVE_LOGICAL", "Archive Logical", null);
        var environment = repository.createEnvironment(
                projectId, UUID.randomUUID(), "ARCHIVE_TEST", "DUSUK", 1,
                new ObjectMapper().createObjectNode(), "Archive Test");
        repository.createSchemaBinding(
                projectId, UUID.randomUUID(), logical.id(), environment.id(), physical.id(), version.id());

        var updated = service.updateConnection(
                projectUuid, connection.uuid(), "ARCHIVE_RENAMED", "Renamed", "Description", connection.version());
        assertEquals("ARCHIVE_RENAMED", updated.code());
        assertEquals(2, updated.version());

        var conflict = assertThrows(tr.com.innova.akis.metadata.ApiException.class,
                () -> service.archiveConnection(projectUuid, connection.uuid(), updated.version()));
        assertEquals("CONNECTION_IN_USE", conflict.code());
        assertTrue(repository.findConnection(projectId, connection.uuid()).isPresent());
        assertTrue(repository.findPhysicalSchema(projectId, physical.uuid()).isPresent());
        assertTrue(repository.findLogicalSchema(projectId, logical.uuid()).isPresent());
        assertEquals(logical.name(), service.listConnectionDependencies(projectUuid, connection.uuid()).getFirst().name());

        var unused = repository.createConnection(
                projectId, UUID.randomUUID(), "ARCHIVE_UNUSED", "ORACLE", "Archive Unused", null);
        service.archiveConnection(projectUuid, unused.uuid(), unused.version());
        assertTrue(repository.findConnection(projectId, unused.uuid()).isEmpty());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

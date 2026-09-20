package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.ConnectionCredentialCipher;
import tr.com.innova.akis.topology.TopologyService.ConnectionInput;
import tr.com.innova.akis.topology.TopologyService.PhysicalSchemaInput;

/** Runs against the generated clean baseline; topology tables are global (no project scope) since V033. */
class CleanTopologyRepositoryIT {

    private static TopologyRepository repository;
    private static TopologyService service;
    private static ConnectionCredentialCipher cipher;
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
        var mapper = new ObjectMapper();
        cipher = new ConnectionCredentialCipher("topology-it-key");
        repository = new TopologyRepository(jdbc, mapper);
        service = new TopologyService(repository, mapper, cipher, null);
        projectId = jdbc.sql("insert into akis.proje(kod, ad) values ('TOPOLOGY_IT', 'Topology IT') returning id")
                .query(Long.class).single();
        projectUuid = jdbc.sql("select uuid from akis.proje where id = :id")
                .param("id", projectId).query(UUID.class).single();
    }

    private static ConnectionInput oracle(String code, String host, String password) {
        return new ConnectionInput(code, code + " name", null, "ORACLE", "JDBC", null, host, 1521, "ORCL", null,
                null, null, null, "reader", password, null, null, 12000, 45000, 90, null, null, null);
    }

    @Test
    void recordAttributionUsesSuccessfulEventsOnly() {
        var record = repository.createLogicalSchema("AUDIT_TEST", "Audit", null, "ORACLE", null);
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
    void connectionStoresTheEncryptedPasswordAndKeepsItOnUpdatesWithoutOne() {
        var created = service.createConnection(oracle("ORACLE_MAIN", "db.example", "local-secret"));
        assertTrue(created.hasPassword());
        assertEquals("oracle.jdbc.OracleDriver", created.driverReference());
        assertEquals(12000, created.connectTimeoutMs());
        assertEquals("ETKIN", created.status());
        String stored = repository.findEncryptedPassword(created.id()).orElseThrow();
        assertFalse(stored.contains("local-secret"));
        assertEquals("local-secret", cipher.decrypt(stored));

        var renamed = service.updateConnection(created.uuid(), oracle("ORACLE_RENAMED", "db-next.example", null));
        assertEquals("ORACLE_RENAMED", renamed.code());
        assertEquals("db-next.example", renamed.host());
        assertEquals(stored, repository.findEncryptedPassword(created.id()).orElseThrow());

        assertThrows(ApiException.class, () -> service.createConnection(oracle("ORACLE_RENAMED", "x.example", "pw")));
        assertThrows(ApiException.class, () -> service.createConnection(oracle("NO_PASSWORD", "x.example", null)));
        var badIdentifier = new ConnectionInput("BAD", "Bad", null, "ORACLE", "JDBC", null, "h.example", 1521, "ORCL", "ORCL",
                null, null, null, "reader", "pw", null, null, null, null, null, null, null, null);
        assertThrows(ApiException.class, () -> service.createConnection(badIdentifier));
    }

    @Test
    void physicalSchemasDefaultTheirWorkSchemaAndKeepOneDefaultPerConnection() {
        var connection = service.createConnection(oracle("PHYSICAL_HOST", "db.example", "pw"));
        var app = service.createPhysicalSchema(new PhysicalSchemaInput(connection.uuid(), null, null, null, null,
                "app", null, null, true, null, null, null, null, null, null, null, null));
        assertEquals("APP", app.schemaName());
        assertEquals("APP", app.workSchemaName());
        assertEquals("APP", app.code());
        assertEquals("C$_", app.loadingPrefix());
        assertEquals("ORACLE", app.databaseType());
        assertTrue(app.defaultSchema());

        var stage = service.createPhysicalSchema(new PhysicalSchemaInput(connection.uuid(), "STAGE_AREA", "Stage", null, null,
                "STAGE", null, "STAGE_WORK", true, "L$_", "I$_", "E$_", "T$_", null, null, null, null));
        assertEquals("STAGE_WORK", stage.workSchemaName());
        assertTrue(stage.defaultSchema());
        assertFalse(repository.findPhysicalSchema(app.uuid()).orElseThrow().defaultSchema());

        var duplicatePrefix = new PhysicalSchemaInput(connection.uuid(), null, null, null, null,
                "OTHER", null, null, false, "X$_", "X$_", "E$_", "T$_", null, null, null, null);
        assertThrows(ApiException.class, () -> service.createPhysicalSchema(duplicatePrefix));
        var conflict = assertThrows(ApiException.class, () -> service.deleteConnection(connection.uuid()));
        assertEquals("CONNECTION_HAS_PHYSICAL_SCHEMAS", conflict.code());
    }

    @Test
    void schemaBindingsEnforceOneTechnologyAndBlockDependentDeletes() {
        var connection = service.createConnection(oracle("BINDING_HOST", "db.example", "pw"));
        var physical = service.createPhysicalSchema(new PhysicalSchemaInput(connection.uuid(), null, null, null, null,
                "ORDERS_APP", null, null, false, null, null, null, null, null, null, null, null));
        var environment = service.createEnvironment("BIND_TEST", "Bind Test", null, "DUSUK", false, new ObjectMapper().createObjectNode());
        var logical = service.createLogicalSchema("ORDERS", "Orders", null, null, environment.uuid(), physical.uuid());
        assertEquals("ORACLE", logical.databaseType());

        var binding = repository.listSchemaBindings().stream()
                .filter(item -> item.logicalSchemaUuid().equals(logical.uuid())).findFirst().orElseThrow();
        assertEquals(physical.uuid(), binding.physicalSchemaUuid());
        assertEquals(environment.uuid(), binding.environmentUuid());
        assertTrue(repository.logicalSchemaInUse(logical.uuid()));
        assertTrue(repository.environmentInUse(environment.uuid()));
        assertTrue(repository.physicalSchemaInUse(physical.uuid()));

        var catalog = repository.listConnectionCatalog().stream()
                .filter(item -> item.connection().uuid().equals(connection.uuid())).findFirst().orElseThrow();
        assertEquals(1, catalog.physicalSchemaCount());
        assertEquals(1, catalog.logicalSchemaCount());
        assertEquals(logical.name(), service.listConnectionDependencies(connection.uuid()).getFirst().name());

        var postgres = service.createConnection(new ConnectionInput("PG_OTHER", "Postgres", null, "POSTGRESQL", "JDBC", null,
                "pg.example", 5432, null, null, "akis", null, null, "akis", "pw", null, null, null, null, null, null, null, null));
        var pgSchema = service.createPhysicalSchema(new PhysicalSchemaInput(postgres.uuid(), null, null, null, null,
                "public", null, null, false, null, null, null, null, null, null, null, null));
        assertThrows(ApiException.class, () -> service.updateSchemaBinding(binding.uuid(), logical.uuid(), environment.uuid(), pgSchema.uuid()));

        assertEquals("CONNECTION_IN_USE", assertThrows(ApiException.class, () -> service.deleteConnection(connection.uuid())).code());
        assertThrows(ApiException.class, () -> service.deleteLogicalSchema(logical.uuid()));
        assertThrows(ApiException.class, () -> service.deleteEnvironment(environment.uuid()));
        assertThrows(ApiException.class, () -> service.deletePhysicalSchema(physical.uuid()));

        service.deleteSchemaBinding(binding.uuid());
        service.deleteLogicalSchema(logical.uuid());
        service.deletePhysicalSchema(physical.uuid());
        service.deleteConnection(connection.uuid());
        assertTrue(repository.findConnection(connection.uuid()).isEmpty());
        assertTrue(repository.findLogicalSchema(logical.uuid()).isEmpty());
    }

    @Test
    void contextEditsRenameInPlace() {
        var logical = repository.createLogicalSchema("EDIT_CONTEXT", "Before", null, "ORACLE", null);
        var updated = service.updateLogicalSchema(logical.uuid(), "After", "Description", "PASIF");
        assertEquals("After", updated.name());
        assertEquals("PASIF", updated.status());
        var environment = service.createEnvironment("EDIT_ENV", "Before", null, "DUSUK", true, new ObjectMapper().createObjectNode());
        assertTrue(environment.defaultEnvironment());
        var other = service.createEnvironment("EDIT_ENV_2", "Other", null, "URETIM", true, new ObjectMapper().createObjectNode());
        assertTrue(other.defaultEnvironment());
        assertFalse(repository.findEnvironment(environment.uuid()).orElseThrow().defaultEnvironment());
        var renamed = service.updateEnvironment(environment.uuid(), "After", null, "ORTA", true, null);
        assertEquals("After", renamed.name());
        assertEquals("ORTA", renamed.risk());
        assertTrue(renamed.defaultEnvironment());
        assertFalse(repository.findEnvironment(other.uuid()).orElseThrow().defaultEnvironment());
        service.deleteEnvironment(environment.uuid());
        assertTrue(repository.findEnvironment(environment.uuid()).isEmpty());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

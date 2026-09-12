package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

class CleanTopologyRepositoryIT {

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
        repository.bindCredential(projectId, version.id(), "ENV", "AKIS_TEST_PASSWORD", "KIMLIK");
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

        var stored = repository.listConnectionVersions(projectId, connection.id()).getFirst();
        assertEquals(12000, stored.policy().get("connectTimeoutMs").intValue());
        assertEquals("ETL", stored.policy().get("purpose").stringValue());
        assertEquals("DISABLED", stored.tlsMode());
        assertEquals("AKTIF", repository.listConnections(projectId).getFirst().status());
        assertEquals(binding.uuid(), repository.listSchemaBindings(projectId).getFirst().uuid());
        assertTrue(repository.findPhysicalSchema(projectId, physical.uuid()).isPresent());
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
                2, policy, "ENV", "AKIS_ORACLE_ATOMIC_CREDENTIAL");

        assertEquals(created.connection().id(), created.initialVersion().connectionId());
        assertEquals("oracle.example", created.initialVersion().host());
        assertEquals(1, repository.listConnectionVersions(
                projectId, created.connection().id()).size());
        assertTrue(TopologyService.class.getDeclaredMethod(
                        "createOracleConnectionWithInitialVersion",
                        UUID.class, String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, Integer.class, String.class,
                        int.class, tools.jackson.databind.JsonNode.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

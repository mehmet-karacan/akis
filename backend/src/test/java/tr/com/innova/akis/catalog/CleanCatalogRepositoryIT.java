package tr.com.innova.akis.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class CleanCatalogRepositoryIT {

    private static CatalogRepository repository;
    private static long projectId;
    private static UUID logicalSchemaUuid;

    @BeforeAll
    static void connect() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_catalog_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean catalog test requires its generated database.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        repository = new CatalogRepository(jdbc, new ObjectMapper());
        projectId = jdbc.sql("insert into akis.proje(kod, ad) values ('CATALOG_IT', 'Catalog IT') returning id")
                .query(Long.class).single();
        logicalSchemaUuid = jdbc.sql("""
                insert into akis.mantiksal_sema(proje_id, kod, ad)
                values (:projectId, 'ORDERS', 'Orders') returning uuid
                """).param("projectId", projectId).query(UUID.class).single();
    }

    @Test
    void createsModelHierarchyAndTranslatesCatalogVocabulary() {
        long logicalId = repository.findLogicalSchema(projectId, logicalSchemaUuid).orElseThrow().id();
        var model = repository.createModel(
                projectId, logicalId, UUID.randomUUID(), "ORDER_MODEL", "Order Model", null);
        var submodel = repository.createSubmodel(
                projectId, model.id(), null, UUID.randomUUID(), "MASTER", "Master");
        var object = repository.createDataObject(
                projectId, model.id(), submodel.id(), UUID.randomUUID(), "ORDERS_VIEW",
                "APP.ORDERS_VIEW", "VIEW", null, null, "Orders View");

        assertEquals("AKTIF", repository.listModels(projectId).getFirst().status());
        assertEquals(model.uuid(), repository.listSubmodels(projectId, model.id()).getFirst().modelUuid());
        assertEquals("VIEW", repository.listDataObjects(projectId, model.id()).getFirst().type());
        assertEquals(object.uuid(), repository.findDataObject(projectId, object.uuid()).orElseThrow().uuid());
        var moved = repository.moveDataObject(projectId, model.id(), object.uuid(), null, object.version()).orElseThrow();
        assertEquals(null, moved.submodelUuid());
        assertEquals(object.version() + 1, moved.version());
        assertEquals(object.objectReference(), moved.objectReference());
        assertEquals(object.uuid(), moved.uuid());
        assertEquals(true, repository.moveDataObject(projectId, model.id(), object.uuid(), submodel.id(), object.version()).isEmpty());
        var restored = repository.moveDataObject(projectId, model.id(), object.uuid(), submodel.id(), moved.version()).orElseThrow();
        assertEquals(submodel.uuid(), restored.submodelUuid());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

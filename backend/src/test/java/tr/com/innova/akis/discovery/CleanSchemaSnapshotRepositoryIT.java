package tr.com.innova.akis.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotProvenance;

class CleanSchemaSnapshotRepositoryIT {

    private static SchemaSnapshotService service;
    private static UUID projectUuid;
    private static UUID dataObjectUuid;
    private static UUID physicalSchemaUuid;
    private static UUID connectionVersionUuid;
    private static ObjectMapper mapper;

    @BeforeAll
    static void connect() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_catalog_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean snapshot test requires its generated database.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        mapper = new ObjectMapper();
        service = new SchemaSnapshotService(new JdbcSchemaSnapshotStore(jdbc, mapper), mapper);
        projectUuid = jdbc.sql("insert into akis.proje(kod, ad) values ('SNAPSHOT_IT', 'Snapshot IT') returning uuid")
                .query(UUID.class).single();
        long projectId = jdbc.sql("select id from akis.proje where uuid=:uuid")
                .param("uuid", projectUuid).query(Long.class).single();
        long connectionId = jdbc.sql("""
                insert into akis.baglanti(
                    kod,ad,saglayici_turu,baglanti_modu,surucu_sinifi,sunucu_adi,
                    port,veritabani_adi,kullanici_adi,sifre)
                values ('PG','PostgreSQL','POSTGRESQL','JDBC','org.postgresql.Driver',
                    'localhost',5432,'db','snapshot_user','snapshot-secret') returning id
                """).query(Long.class).single();
        connectionVersionUuid = jdbc.sql("select uuid from akis.baglanti where id=:id")
                .param("id", connectionId).query(UUID.class).single();
        physicalSchemaUuid = jdbc.sql("""
                insert into akis.fiziksel_sema(
                    baglanti_id,kod,ad,saglayici_turu,sema_adi,calisma_sema_adi)
                values (:b,'PUBLIC','Public','POSTGRESQL','public','public') returning uuid
                """).param("b", connectionId).query(UUID.class).single();
        long logicalId = jdbc.sql("insert into akis.mantiksal_sema(kod,ad,saglayici_turu) values ('L','Logical','POSTGRESQL') returning id")
                .query(Long.class).single();
        long modelId = jdbc.sql("insert into akis.model(proje_id,mantiksal_sema_id,kod,ad) values (:p,:l,'M','Model') returning id")
                .param("p", projectId).param("l", logicalId).query(Long.class).single();
        dataObjectUuid = jdbc.sql("""
                insert into akis.veri_nesnesi(proje_id,model_id,kod,ad,nesne_referansi,tur)
                values (:p,:m,'ORDERS','Orders','public.orders','TABLO') returning uuid
                """).param("p", projectId).param("m", modelId).query(UUID.class).single();
    }

    @Test
    void storesOrderedImmutableAndDeduplicatedSnapshot() {
        var properties = mapper.createObjectNode().put("owner", "public");
        var details = mapper.createObjectNode().put("validated", true);
        var columns = List.of(
                new ColumnInput("ID", "NUMBER", "INTEGER", 1, 19, 0, null, null, false, null, "ID"),
                new ColumnInput("NAME", "VARCHAR2", "STRING", 2, null, null, 100L, null, true, null, "Name"));
        var constraints = List.of(new ConstraintInput(
                "PK_ORDERS", "PK", true, 1, details, "Primary key", List.of("ID")));
        OffsetDateTime discoveredAt = OffsetDateTime.parse("2026-09-12T00:00:00Z");

        var first = service.create(projectUuid, dataObjectUuid, physicalSchemaUuid,
                connectionVersionUuid, "PostgreSQL 16", discoveredAt, 1,
                properties, columns, constraints,
                new SnapshotProvenance("POSTGRESQL", "PostgreSQL", "16", "pgJDBC", "42"));
        var repeated = service.create(projectUuid, dataObjectUuid, physicalSchemaUuid,
                connectionVersionUuid, "PostgreSQL 16", discoveredAt.plusHours(1), 1,
                properties, columns, constraints,
                new SnapshotProvenance("POSTGRESQL", "PostgreSQL", "16", "pgJDBC", "42"));

        assertEquals(first.uuid(), repeated.uuid());
        assertEquals(List.of("ID", "NAME"), first.columns().stream().map(c -> c.reference()).toList());
        assertEquals("PK", first.constraints().getFirst().type());
        assertEquals(List.of("ID"), first.constraints().getFirst().columnReferences());
        assertSame(first.getClass(), repeated.getClass());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}

package tr.com.innova.akis.topology;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated test database, never uses the application connection registry. */
@Testcontainers(disabledWithoutDocker=true)
class EnvironmentTransferBudgetIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:18.6-bookworm");

    @Test void budgetUpdateDoesNotReplaceAnUnrelatedPolicyChangedSinceTheSnapshot() {
        var jdbc=jdbc();var mapper=new ObjectMapper();var repository=new TopologyRepository(jdbc,mapper);
        UUID uuid=create(jdbc);
        var stale=(tools.jackson.databind.node.ObjectNode)repository.findEnvironment(uuid).orElseThrow().policy().deepCopy();
        stale.put("transferMemoryBudgetMiB",64);
        jdbc.sql("update akis.ortam set politika=jsonb_set(politika,'{approval}','true') where uuid=:uuid").param("uuid",uuid).update();
        repository.updateEnvironmentMemoryPolicy(uuid,stale);
        var saved=repository.findEnvironment(uuid).orElseThrow().policy();
        assertTrue(saved.path("approval").asBoolean(),"Unrelated live policy must survive a stale budget update");
        assertEquals(64,saved.path("transferMemoryBudgetMiB").asLong());
    }

    @Test void staleMetadataOnlyUpdateCannotUndoAReducedBudget() {
        var jdbc=jdbc();var mapper=new ObjectMapper();var repository=spy(new TopologyRepository(jdbc,mapper));
        UUID uuid=create(jdbc);var stale=repository.findEnvironment(uuid).orElseThrow();
        jdbc.sql("update akis.ortam set politika=jsonb_set(politika,'{transferMemoryBudgetMiB}','64') where uuid=:uuid").param("uuid",uuid).update();
        doReturn(java.util.Optional.of(stale)).doCallRealMethod().when(repository).findEnvironment(uuid);
        var service=new TopologyService(repository,mapper,null,null);
        service.updateEnvironment(uuid,"Renamed",null,null,false,null);
        assertEquals(64,repository.findEnvironment(uuid).orElseThrow().policy().path("transferMemoryBudgetMiB").asLong());
    }

    private static JdbcClient jdbc() {
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())
                .locations("classpath:db/akis").defaultSchema("akis").schemas("akis").load().migrate();
        return JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
    }
    private static UUID create(JdbcClient jdbc) {
        return jdbc.sql("insert into akis.ortam(kod,ad,politika) values(:code,'Memory race','{\"transferMemoryBudgetMiB\":1024,\"approval\":false}'::jsonb) returning uuid")
                .param("code","MEM_"+UUID.randomUUID().toString().replace("-","").toUpperCase(java.util.Locale.ROOT))
                .query(UUID.class).single();
    }
}

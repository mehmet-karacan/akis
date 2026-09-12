package tr.com.innova.akis.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

class CleanScenarioRepositoryIT {
    private static ScenarioService service;
    private static UUID projectUuid;
    private static UUID definitionUuid;
    private static UUID versionUuid;

    @BeforeAll
    static void connect() throws Exception {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_scenario_test_[0-9]+)(?:\\?.*)?$")) throw new IllegalStateException("Generated scenario DB required.");
        var dataSource = new DriverManagerDataSource(url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var mapper = new ObjectMapper();
        var compiler = new ScenarioPlanCompiler(mapper, new DefinitionContentValidator(), new SecretValueSanitizer());
        service = new ScenarioService(new JdbcScenarioStore(jdbc, mapper), compiler);
        projectUuid = jdbc.sql("insert into akis.proje(kod,ad) values ('SCENARIO_IT','Scenario IT') returning uuid").query(UUID.class).single();
        long projectId = jdbc.sql("select id from akis.proje where uuid=:u").param("u", projectUuid).query(Long.class).single();
        long folderId = jdbc.sql("insert into akis.klasor(proje_id,kod,ad) values (:p,'ROOT','Root') returning id").param("p",projectId).query(Long.class).single();
        definitionUuid = jdbc.sql("insert into akis.tanim(proje_id,klasor_id,tur,kod,ad) values (:p,:f,'PROSEDUR','LOAD','Load') returning uuid")
                .param("p",projectId).param("f",folderId).query(UUID.class).single();
        long definitionId = jdbc.sql("select id from akis.tanim where uuid=:u").param("u",definitionUuid).query(Long.class).single();
        var content = mapper.readTree("{\"tasks\":[{\"id\":\"READ\",\"type\":\"SQL\",\"connectionRole\":\"SOURCE\",\"riskClass\":\"READ_ONLY\",\"command\":\"select 1 from dual\"}]}");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(compiler.canonicalize(content).toString().getBytes(StandardCharsets.UTF_8)));
        versionUuid = jdbc.sql("""
                insert into akis.tanim_surumu(proje_id,tanim_id,surum_no,sema_surumu,icerik_ozeti,icerik)
                values (:p,:t,1,1,:h,cast(:c as jsonb)) returning uuid
                """).param("p",projectId).param("t",definitionId).param("h",hash)
                .param("c",content.toString()).query(UUID.class).single();
    }

    @Test
    void compilesOnceAndReturnsImmutableScenario() {
        var first = service.compile(projectUuid, definitionUuid, versionUuid);
        var repeated = service.compile(projectUuid, definitionUuid, versionUuid);
        assertTrue(first.created());
        assertFalse(repeated.created());
        assertEquals(first.scenario().uuid(), repeated.scenario().uuid());
        assertEquals(2, first.scenario().planVersion());
        assertEquals(1, service.list(projectUuid, definitionUuid, versionUuid).size());
    }

    private static String required(String name) {
        String value=System.getenv(name); if(value==null||value.isBlank()) throw new IllegalStateException(name+" is required."); return value;
    }
}

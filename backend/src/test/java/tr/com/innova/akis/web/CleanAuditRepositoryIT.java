package tr.com.innova.akis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class CleanAuditRepositoryIT {
 @Test void appendsImmutableAuditEvent(){
  String url=required("SPRING_DATASOURCE_URL");if(!url.contains("/akis_bundle_test_"))throw new IllegalStateException("Generated DB required.");var jdbc=JdbcClient.create(new DriverManagerDataSource(url,required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD")));var repository=new AuditRepository(jdbc);UUID projectUuid=jdbc.sql("insert into akis.proje(kod,ad) values ('AUDIT_IT','Audit IT') returning uuid").query(UUID.class).single();repository.append(repository.findProjectId(projectUuid).orElseThrow(),projectUuid,"correlation","SISTEM","TEST","BASARILI",new ObjectMapper().createObjectNode().put("safe",true));assertEquals(1,jdbc.sql("select count(*) from akis.denetim_olayi where dis_nesne_uuid=:u").param("u",projectUuid).query(Integer.class).single());
 }
 private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required");return v;}
}

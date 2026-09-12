package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionType;

class CleanProjectBundleRepositoryIT {
 @Test void roundTripsDefinitionMetadataOnCleanSchema(){
  String url=required("SPRING_DATASOURCE_URL");if(!url.matches(".*(/akis_bundle_test_[0-9]+)(?:\\?.*)?$"))throw new IllegalStateException("Generated bundle DB required.");var mapper=new ObjectMapper();var repo=new ProjectBundleRepository(JdbcClient.create(new DriverManagerDataSource(url,required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"))),mapper);
  var project=repo.insertProject(UUID.randomUUID(),"IMPORTED","AKTIF","Imported",null);long folder=repo.insertFolder(project.id(),null,"ROOT","GELISTIRME","AKTIF","Root",null);long definition=repo.insertDefinition(project.id(),folder,DefinitionType.PROCEDURE,"LOAD","AKTIF","Load",null);var content=mapper.createObjectNode();content.putArray("tasks");repo.insertDraft(definition,1,content);repo.insertVersion(definition,new ProjectBundleRepository.VersionRow(definition,1,1,"a".repeat(64),content,"v1",OffsetDateTime.now()));var snapshot=repo.loadSnapshot(project.uuid());
  assertEquals("IMPORTED",snapshot.project().code());assertEquals("GELISTIRME",snapshot.folders().getFirst().type());assertEquals(DefinitionType.PROCEDURE,snapshot.definitions().getFirst().type());assertEquals(1,snapshot.versions().size());
 }
 private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required");return v;}
}

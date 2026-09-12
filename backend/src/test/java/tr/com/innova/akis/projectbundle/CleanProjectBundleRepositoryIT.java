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
  var topology=mapper.createObjectNode();var connections=topology.putArray("connections");var connection=connections.addObject().put("code","SKY").put("name","SKY Oracle").put("provider","ORACLE").putNull("description");var versions=connection.putArray("versions");var version=versions.addObject().put("versionNumber",1).put("mode","JDBC").put("driverClass","oracle.jdbc.OracleDriver").put("host","10.6.86.68").put("port",1907).putNull("serviceName").put("sid","TTBP2").putNull("databaseName").putNull("jndiName").put("tlsMode","DEVRE_DISI").put("connectTimeoutMs",10000).put("readTimeoutMs",60000).put("networkTimeoutMs",60000).put("queryTimeoutSeconds",60).put("purpose","SOURCE");version.putArray("identityBindings").addObject().put("purpose","VERITABANI").put("username","INNOVA_ODI").put("provider","ENV").put("reference","AKIS_SKY_PASSWORD");
  topology.putArray("physicalSchemas").addObject().put("code","SKY_TTBP").put("name","SKY TTBP").put("schemaName","TTBP").put("connectionCode","SKY");topology.putArray("logicalSchemas").addObject().put("code","HAKEDIS").put("name","Hakediş").putNull("description");var environment=topology.putArray("environments").addObject().put("code","DEV").put("name","Development").put("production",false).put("risk","DUSUK").put("policySchemaVersion",1);environment.putObject("policy");topology.putArray("schemaBindings").addObject().put("environmentCode","DEV").put("logicalSchemaCode","HAKEDIS").put("connectionCode","SKY").put("physicalSchemaCode","SKY_TTBP").put("connectionVersion",1);topology.putArray("models").addObject().put("code","HAKEDIS_MODEL").put("name","Hakediş Model").putNull("description").put("logicalSchemaCode","HAKEDIS");topology.putArray("submodels").addObject().put("code","CORE").put("name","Core").putNull("description").put("modelCode","HAKEDIS_MODEL").putNull("parentCode");topology.putArray("dataObjects").addObject().put("code","HAKEDIS_TIPI").put("name","Hakediş Tipi").put("reference","TTBP.HAKEDIS_TIPI").put("type","TABLO").put("modelCode","HAKEDIS_MODEL").put("submodelCode","CORE").putNull("querySchemaVersion").putNull("queryDefinition");repo.importPortableTopology(project.id(),topology);var exportedTopology=repo.loadPortableTopology(project.id());
  assertEquals("IMPORTED",snapshot.project().code());assertEquals("GELISTIRME",snapshot.folders().getFirst().type());assertEquals(DefinitionType.PROCEDURE,snapshot.definitions().getFirst().type());assertEquals(1,snapshot.versions().size());
  assertEquals(1,exportedTopology.get("connections").size());assertEquals("AKIS_SKY_PASSWORD",exportedTopology.get("connections").get(0).get("versions").get(0).get("identityBindings").get(0).get("reference").asString());assertEquals(1,exportedTopology.get("dataObjects").size());
 }
 private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required");return v;}
}

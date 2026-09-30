package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ConflictPolicy;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportRequest;

class CleanProjectBundleRepositoryIT {

    @Test
    void roundTripsDefinitionMetadataAndExportsSanitizedPortableTopology() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_bundle_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Generated bundle DB required.");
        }
        var mapper = new ObjectMapper();
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"));
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/akis")
                .defaultSchema("akis")
                .schemas("akis")
                .createSchemas(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        var jdbc = JdbcClient.create(dataSource);
        var repository = new ProjectBundleRepository(jdbc, mapper);
        long actorId = jdbc.sql("""
                        insert into akis.kullanici(
                            gorunen_ad,kullanici_kodu,ad,durum)
                        values ('Bundle Test','bundle_test','Bundle','PAROLA_BEKLIYOR')
                        returning id
                        """).query(Long.class).single();

        long connectionId = jdbc.sql("""
                        insert into akis.baglanti(
                            kod, ad, saglayici_turu, baglanti_modu, surucu_sinifi,
                            sunucu_adi, port, servis_adi, kullanici_adi, sifre)
                        values ('SOURCE', 'SOURCE Oracle', 'ORACLE', 'JDBC',
                                'oracle.jdbc.OracleDriver', '10.6.86.68', 1907,
                                'UPSTREAM_SCHEMA_2', 'TARGET_SCHEMA', 'encrypted-secret')
                        returning id
                        """).query(Long.class).single();
        long physicalSchemaId = jdbc.sql("""
                        insert into akis.fiziksel_sema(
                            baglanti_id, kod, ad, saglayici_turu,
                            sema_adi, calisma_sema_adi)
                        values (:connectionId, 'SRC_UPSTREAM', 'SOURCE UPSTREAM_SCHEMA', 'ORACLE',
                                'UPSTREAM_SCHEMA', 'UPSTREAM_SCHEMA')
                        returning id
                        """).param("connectionId", connectionId).query(Long.class).single();
        long logicalSchemaId = jdbc.sql("""
                        insert into akis.mantiksal_sema(kod, ad, saglayici_turu)
                        values ('SAMPLE', 'Hakediş', 'ORACLE') returning id
                        """).query(Long.class).single();
        long environmentId = jdbc.sql("""
                        insert into akis.ortam(kod, ad) values ('DEV', 'Development') returning id
                        """).query(Long.class).single();
        jdbc.sql("""
                        insert into akis.sema_eslemesi(
                            ortam_id, mantiksal_sema_id, fiziksel_sema_id, saglayici_turu)
                        values (:environmentId, :logicalSchemaId, :physicalSchemaId, 'ORACLE')
                        """)
                .param("environmentId", environmentId)
                .param("logicalSchemaId", logicalSchemaId)
                .param("physicalSchemaId", physicalSchemaId)
                .update();

        var project = repository.insertProject(
                UUID.randomUUID(), "IMPORTED", "AKTIF", "Imported", null);
        long folder = repository.insertFolder(
                project.id(), null, "ROOT", "AKTIF", "Root", null);
        long childFolder = repository.insertFolder(
                project.id(), folder, "CHILD", "AKTIF", "Child", null);
        long definition = repository.insertDefinition(
                project.id(), childFolder, DefinitionType.SEQUENCE,
                "LOAD", "AKTIF", "Load", null);
        var content = mapper.createObjectNode();
        content.put("implementation", "REPOSITORY");
        content.put("start", 1);
        content.put("increment", 1);
        content.put("cycle", false);
        repository.insertDraft(definition, 1, content);
        repository.insertVersion(definition, new ProjectBundleRepository.VersionRow(
                definition, 1, 1,
                sha256("{\"cycle\":false,\"implementation\":\"REPOSITORY\",\"increment\":1,\"start\":1}"),
                content, "v1", OffsetDateTime.now()));
        long versionId = jdbc.sql("select id from akis.tanim_surumu where tanim_id=:definition and surum_no=1")
                .param("definition", definition).query(Long.class).single();
        String versionHash = jdbc.sql("select icerik_ozeti from akis.tanim_surumu where id=:version")
                .param("version", versionId).query(String.class).single();
        long validationId = jdbc.sql("""
                insert into akis.dogrulama(
                    proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi)
                values (:project,:version,:hash,'GECTI','{}') returning id
                """)
                .param("project", project.id()).param("version", versionId)
                .param("hash", versionHash).query(Long.class).single();
        long scenarioId = jdbc.sql("""
                insert into akis.senaryo(
                    proje_id,tanim_surumu_id,dogrulama_id,surum_no,
                    plan_sema_surumu,plan_ozeti,plan)
                values (:project,:version,:validation,1,2,:hash,'{}') returning id
                """)
                .param("project", project.id()).param("version", versionId)
                .param("validation", validationId).param("hash", "b".repeat(64))
                .query(Long.class).single();
        long publicationId = jdbc.sql("""
                insert into akis.yayin(
                    proje_id,senaryo_id,ortam_id,yayin_no,durum,
                    bagimlilik_ozeti,fiziksel_manifesto)
                values (:project,:scenario,:environment,1,'ONAY_BEKLIYOR','test','{}')
                returning id
                """)
                .param("project", project.id()).param("scenario", scenarioId)
                .param("environment", environmentId).query(Long.class).single();
        jdbc.sql("""
                insert into akis.zamanlama(
                    proje_id,yayin_id,kod,ad,olusturan_kullanici_id,
                    cron_ifadesi,zaman_dilimi,cakisma_politikasi,kacirma_politikasi,
                    yayin_gorunum_kodu)
                values (:project,:publication,'LOAD_DAILY','Daily load',:actor,
                        '0 0 2 * * *','Europe/Istanbul','SKIP','RUN_ONCE','PINNED')
                """)
                .param("project", project.id()).param("publication", publicationId)
                .param("actor", actorId).update();

        var topology = mapper.createObjectNode();
        topology.putArray("connections");
        topology.putArray("physicalSchemas");
        topology.putArray("logicalSchemas");
        topology.putArray("environments");
        topology.putArray("schemaBindings");
        topology.putArray("models").addObject()
                .put("code", "SAMPLE_MODEL")
                .put("name", "Hakediş Model")
                .putNull("description")
                .put("logicalSchemaCode", "SAMPLE")
                .put("reverseEnvironmentCode", "DEV");
        topology.putArray("submodels").addObject()
                .put("code", "CORE")
                .put("name", "Core")
                .putNull("description")
                .put("modelCode", "SAMPLE_MODEL")
                .putNull("parentCode");
        ((tools.jackson.databind.node.ArrayNode) topology.get("submodels")).addObject()
                .put("code", "DETAIL")
                .put("name", "Detail")
                .put("modelCode", "SAMPLE_MODEL")
                .put("parentCode", "CORE");
        topology.putArray("dataObjects").addObject()
                .put("code", "SAMPLE_TABLE")
                .put("name", "Hakediş Tipi")
                .put("reference", "UPSTREAM_SCHEMA.SAMPLE_TABLE")
                .put("type", "TABLO")
                .put("modelCode", "SAMPLE_MODEL")
                .put("submodelCode", "DETAIL")
                .putNull("querySchemaVersion")
                .putNull("queryDefinition");
        repository.importPortableTopology(project.id(), topology);

        var snapshot = repository.loadSnapshot(project.uuid());
        var exportedTopology = repository.loadPortableTopology(project.id());

        assertEquals("IMPORTED", snapshot.project().code());
        assertEquals("ROOT", snapshot.folders().getFirst().code());
        assertEquals(DefinitionType.SEQUENCE, snapshot.definitions().getFirst().type());
        assertEquals(1, snapshot.versions().size());
        assertEquals(1, exportedTopology.get("connections").size());
        assertEquals("SOURCE", exportedTopology.get("connections").get(0).get("code").asString());
        assertFalse(exportedTopology.get("connections").get(0).has("username"));
        assertFalse(exportedTopology.get("connections").get(0).has("password"));
        assertEquals(1, exportedTopology.get("physicalSchemas").size());
        assertEquals(1, exportedTopology.get("logicalSchemas").size());
        assertEquals(1, exportedTopology.get("environments").size());
        UUID sourceEnvironmentUuid = jdbc.sql("select uuid from akis.ortam where id = :id")
                .param("id", environmentId).query(UUID.class).single();
        assertEquals(sourceEnvironmentUuid.toString(), exportedTopology.get("environments")
                .get(0).get("sourceUuid").asString());
        assertEquals(1, exportedTopology.get("schemaBindings").size());
        assertEquals(1, exportedTopology.get("models").size());
        assertEquals(2, exportedTopology.get("submodels").size());
        assertEquals(1, exportedTopology.get("dataObjects").size());
        assertEquals("IMPORTED_UNVERIFIED", exportedTopology.get("dataObjects").get(0)
                .get("metadataVerification").asString());

        long sourceDataObjectId = jdbc.sql("""
                select id from akis.veri_nesnesi
                 where proje_id = :project and kod = 'SAMPLE_TABLE'
                """).param("project", project.id()).query(Long.class).single();
        jdbc.sql("""
                insert into akis.sema_goruntusu(
                    proje_id, veri_nesnesi_id, fiziksel_sema_id, baglanti_id,
                    parmak_izi, motor_surumu, kesif_zamani, ozellik_sema_surumu, ozellik)
                values (:project, :dataObject, :schema, :connection,
                        :fingerprint, 'TEST', current_timestamp, 1, '{}'::jsonb)
                """)
                .param("project", project.id()).param("dataObject", sourceDataObjectId)
                .param("schema", physicalSchemaId).param("connection", connectionId)
                .param("fingerprint", "a".repeat(64)).update();

        var service = new ProjectBundleService(
                repository, new DefinitionContentValidator(),
                new SecretValueSanitizer(), mapper,
                new PendingRecipeImportWriter(jdbc, new PendingRecipeRepository(jdbc)));
        var bundle = service.exportBundle(project.uuid());
        assertTrue(service.validate(bundle).valid());
        assertEquals(1, bundle.publications().size());
        assertEquals(DefinitionType.SEQUENCE, bundle.publications().getFirst().definitionType());
        assertEquals("LOAD", bundle.publications().getFirst().definitionCode());
        assertEquals(1, bundle.publications().getFirst().definitionVersionNumber());
        assertEquals("DEV", bundle.publications().getFirst().environmentCode());
        assertEquals(1, bundle.schedules().size());
        assertEquals("LOAD_DAILY", bundle.schedules().getFirst().code());
        assertEquals("0 0 2 * * *", bundle.schedules().getFirst().cronExpression());
        assertEquals("PINNED", bundle.schedules().getFirst().publicationPolicy());
        assertEquals(bundle.publications().getFirst(), bundle.schedules().getFirst().publicationSelection());
        var legacyImportError = assertThrows(ProjectBundleException.class,
                () -> service.importBundle(bundle, ConflictPolicy.RENAME, false));
        assertEquals("TARGET_IMPORT_REQUIRED", legacyImportError.code());
        assertEquals(0L, jdbc.sql("select count(*) from akis.proje where kod = 'IMPORTED_IMPORT_1'")
                .query(Long.class).single());

        long targetConnectionId = jdbc.sql("""
                insert into akis.baglanti(
                    kod, ad, saglayici_turu, baglanti_modu, surucu_sinifi,
                    sunucu_adi, port, servis_adi, kullanici_adi, sifre)
                values ('TARGET_SOURCE', 'Target Oracle', 'ORACLE', 'JDBC',
                        'oracle.jdbc.OracleDriver', '127.0.0.1', 1521,
                        'TARGET', 'TEST', 'test-only') returning id
                """).query(Long.class).single();
        long targetPhysicalSchemaId = jdbc.sql("""
                insert into akis.fiziksel_sema(
                    baglanti_id, kod, ad, saglayici_turu, sema_adi, calisma_sema_adi)
                values (:connection, 'TARGET_DB', 'Target UPSTREAM_SCHEMA', 'ORACLE',
                        'UPSTREAM_TEST', 'UPSTREAM_TEST') returning id
                """).param("connection", targetConnectionId).query(Long.class).single();
        UUID targetConnectionUuid = jdbc.sql("select uuid from akis.baglanti where id=:id")
                .param("id", targetConnectionId).query(UUID.class).single();
        UUID targetPhysicalSchemaUuid = jdbc.sql("select uuid from akis.fiziksel_sema where id=:id")
                .param("id", targetPhysicalSchemaId).query(UUID.class).single();
        UUID logicalSchemaUuid = jdbc.sql("select uuid from akis.mantiksal_sema where id=:id")
                .param("id", logicalSchemaId).query(UUID.class).single();
        UUID environmentUuid = jdbc.sql("select uuid from akis.ortam where id=:id")
                .param("id", environmentId).query(UUID.class).single();
        List<GlobalBinding> bindings = List.of(
                new GlobalBinding(GlobalResourceType.CONNECTION, "SOURCE",
                        GlobalBindingMode.BIND_EXISTING, targetConnectionUuid, null, null),
                // Physical-schema dependency keys are connection-qualified so
                // two connections may safely expose the same schema code.
                new GlobalBinding(GlobalResourceType.PHYSICAL_SCHEMA, "SOURCE::SRC_UPSTREAM",
                        GlobalBindingMode.BIND_EXISTING, targetPhysicalSchemaUuid, null, null),
                new GlobalBinding(GlobalResourceType.LOGICAL_SCHEMA, "SAMPLE",
                        GlobalBindingMode.BIND_EXISTING, logicalSchemaUuid, null, null),
                new GlobalBinding(GlobalResourceType.ENVIRONMENT, "DEV",
                        GlobalBindingMode.BIND_EXISTING, environmentUuid, null, null));
        var emptyTarget = repository.insertProject(
                UUID.randomUUID(), "TARGET", "AKTIF", "Target", null);
        var plan = service.planTargetImport(emptyTarget.uuid(), bundle, bindings);
        assertTrue(plan.valid(), () -> plan.issues().toString());
        var targetResult = service.importIntoTarget(
                emptyTarget.uuid(),
                new TargetImportRequest(
                        bundle, bindings, plan.planDigest(), plan.targetVersion()),
                "clean-target-import-0001", actorId);
        var replay = service.importIntoTarget(
                emptyTarget.uuid(),
                new TargetImportRequest(
                        bundle, bindings, plan.planDigest(), plan.targetVersion()),
                "clean-target-import-0001", actorId);
        assertTrue(targetResult.imported());
        assertFalse(targetResult.replayed());
        assertTrue(replay.replayed());
        assertEquals(2, targetResult.targetVersion());
        assertEquals(1L, jdbc.sql("""
                select count(*) from akis.ithal_yayin_tarifi r
                join akis.tanim_surumu v on v.id = r.tanim_surumu_id
                join akis.tanim t on t.id = v.tanim_id
                where r.proje_id = :project and t.kod = 'LOAD'
                  and v.surum_no = 1 and r.ortam_id = :environment
                  and r.olusturan_kullanici_id = :actor
                """)
                .param("project", emptyTarget.id())
                .param("environment", environmentId)
                .param("actor", actorId)
                .query(Long.class).single());
        assertEquals(1L, jdbc.sql("""
                select count(*) from akis.ithal_zamanlama_tarifi s
                join akis.ithal_yayin_tarifi r on r.id = s.yayin_tarifi_id
                where s.proje_id = :project and r.proje_id = :project
                  and s.kod = 'LOAD_DAILY' and s.yayin_gorunum_kodu = 'PINNED'
                  and s.cron_ifadesi = '0 0 2 * * *'
                """).param("project", emptyTarget.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.yayin where proje_id = :project")
                .param("project", emptyTarget.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.zamanlama where proje_id = :project")
                .param("project", emptyTarget.id()).query(Long.class).single());
        var rollbackTarget = repository.insertProject(
                UUID.randomUUID(), "ROLLBACK_TARGET", "AKTIF", "Rollback target", null);
        var rollbackPlan = service.planTargetImport(rollbackTarget.uuid(), bundle, bindings);
        assertTrue(rollbackPlan.valid(), () -> rollbackPlan.issues().toString());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(ignored ->
                service.importIntoTarget(
                        rollbackTarget.uuid(),
                        new TargetImportRequest(bundle, bindings,
                                rollbackPlan.planDigest(), rollbackPlan.targetVersion()),
                        "rollback-target-import-0001", Long.MAX_VALUE)));
        assertEquals(0L, jdbc.sql("select count(*) from akis.tanim where proje_id = :project")
                .param("project", rollbackTarget.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.ithal_yayin_tarifi where proje_id = :project")
                .param("project", rollbackTarget.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.ithal_zamanlama_tarifi where proje_id = :project")
                .param("project", rollbackTarget.id()).query(Long.class).single());
        assertEquals(rollbackPlan.targetVersion(), repository.findProject(rollbackTarget.uuid())
                .orElseThrow().version());
        var targetSnapshot = repository.loadSnapshot(emptyTarget.uuid());
        assertNotEquals(snapshot.project().uuid(), targetSnapshot.project().uuid());
        assertEquals(2, targetSnapshot.folders().size());
        var targetRoot = targetSnapshot.folders().stream()
                .filter(item -> "ROOT".equals(item.code())).findFirst().orElseThrow();
        var targetChild = targetSnapshot.folders().stream()
                .filter(item -> "CHILD".equals(item.code())).findFirst().orElseThrow();
        assertEquals(targetRoot.id(), targetChild.parentId());
        assertNotEquals(folder, targetRoot.id());
        assertNotEquals(childFolder, targetChild.id());
        assertNotEquals(snapshot.definitions().getFirst().uuid(), targetSnapshot.definitions().getFirst().uuid());
        assertNotEquals(ownedUuid(jdbc, "model", project.id(), "SAMPLE_MODEL"),
                ownedUuid(jdbc, "model", emptyTarget.id(), "SAMPLE_MODEL"));
        assertNotEquals(ownedUuid(jdbc, "alt_model", project.id(), "CORE"),
                ownedUuid(jdbc, "alt_model", emptyTarget.id(), "CORE"));
        assertNotEquals(ownedUuid(jdbc, "alt_model", project.id(), "DETAIL"),
                ownedUuid(jdbc, "alt_model", emptyTarget.id(), "DETAIL"));
        assertNotEquals(ownedUuid(jdbc, "veri_nesnesi", project.id(), "SAMPLE_TABLE"),
                ownedUuid(jdbc, "veri_nesnesi", emptyTarget.id(), "SAMPLE_TABLE"));
        assertEquals("IMPORTED", repository.loadSnapshot(project.uuid()).project().code());
        assertEquals(1, repository.loadSnapshot(project.uuid()).definitions().size());
        assertEquals(1, targetSnapshot.definitions().size());
        var targetTopology = repository.loadPortableTopology(emptyTarget.id());
        assertEquals(1, targetTopology.get("models").size());
        assertEquals("SAMPLE_MODEL", targetTopology.get("models").get(0).get("code").asString());
        assertEquals(2, targetTopology.get("submodels").size());
        assertEquals("CORE", targetTopology.path("submodels").get(1).path("parentCode").asString());
        assertEquals(1, targetTopology.get("dataObjects").size());
        assertEquals("SAMPLE_TABLE", targetTopology.get("dataObjects").get(0).get("code").asString());
        assertEquals(targetPhysicalSchemaId, jdbc.sql("""
                select sg.fiziksel_sema_id from akis.sema_goruntusu sg
                  join akis.veri_nesnesi vn on vn.id = sg.veri_nesnesi_id
                 where vn.proje_id = :project and vn.kod = 'SAMPLE_TABLE'
                """).param("project", emptyTarget.id()).query(Long.class).single());
        assertEquals(targetConnectionId, jdbc.sql("""
                select sg.baglanti_id from akis.sema_goruntusu sg
                  join akis.veri_nesnesi vn on vn.id = sg.veri_nesnesi_id
                 where vn.proje_id = :project and vn.kod = 'SAMPLE_TABLE'
                """).param("project", emptyTarget.id()).query(Long.class).single());
        assertEquals(logicalSchemaId, jdbc.sql("""
                        select mantiksal_sema_id from akis.model
                         where proje_id=:projectId and kod='SAMPLE_MODEL'
                        """).param("projectId", emptyTarget.id()).query(Long.class).single());
        assertEquals(environmentId, jdbc.sql("""
                        select tersine_muhendislik_ortam_id from akis.model
                         where proje_id=:projectId and kod='SAMPLE_MODEL'
                        """).param("projectId", emptyTarget.id()).query(Long.class).single());
        assertEquals(1, repository.loadSnapshot(project.uuid()).definitions().size());
        assertEquals(1, repository.loadPortableTopology(project.id()).get("dataObjects").size());
        assertEquals(1, jdbc.sql("""
                select count(*) from akis.ithal_katalog_metadata m
                join akis.proje p on p.id=m.proje_id where p.uuid=:uuid
                """).param("uuid", emptyTarget.uuid()).query(Integer.class).single());

        long procedureLogicalId = jdbc.sql("""
                insert into akis.mantiksal_sema(kod, ad, saglayici_turu)
                values ('PROC_ONLY', 'Procedure only', 'ORACLE') returning id
                """).query(Long.class).single();
        long procedureEnvironmentId = jdbc.sql("""
                insert into akis.ortam(kod, ad)
                values ('PROC_ENV', 'Procedure environment') returning id
                """).query(Long.class).single();
        jdbc.sql("""
                insert into akis.sema_eslemesi(
                    ortam_id, mantiksal_sema_id, fiziksel_sema_id, saglayici_turu)
                values (:environment, :logical, :physical, 'ORACLE')
                """)
                .param("environment", procedureEnvironmentId)
                .param("logical", procedureLogicalId)
                .param("physical", physicalSchemaId)
                .update();
        UUID procedureLogicalUuid = jdbc.sql("select uuid from akis.mantiksal_sema where id=:id")
                .param("id", procedureLogicalId).query(UUID.class).single();
        UUID procedureEnvironmentUuid = jdbc.sql("select uuid from akis.ortam where id=:id")
                .param("id", procedureEnvironmentId).query(UUID.class).single();
        long procedureId = repository.insertDefinition(
                project.id(), folder, DefinitionType.PROCEDURE,
                "PROC_ONLY", "AKTIF", "Procedure only", null);
        var procedureContent = mapper.createObjectNode();
        procedureContent.putArray("tasks").addObject()
                .put("id", "PROC_READ")
                .put("type", "SQL")
                .put("connectionRole", "SOURCE")
                .put("riskClass", "READ_ONLY")
                .put("command", "SELECT 1")
                .put("logicalSchemaUuid", procedureLogicalUuid.toString())
                .put("environmentUuid", procedureEnvironmentUuid.toString());
        repository.insertDraft(procedureId, 1, procedureContent);

        var procedureTopology = repository.loadPortableTopology(project.id());
        assertTrue(containsCode(procedureTopology.path("logicalSchemas"), "PROC_ONLY"));
        assertTrue(containsCode(procedureTopology.path("environments"), "PROC_ENV"));
        assertTrue(containsCode(procedureTopology.path("physicalSchemas"), "SRC_UPSTREAM"));
        assertEquals(2, procedureTopology.path("schemaBindings").size());

        var procedureBundle = service.exportBundle(project.uuid());
        assertTrue(service.validate(procedureBundle).valid());
        var procedureBindings = new java.util.ArrayList<>(bindings);
        procedureBindings.add(new GlobalBinding(
                GlobalResourceType.LOGICAL_SCHEMA, "PROC_ONLY",
                GlobalBindingMode.BIND_EXISTING, procedureLogicalUuid, null, null));
        procedureBindings.add(new GlobalBinding(
                GlobalResourceType.ENVIRONMENT, "PROC_ENV",
                GlobalBindingMode.BIND_EXISTING, procedureEnvironmentUuid, null, null));
        var procedureTarget = repository.insertProject(
                UUID.randomUUID(), "TARGET_PROCEDURE", "AKTIF", "Target Procedure", null);
        var procedurePlan = service.planTargetImport(
                procedureTarget.uuid(), procedureBundle, procedureBindings);
        assertTrue(procedurePlan.valid(), () -> procedurePlan.issues().toString());
        var procedureResult = service.importIntoTarget(
                procedureTarget.uuid(),
                new TargetImportRequest(procedureBundle, procedureBindings,
                        procedurePlan.planDigest(), procedurePlan.targetVersion()),
                "clean-procedure-import-0001", actorId);
        assertTrue(procedureResult.imported());
        String importedProcedureContent = jdbc.sql("""
                select tt.icerik::text from akis.tanim_taslagi tt
                join akis.tanim t on t.id=tt.tanim_id
                where t.proje_id=:projectId and t.kod='PROC_ONLY'
                """).param("projectId", procedureTarget.id()).query(String.class).single();
        var importedProcedureTask = mapper.readTree(importedProcedureContent)
                .path("tasks").get(0);
        assertEquals(procedureLogicalUuid.toString(),
                importedProcedureTask.path("logicalSchemaUuid").asString());
        assertEquals(procedureEnvironmentUuid.toString(),
                importedProcedureTask.path("environmentUuid").asString());

        long variableLogicalId = jdbc.sql("""
                insert into akis.mantiksal_sema(kod, ad, saglayici_turu)
                values ('VAR_ONLY', 'Variable only', 'ORACLE') returning id
                """).query(Long.class).single();
        long variableEnvironmentId = jdbc.sql("""
                insert into akis.ortam(kod, ad)
                values ('VAR_ENV', 'Variable environment') returning id
                """).query(Long.class).single();
        jdbc.sql("""
                insert into akis.sema_eslemesi(
                    ortam_id, mantiksal_sema_id, fiziksel_sema_id, saglayici_turu)
                values (:environment, :logical, :physical, 'ORACLE')
                """)
                .param("environment", variableEnvironmentId)
                .param("logical", variableLogicalId)
                .param("physical", physicalSchemaId)
                .update();
        UUID variableLogicalUuid = jdbc.sql("select uuid from akis.mantiksal_sema where id=:id")
                .param("id", variableLogicalId).query(UUID.class).single();
        long variableId = repository.insertDefinition(
                project.id(), folder, DefinitionType.VARIABLE,
                "VAR_ONLY", "AKTIF", "Variable only", null);
        var variableContent = mapper.createObjectNode();
        variableContent.put("dataType", "STRING");
        variableContent.put("scope", "PROJECT");
        variableContent.put("historyMode", "NONE");
        variableContent.put("valueSource", "REFRESH_QUERY");
        variableContent.put("logicalSchemaUuid", variableLogicalUuid.toString());
        variableContent.put("query", "SELECT 'x' FROM DUAL");
        repository.insertDraft(variableId, 1, variableContent);
        var variableTopology = repository.loadPortableTopology(project.id());
        assertTrue(containsCode(variableTopology.path("logicalSchemas"), "VAR_ONLY"));
        assertTrue(containsCode(variableTopology.path("environments"), "VAR_ENV"));
        assertEquals(3, variableTopology.path("schemaBindings").size());

        UUID sourceProcedureUuid = repository.findDefinitionUuid(
                project.id(), DefinitionType.PROCEDURE, "PROC_ONLY");
        long childPackageId = repository.insertDefinition(
                project.id(), childFolder, DefinitionType.PACKAGE,
                "CHILD_PACKAGE", "AKTIF", "Child package", null);
        UUID sourceChildPackageUuid = repository.findDefinitionUuid(
                project.id(), DefinitionType.PACKAGE, "CHILD_PACKAGE");
        var childPackageContent = mapper.createObjectNode();
        childPackageContent.put("firstStepId", "CALL_PROCEDURE");
        childPackageContent.putArray("transitions");
        childPackageContent.putArray("steps").addObject()
                .put("id", "CALL_PROCEDURE")
                .put("type", "PROCEDURE")
                .put("definitionUuid", sourceProcedureUuid.toString());
        repository.insertDraft(childPackageId, 1, childPackageContent);
        long parentPackageId = repository.insertDefinition(
                project.id(), folder, DefinitionType.PACKAGE,
                "ROOT_PACKAGE", "AKTIF", "Root package", null);
        var parentPackageContent = mapper.createObjectNode();
        parentPackageContent.put("firstStepId", "CALL_CHILD");
        parentPackageContent.putArray("transitions");
        parentPackageContent.putArray("steps").addObject()
                .put("id", "CALL_CHILD")
                .put("type", "PACKAGE")
                .put("definitionUuid", sourceChildPackageUuid.toString());
        repository.insertDraft(parentPackageId, 1, parentPackageContent);

        var nestedBundle = service.exportBundle(project.uuid());
        assertTrue(service.validate(nestedBundle).valid());
        UUID variableEnvironmentUuid = jdbc.sql("select uuid from akis.ortam where id=:id")
                .param("id", variableEnvironmentId).query(UUID.class).single();
        var nestedBindings = new java.util.ArrayList<>(procedureBindings);
        nestedBindings.add(new GlobalBinding(
                GlobalResourceType.LOGICAL_SCHEMA, "VAR_ONLY",
                GlobalBindingMode.BIND_EXISTING, variableLogicalUuid, null, null));
        nestedBindings.add(new GlobalBinding(
                GlobalResourceType.ENVIRONMENT, "VAR_ENV",
                GlobalBindingMode.BIND_EXISTING, variableEnvironmentUuid, null, null));
        var nestedTarget = repository.insertProject(
                UUID.randomUUID(), "TARGET_NESTED", "AKTIF", "Nested Target", null);
        var nestedPlan = service.planTargetImport(nestedTarget.uuid(), nestedBundle, nestedBindings);
        assertTrue(nestedPlan.valid(), () -> nestedPlan.issues().toString());
        assertTrue(service.importIntoTarget(
                nestedTarget.uuid(),
                new TargetImportRequest(nestedBundle, nestedBindings,
                        nestedPlan.planDigest(), nestedPlan.targetVersion()),
                "clean-nested-import-0001", actorId).imported());
        UUID targetProcedureUuid = repository.findDefinitionUuid(
                nestedTarget.id(), DefinitionType.PROCEDURE, "PROC_ONLY");
        UUID targetChildPackageUuid = repository.findDefinitionUuid(
                nestedTarget.id(), DefinitionType.PACKAGE, "CHILD_PACKAGE");
        assertNotEquals(sourceProcedureUuid, targetProcedureUuid);
        assertNotEquals(sourceChildPackageUuid, targetChildPackageUuid);
        assertEquals(targetProcedureUuid.toString(), draftStepDefinitionUuid(
                jdbc, mapper, nestedTarget.id(), "CHILD_PACKAGE"));
        assertEquals(targetChildPackageUuid.toString(), draftStepDefinitionUuid(
                jdbc, mapper, nestedTarget.id(), "ROOT_PACKAGE"));
    }

    private static String draftStepDefinitionUuid(
            JdbcClient jdbc, ObjectMapper mapper, long projectId, String code) {
        String content = jdbc.sql("""
                select tt.icerik::text from akis.tanim_taslagi tt
                join akis.tanim t on t.id=tt.tanim_id
                where t.proje_id=:projectId and t.kod=:code
                """)
                .param("projectId", projectId)
                .param("code", code)
                .query(String.class)
                .single();
        return mapper.readTree(content).path("steps").get(0)
                .path("definitionUuid").asString();
    }

    private static boolean containsCode(tools.jackson.databind.JsonNode array, String code) {
        for (var value : array) {
            if (code.equals(value.path("code").asString())) return true;
        }
        return false;
    }

    private static UUID ownedUuid(
            JdbcClient jdbc, String table, long projectId, String code) {
        if (!List.of("model", "alt_model", "veri_nesnesi").contains(table)) {
            throw new IllegalArgumentException("Unsupported project-owned table");
        }
        return jdbc.sql("select uuid from akis." + table
                        + " where proje_id=:projectId and kod=:code")
                .param("projectId", projectId)
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " required");
        }
        return value;
    }
}

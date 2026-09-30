package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.scenario.ScenarioAcceptanceBridge;
import tr.com.innova.akis.publication.PublicationAcceptanceBridge;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportPlan;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportRequest;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportResult;

/**
 * Gerçek PostgreSQL üzerinden bundle metadata round-trip kabul testi:
 * kaynak proje oluştur → export et → doğrula → boş hedef projeye plan/apply yap
 * → ithal tanımı derle ve yayınla. Kontrollü çalışma bu fixture'ın kapsamı dışındadır.
 * Kanıt: her proje-owned kimlik yenilenir; JSON referanslar hedef bağlama göre remap edilir;
 * global kaynaklar değişmez; kaynak proje dokunulmaz.
 */
class ProjectBundleRoundTripIT {

    @Test
    void exportsBundleAndImportsIntoEmptyTargetWithRemappedReferences() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        Assumptions.assumeTrue(url != null
                && url.matches(".*(/akis_(?:bundle|schedule)_test_[0-9]+(?:_roundtrip)?)(?:\\?.*)?$"),
                "Generated isolated DB required.");

        var mapper = new ObjectMapper();
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
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
        var pendingRecipes = new PendingRecipeRepository(jdbc);
        var service = new ProjectBundleService(
                repository,
                new DefinitionContentValidator(),
                new SecretValueSanitizer(),
                mapper,
                new PendingRecipeImportWriter(jdbc, pendingRecipes));

        long actorId = insertActor(jdbc);

        // Global kaynaklar: source projede kullanılanlar ve hedefte bind edilecek farklı kayıtlar.
        UUID sourceLogicalSchemaUuid = insertLogicalSchema(jdbc, "SOURCE_LS", "Source LS", "ORACLE");
        UUID targetLogicalSchemaUuid = insertLogicalSchema(jdbc, "TARGET_LS", "Target LS", "ORACLE");
        UUID sourceEnvironmentUuid = insertEnvironment(jdbc, "SOURCE_ENV", "Source Environment");
        UUID targetEnvironmentUuid = insertEnvironment(jdbc, "TARGET_ENV", "Target Environment");

        // Kaynak proje
        var sourceProject = repository.insertProject(
                UUID.randomUUID(), "SOURCE_PROJECT", "AKTIF", "Source Project", null);
        long rootFolder = repository.insertFolder(
                sourceProject.id(), null, "ROOT", "AKTIF", "Root", null);
        long childFolder = repository.insertFolder(
                sourceProject.id(), rootFolder, "CHILD", "AKTIF", "Child", null);

        // Remap kanıtı için bir hedef PROCEDURE tanımı: task içinde source LS/ENV referansları var.
        long procedureDefinition = repository.insertDefinition(
                sourceProject.id(), childFolder, DefinitionType.PROCEDURE,
                "RUN_LOAD", "AKTIF", "Run load", null);
        var procedureContent = mapper.createObjectNode();
        var task = procedureContent.putArray("tasks").addObject();
        task.put("id", "T1");
        task.put("type", "SQL");
        task.put("connectionRole", "TARGET");
        task.put("riskClass", "DML");
        task.put("command", "INSERT INTO target_table (id) VALUES (:id)");
        task.put("logicalSchemaUuid", sourceLogicalSchemaUuid.toString());
        task.put("environmentUuid", sourceEnvironmentUuid.toString());
        task.put("requiresApproval", true);

        // PACKAGE tanımı: adım olarak LOAD değişkenine definitionUuid ile bağlanır.
        long variableDefinition = repository.insertDefinition(
                sourceProject.id(), childFolder, DefinitionType.VARIABLE,
                "LOAD", "AKTIF", "Load variable", null);
        long rkmDefinition = repository.insertDefinition(
                sourceProject.id(), childFolder, DefinitionType.KNOWLEDGE_MODULE,
                "RKM_SAMPLE", "AKTIF", "Hakediş reverse module", null);
        var rkmContent = mapper.createObjectNode();
        rkmContent.put("kmType", "RKM");
        rkmContent.putArray("tasks");
        rkmContent.putArray("options");
        var variableContent = mapper.createObjectNode();
        variableContent.put("dataType", "STRING");
        variableContent.put("scope", "PROJECT");
        variableContent.put("historyMode", "NONE");
        variableContent.put("valueSource", "DEFAULT");
        variableContent.put("defaultValue", "0");

        long packageDefinition = repository.insertDefinition(
                sourceProject.id(), rootFolder, DefinitionType.PACKAGE,
                "MAIN_PACKAGE", "AKTIF", "Main package", null);
        long childPackageDefinition = repository.insertDefinition(
                sourceProject.id(), childFolder, DefinitionType.PACKAGE,
                "CHILD_PACKAGE", "AKTIF", "Child package", null);
        var packageContent = mapper.createObjectNode();
        var step = packageContent.putArray("steps").addObject();
        step.put("id", "S1");
        step.put("type", "VARIABLE_SET");
        step.put("definitionUuid", UUID.randomUUID().toString()); // placeholder, version sonrası düzeltilir
        packageContent.putArray("transitions");
        packageContent.put("firstStepId", "S1");
        var childPackageContent = mapper.createObjectNode();
        var childStep = childPackageContent.putArray("steps").addObject();
        childStep.put("id", "C1");
        childStep.put("type", "PROCEDURE");
        childPackageContent.putArray("transitions");
        childPackageContent.put("firstStepId", "C1");

        repository.insertDraft(variableDefinition, 1, variableContent);
        repository.insertVersion(variableDefinition, new ProjectBundleRepository.VersionRow(
                variableDefinition, 1, 1,
                service.sha256(service.canonical(variableContent)), variableContent, "v1", OffsetDateTime.now()));
        repository.insertDraft(rkmDefinition, 1, rkmContent);
        repository.insertVersion(rkmDefinition, new ProjectBundleRepository.VersionRow(
                rkmDefinition, 1, 1,
                service.sha256(service.canonical(rkmContent)), rkmContent, "v1", OffsetDateTime.now()));

        UUID sourceVariableDefinitionUuid = repository.findDefinitionUuid(
                sourceProject.id(), DefinitionType.VARIABLE, "LOAD");
        // Package adımındaki definitionUuid'yi gerçek source variable UUID'sine ayarla.
        step.put("definitionUuid", sourceVariableDefinitionUuid.toString());
        UUID sourceProcedureForChildUuid = repository.findDefinitionUuid(
                sourceProject.id(), DefinitionType.PROCEDURE, "RUN_LOAD");
        childStep.put("definitionUuid", sourceProcedureForChildUuid.toString());
        UUID sourceChildPackageUuid = repository.findDefinitionUuid(
                sourceProject.id(), DefinitionType.PACKAGE, "CHILD_PACKAGE");
        var nestedStep = ((tools.jackson.databind.node.ArrayNode) packageContent.get("steps"))
                .addObject();
        nestedStep.put("id", "S2");
        nestedStep.put("type", "PACKAGE");
        nestedStep.put("definitionUuid", sourceChildPackageUuid.toString());
        packageContent.withArray("transitions").addObject()
                .put("fromStepId", "S1").put("toStepId", "S2").put("outcome", "ALWAYS");

        repository.insertDraft(procedureDefinition, 1, procedureContent);
        repository.insertVersion(procedureDefinition, new ProjectBundleRepository.VersionRow(
                procedureDefinition, 1, 1,
                service.sha256(service.canonical(procedureContent)), procedureContent, "v1", OffsetDateTime.now()));

        repository.insertDraft(packageDefinition, 1, packageContent);
        repository.insertVersion(packageDefinition, new ProjectBundleRepository.VersionRow(
                packageDefinition, 1, 1,
                service.sha256(service.canonical(packageContent)), packageContent, "v1", OffsetDateTime.now()));
        repository.insertDraft(childPackageDefinition, 1, childPackageContent);
        repository.insertVersion(childPackageDefinition, new ProjectBundleRepository.VersionRow(
                childPackageDefinition, 1, 1,
                service.sha256(service.canonical(childPackageContent)),
                childPackageContent, "v1", OffsetDateTime.now()));

        long procedureVersionId = jdbc.sql(
                        "select id from akis.tanim_surumu where tanim_id = :definition and surum_no = 1")
                .param("definition", procedureDefinition).query(Long.class).single();
        long sourceEnvironmentId = jdbc.sql("select id from akis.ortam where uuid = :uuid")
                .param("uuid", sourceEnvironmentUuid).query(Long.class).single();
        String procedureHash = service.sha256(service.canonical(procedureContent));
        long validationId = jdbc.sql("""
                        insert into akis.dogrulama(proje_id,tanim_surumu_id,icerik_ozeti,sonuc,sonuc_ayrintisi)
                        values (:project,:version,:hash,'GECTI','{}') returning id
                        """)
                .param("project", sourceProject.id()).param("version", procedureVersionId)
                .param("hash", procedureHash).query(Long.class).single();
        long scenarioId = jdbc.sql("""
                        insert into akis.senaryo(proje_id,tanim_surumu_id,dogrulama_id,surum_no,plan_sema_surumu,plan_ozeti,plan)
                        values (:project,:version,:validation,1,2,:hash,'{}') returning id
                        """)
                .param("project", sourceProject.id()).param("version", procedureVersionId)
                .param("validation", validationId).param("hash", "a".repeat(64))
                .query(Long.class).single();
        long sourcePublicationId = jdbc.sql("""
                        insert into akis.yayin(proje_id,senaryo_id,ortam_id,yayin_no,durum,bagimlilik_ozeti,fiziksel_manifesto,etkinlestirilme_zamani)
                        values (:project,:scenario,:environment,1,'AKTIF','test fixture',cast(:manifest as jsonb),current_timestamp)
                        returning id
                        """)
                .param("project", sourceProject.id()).param("scenario", scenarioId)
                .param("environment", sourceEnvironmentId)
                .param("manifest", "{\"releaseHash\":\"" + "b".repeat(64) + "\"}")
                .query(Long.class).single();
        jdbc.sql("""
                        insert into akis.zamanlama(proje_id,yayin_id,kod,ad,olusturan_kullanici_id,
                            cron_ifadesi,zaman_dilimi,cakisma_politikasi,kacirma_politikasi,
                            yayin_gorunum_kodu,durum_kodu,sonraki_tetikleme_zamani,
                            baslangic_zamani,bitis_zamani)
                        values (:project,:publication,'RUN_LOAD_DAILY','Run load daily',:actor,
                            '0 0 2 * * *','Europe/Istanbul','SKIP','RUN_ONCE',
                            'PINNED','AKTIF',current_timestamp + interval '3 hours',
                            current_timestamp - interval '1 day',current_timestamp + interval '3 days')
                        """)
                .param("project", sourceProject.id()).param("publication", sourcePublicationId)
                .param("actor", actorId).update();

        // Model/submodel/veri nesnesi topolojisi (source LS ile)
        var topology = mapper.createObjectNode();
        topology.putArray("connections");
        topology.putArray("physicalSchemas");
        topology.putArray("logicalSchemas").addObject()
                .put("code", "SOURCE_LS")
                .put("name", "Source LS")
                .put("provider", "ORACLE")
                .put("sourceUuid", sourceLogicalSchemaUuid.toString());
        topology.putArray("environments").addObject()
                .put("code", "SOURCE_ENV")
                .put("name", "Source Environment")
                .put("sourceUuid", sourceEnvironmentUuid.toString());
        topology.putArray("schemaBindings");
        topology.putArray("models").addObject()
                .put("code", "SAMPLE_MODEL")
                .put("name", "Hakediş Model")
                .putNull("description")
                .put("logicalSchemaCode", "SOURCE_LS")
                .put("reverseEnvironmentCode", "SOURCE_ENV")
                .put("reverseMode", "CUSTOM_RKM")
                .put("rkmCode", "RKM_SAMPLE")
                .put("rkmScope", "PROJE");
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
                .put("submodelCode", "DETAIL");
        repository.importPortableTopology(sourceProject.id(), topology);
        long sourceDataObjectId = jdbc.sql("""
                select id from akis.veri_nesnesi
                 where proje_id = :project and kod = 'SAMPLE_TABLE'
                """).param("project", sourceProject.id()).query(Long.class).single();
        var portableColumns = mapper.createArrayNode();
        portableColumns.addObject()
                .put("reference", "CUSTOMER_NAME")
                .put("name", "CUSTOMER_NAME")
                .put("producerType", "VARCHAR2(100)")
                .put("canonicalType", "STRING")
                .put("ordinal", 1)
                .put("length", 100)
                .put("nullable", false)
                .put("comment", "Customer name")
                .put("sensitivityClass", "KISISEL")
                .put("protection", "SIFRELE");
        jdbc.sql("""
                insert into akis.ithal_katalog_metadata(
                    proje_id, veri_nesnesi_id, kolonlar, kaynak_bundle_checksum)
                values (:project, :dataObject, cast(:columns as jsonb), :checksum)
                """).param("project", sourceProject.id())
                .param("dataObject", sourceDataObjectId)
                .param("columns", portableColumns.toString())
                .param("checksum", "e".repeat(64)).update();

        UUID sourceProcedureDefinitionUuid = repository.findDefinitionUuid(
                sourceProject.id(), DefinitionType.PROCEDURE, "RUN_LOAD");
        UUID sourcePackageDefinitionUuid = repository.findDefinitionUuid(
                sourceProject.id(), DefinitionType.PACKAGE, "MAIN_PACKAGE");
        UUID sourceRkmUuid = repository.findDefinitionUuid(
                sourceProject.id(), DefinitionType.KNOWLEDGE_MODULE, "RKM_SAMPLE");
        assertNotEquals(sourcePackageDefinitionUuid, sourceChildPackageUuid);
        String sourceLogicalSchemaBefore = jdbc.sql(
                        "select to_jsonb(m)::text from akis.mantiksal_sema m where m.uuid = :uuid")
                .param("uuid", sourceLogicalSchemaUuid).query(String.class).single();
        String sourceEnvironmentBefore = jdbc.sql(
                        "select to_jsonb(o)::text from akis.ortam o where o.uuid = :uuid")
                .param("uuid", sourceEnvironmentUuid).query(String.class).single();

        // Export
        ProjectBundle bundle;
        try {
            bundle = service.exportBundle(sourceProject.uuid());
        }
        catch (ProjectBundleException invalid) {
            throw new AssertionError("Fixture export is invalid: " + invalid.report().issues(), invalid);
        }

        assertEquals(ProjectBundleModels.FORMAT, bundle.format());
        assertEquals(ProjectBundleModels.FORMAT_VERSION, bundle.formatVersion());
        assertNotNull(bundle.checksum());
        assertTrue(bundle.topology().sanitized());
        assertEquals(1, bundle.publications().size());
        assertEquals(1, bundle.schedules().size());
        assertNotNull(bundle.schedules().getFirst().startsAt());
        assertNotNull(bundle.schedules().getFirst().endsAt());
        assertEquals(1, bundle.topology().definitions().path("logicalSchemas").size());
        assertEquals("KISISEL", bundle.topology().definitions()
                .path("dataObjects").get(0).path("columns").get(0)
                .path("sensitivityClass").asString());
        for (JsonNode connection : bundle.topology().definitions().path("connections")) {
            assertFalse(connection.has("password"), "Exported topology must not contain secrets.");
        }

        // Validate
        var validation = service.validate(bundle);
        assertTrue(validation.valid(), () -> validation.issues().toString());

        // Boş hedef proje
        var targetProject = repository.insertProject(
                UUID.randomUUID(), "TARGET_PROJECT", "AKTIF", "Target Project", null);

        // Global binding: source code'ları hedefte farklı global kayıtlara bağla.
        List<GlobalBinding> bindings = List.of(
                new GlobalBinding(
                        GlobalResourceType.LOGICAL_SCHEMA, "SOURCE_LS",
                        GlobalBindingMode.BIND_EXISTING, targetLogicalSchemaUuid, null, null),
                new GlobalBinding(
                        GlobalResourceType.ENVIRONMENT, "SOURCE_ENV",
                        GlobalBindingMode.BIND_EXISTING, targetEnvironmentUuid, null, null));

        // Plan
        TargetImportPlan plan = service.planTargetImport(targetProject.uuid(), bundle, bindings);
        assertTrue(plan.valid(), () -> plan.issues().toString());
        assertEquals(targetProject.uuid(), plan.targetProjectUuid());
        assertEquals(bundle.checksum(), plan.bundleChecksum());
        assertNotNull(plan.planDigest());

        // Apply
        var request = new TargetImportRequest(bundle, bindings, plan.planDigest(), plan.targetVersion());
        String idempotencyKey = "round-trip-" + UUID.randomUUID();
        TargetImportResult result = service.importIntoTarget(
                targetProject.uuid(), request, idempotencyKey, actorId);

        assertTrue(result.imported());
        assertFalse(result.replayed());
        assertEquals(targetProject.uuid(), result.targetProjectUuid());
        assertEquals(bundle.checksum(), result.bundleChecksum());
        assertEquals(plan.planDigest(), result.planDigest());

        TargetImportResult replay = service.importIntoTarget(
                targetProject.uuid(), request, idempotencyKey, actorId);
        assertTrue(replay.replayed(), "The same import request must return its original receipt.");
        assertEquals(result.bundleChecksum(), replay.bundleChecksum());
        assertEquals(result.planDigest(), replay.planDigest());
        ProjectBundleException reusedKey = assertThrows(ProjectBundleException.class,
                () -> service.importIntoTarget(targetProject.uuid(),
                        new TargetImportRequest(bundle, bindings, "0".repeat(64), plan.targetVersion()),
                        idempotencyKey, actorId));
        assertEquals("IDEMPOTENCY_KEY_REUSED", reusedKey.code());

        assertEquals(1, pendingRecipes.findPendingPublicationRecipes(targetProject.id()).size());
        assertEquals(1, pendingRecipes.findPendingScheduleRecipes(targetProject.id()).size());
        var pendingSchedule = pendingRecipes.findPendingScheduleRecipes(targetProject.id()).getFirst();
        assertEquals(bundle.schedules().getFirst().startsAt().toInstant(), pendingSchedule.startsAt().toInstant());
        assertEquals(bundle.schedules().getFirst().endsAt().toInstant(), pendingSchedule.endsAt().toInstant());
        assertEquals(0L, jdbc.sql("select count(*) from akis.yayin where proje_id = :project")
                .param("project", targetProject.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.zamanlama where proje_id = :project")
                .param("project", targetProject.id()).query(Long.class).single());
        assertEquals("KISISEL", jdbc.sql("""
                select c.kolonlar->0->>'sensitivityClass'
                  from akis.ithal_katalog_metadata c
                 where c.proje_id = :project
                """).param("project", targetProject.id()).query(String.class).single());
        assertEquals("KISISEL", service.exportBundle(targetProject.uuid())
                .topology().definitions().path("dataObjects").get(0)
                .path("columns").get(0).path("sensitivityClass").asString());

        // Kimlik kanıtları
        var targetSnapshot = repository.loadSnapshot(targetProject.uuid());
        assertNotEquals(sourceProject.uuid(), targetSnapshot.project().uuid());
        assertEquals("TARGET_PROJECT", targetSnapshot.project().code());

        UUID targetProcedureDefinitionUuid = repository.findDefinitionUuid(
                targetProject.id(), DefinitionType.PROCEDURE, "RUN_LOAD");
        UUID targetVariableDefinitionUuid = repository.findDefinitionUuid(
                targetProject.id(), DefinitionType.VARIABLE, "LOAD");
        UUID targetChildPackageUuid = repository.findDefinitionUuid(
                targetProject.id(), DefinitionType.PACKAGE, "CHILD_PACKAGE");
        UUID targetRkmUuid = repository.findDefinitionUuid(
                targetProject.id(), DefinitionType.KNOWLEDGE_MODULE, "RKM_SAMPLE");

        assertNotEquals(sourceProcedureDefinitionUuid, targetProcedureDefinitionUuid);
        assertNotEquals(sourceVariableDefinitionUuid, targetVariableDefinitionUuid);
        assertNotEquals(sourceChildPackageUuid, targetChildPackageUuid);
        assertNotEquals(sourceRkmUuid, targetRkmUuid);
        assertEquals(targetRkmUuid, jdbc.sql("""
                select r.uuid from akis.model m
                  join akis.tanim r on r.id = m.rkm_tanim_id
                 where m.proje_id = :project and m.kod = 'SAMPLE_MODEL'
                """).param("project", targetProject.id()).query(UUID.class).single());

        // Reference remap kanıtı: PROCEDURE task içindeki LS/ENV hedef global UUID'lere dönüşmüş.
        var targetProcedureVersion = targetSnapshot.versions().stream()
                .filter(v -> v.definitionId() == targetSnapshot.definitions().stream()
                        .filter(d -> d.code().equals("RUN_LOAD")).findFirst().orElseThrow().id())
                .findFirst()
                .orElseThrow();
        JsonNode targetProcedureContent = targetProcedureVersion.content();
        JsonNode targetTask = targetProcedureContent.path("tasks").get(0);
        assertEquals(
                targetLogicalSchemaUuid.toString(),
                targetTask.path("logicalSchemaUuid").asString(),
                "Procedure logicalSchemaUuid must be remapped to the bound target global resource.");
        assertEquals(
                targetEnvironmentUuid.toString(),
                targetTask.path("environmentUuid").asString(),
                "Procedure environmentUuid must be remapped to the bound target global resource.");

        // PACKAGE adımındaki definitionUuid hedef VARIABLE tanımına remap edilmiş.
        var targetPackageVersion = targetSnapshot.versions().stream()
                .filter(v -> v.definitionId() == targetSnapshot.definitions().stream()
                        .filter(d -> d.code().equals("MAIN_PACKAGE")).findFirst().orElseThrow().id())
                .findFirst()
                .orElseThrow();
        JsonNode targetPackageStep = targetPackageVersion.content().path("steps").get(0);
        assertEquals(
                targetVariableDefinitionUuid.toString(),
                targetPackageStep.path("definitionUuid").asString(),
                "Package step definitionUuid must be remapped to the target definition.");
        assertEquals(targetChildPackageUuid.toString(),
                targetPackageVersion.content().path("steps").get(1).path("definitionUuid").asString(),
                "Parent package must refer to the imported child package.");
        var targetChildPackageVersion = targetSnapshot.versions().stream()
                .filter(v -> v.definitionId() == targetSnapshot.definitions().stream()
                        .filter(d -> d.code().equals("CHILD_PACKAGE")).findFirst().orElseThrow().id())
                .findFirst().orElseThrow();
        assertEquals(targetProcedureDefinitionUuid.toString(),
                targetChildPackageVersion.content().path("steps").get(0).path("definitionUuid").asString(),
                "Child package must refer to the imported procedure.");

        // Kaynak proje ve global kayıtlar dokunulmamış.
        var sourceAfter = repository.loadSnapshot(sourceProject.uuid());
        assertEquals(sourceProject.version(), sourceAfter.project().version());
        assertEquals(bundle.checksum(), service.exportBundle(sourceProject.uuid()).checksum(),
                "Import must not alter the source project's portable content.");
        var sourceOwnedUuids = new ArrayList<UUID>();
        sourceOwnedUuids.add(sourceProject.uuid());
        sourceAfter.definitions().forEach(definition -> sourceOwnedUuids.add(definition.uuid()));
        sourceAfter.versions().forEach(version -> sourceOwnedUuids.add(version.uuid()));
        String importedDefinitionJson = java.util.stream.Stream.concat(
                        targetSnapshot.drafts().stream().map(draft -> draft.content().toString()),
                        targetSnapshot.versions().stream().map(version -> version.content().toString()))
                .collect(Collectors.joining("\n"));
        for (UUID sourceOwnedUuid : sourceOwnedUuids) {
            assertFalse(importedDefinitionJson.contains(sourceOwnedUuid.toString()),
                    () -> "Imported definitions still reference source-owned UUID " + sourceOwnedUuid);
        }
        assertEquals(sourceLogicalSchemaBefore,
                jdbc.sql("select to_jsonb(m)::text from akis.mantiksal_sema m where m.uuid = :uuid")
                        .param("uuid", sourceLogicalSchemaUuid).query(String.class).single());
        assertEquals(sourceEnvironmentBefore,
                jdbc.sql("select to_jsonb(o)::text from akis.ortam o where o.uuid = :uuid")
                        .param("uuid", sourceEnvironmentUuid).query(String.class).single());

        // İthalat receipt kaydı oluşmuş.
        long receiptCount = jdbc.sql(
                        "select count(*) from akis.proje_paket_ithalati where proje_id = :projectId")
                .param("projectId", targetProject.id())
                .query(Long.class)
                .single();
        assertEquals(1, receiptCount);

        // Persistence handoff: after a new target publication exists, its imported
        // recipe becomes a suspended schedule, never an automatic run.
        long targetProcedureVersionId = jdbc.sql("""
                select id from akis.tanim_surumu
                 where tanim_id = :definition and surum_no = 1
                """).param("definition", targetProcedureVersion.definitionId())
                .query(Long.class).single();
        UUID targetScenarioUuid = ScenarioAcceptanceBridge.compile(jdbc, dataSource, mapper,
                targetProject.uuid(), targetProcedureDefinitionUuid, targetProcedureVersion.uuid());
        long targetScenarioId = jdbc.sql("""
                select id from akis.senaryo where uuid = :uuid and tanim_surumu_id = :version
                """).param("uuid", targetScenarioUuid).param("version", targetProcedureVersionId)
                .query(Long.class).single();
        assertEquals("GECTI", jdbc.sql("""
                select d.sonuc from akis.dogrulama d
                  join akis.senaryo s on s.dogrulama_id = d.id
                 where s.id = :scenario and d.proje_id = :project
                """).param("scenario", targetScenarioId).param("project", targetProject.id())
                .query(String.class).single());
        UUID targetPublicationUuid = PublicationAcceptanceBridge.publish(jdbc, dataSource, mapper,
                targetProject.uuid(), targetScenarioUuid, targetEnvironmentUuid);
        assertNotEquals(jdbc.sql("select uuid from akis.yayin where id = :id")
                .param("id", sourcePublicationId).query(UUID.class).single(), targetPublicationUuid);
        var targetPublication = jdbc.sql("""
                select id, senaryo_id, durum, fiziksel_manifesto->>'releaseHash' as release_hash,
                       fiziksel_manifesto->>'runtimeCapability' as runtime_capability
                  from akis.yayin where uuid = :uuid
                """).param("uuid", targetPublicationUuid)
                .query((rs, row) -> List.of(rs.getLong("id"), rs.getLong("senaryo_id"),
                        rs.getString("durum"), rs.getString("release_hash"),
                        rs.getString("runtime_capability"))).single();
        assertEquals(targetScenarioId, targetPublication.get(1));
        assertEquals("AKTIF", targetPublication.get(2));
        assertEquals(64, ((String) targetPublication.get(3)).length());
        assertEquals("DEFINITION_ONLY", targetPublication.get(4),
                "This schema-v1 fixture proves metadata import and publication, not execution.");
        var activatedSchedule = jdbc.sql("""
                select z.yayin_id, z.durum_kodu, z.sonraki_tetikleme_zamani,
                       z.baslangic_zamani, z.bitis_zamani
                  from akis.zamanlama z
                 where z.proje_id = :project and z.kod = 'RUN_LOAD_DAILY'
                """).param("project", targetProject.id())
                .query((rs, row) -> List.of(
                        rs.getLong("yayin_id"), rs.getString("durum_kodu"),
                        rs.getObject("baslangic_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class)))
                .single();
        assertEquals("ASKIDA", activatedSchedule.get(1));
        assertEquals(targetPublication.getFirst(), activatedSchedule.getFirst());
        assertEquals(bundle.schedules().getFirst().startsAt().toInstant(),
                ((OffsetDateTime) activatedSchedule.get(2)).toInstant());
        assertEquals(bundle.schedules().getFirst().endsAt().toInstant(),
                ((OffsetDateTime) activatedSchedule.get(3)).toInstant());
        assertEquals(0L, jdbc.sql("""
                select count(*) from akis.zamanlama
                 where proje_id = :project and sonraki_tetikleme_zamani is not null
                """).param("project", targetProject.id()).query(Long.class).single());

        // A late FK failure after content insertion must roll back the entire import.
        var rollbackTarget = repository.insertProject(
                UUID.randomUUID(), "ROLLBACK_TARGET", "AKTIF", "Rollback Target", null);
        TargetImportPlan rollbackPlan = service.planTargetImport(
                rollbackTarget.uuid(), bundle, bindings);
        assertTrue(rollbackPlan.valid(), () -> rollbackPlan.issues().toString());
        var rollbackRequest = new TargetImportRequest(
                bundle, bindings, rollbackPlan.planDigest(), rollbackPlan.targetVersion());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(RuntimeException.class, () -> transaction.execute(status ->
                service.importIntoTarget(rollbackTarget.uuid(), rollbackRequest,
                        "rollback-" + UUID.randomUUID(), Long.MAX_VALUE)));
        assertEquals(0L, jdbc.sql("select count(*) from akis.tanim where proje_id = :project")
                .param("project", rollbackTarget.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.klasor where proje_id = :project")
                .param("project", rollbackTarget.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.ithal_yayin_tarifi where proje_id = :project")
                .param("project", rollbackTarget.id()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("select count(*) from akis.proje_paket_ithalati where proje_id = :project")
                .param("project", rollbackTarget.id()).query(Long.class).single());
        assertEquals(rollbackTarget.version(), repository.loadSnapshot(rollbackTarget.uuid()).project().version());

        // A valid checksum does not make a cyclic submodel hierarchy importable.
        ObjectNode cyclicTopology = (ObjectNode) bundle.topology().definitions().deepCopy();
        ((ObjectNode) cyclicTopology.path("submodels").get(0)).put("parentCode", "DETAIL");
        ProjectBundle cyclicBundle = withTopology(bundle, cyclicTopology, service);
        assertTrue(service.validate(cyclicBundle).issues().stream()
                .anyMatch(issue -> "SUBMODEL_HIERARCHY_CYCLE".equals(issue.code())));
        var cyclicTarget = repository.insertProject(
                UUID.randomUUID(), "CYCLIC_TARGET", "AKTIF", "Cyclic Target", null);
        assertFalse(service.planTargetImport(cyclicTarget.uuid(), cyclicBundle, bindings).valid());
        assertEquals(0L, jdbc.sql("select count(*) from akis.alt_model where proje_id = :project")
                .param("project", cyclicTarget.id()).query(Long.class).single());

        ObjectNode missingParentTopology = (ObjectNode) bundle.topology().definitions().deepCopy();
        ((ObjectNode) missingParentTopology.path("submodels").get(1))
                .put("parentCode", "UNKNOWN_PARENT");
        ProjectBundle missingParentBundle = withTopology(bundle, missingParentTopology, service);
        assertTrue(service.validate(missingParentBundle).issues().stream()
                .anyMatch(issue -> "SUBMODEL_PARENT_MISSING".equals(issue.code())));
    }

    private static ProjectBundle withTopology(
            ProjectBundle source, ObjectNode topology, ProjectBundleService service) {
        ProjectBundle unsigned = new ProjectBundle(
                source.format(), source.formatVersion(), source.schemaVersion(), null,
                source.exportedAt(), source.project(), source.folders(), source.definitions(),
                new ProjectBundleModels.TopologyEntry(true, topology), source.producer(),
                source.includedSections(), source.publications(), source.schedules());
        return new ProjectBundle(
                unsigned.format(), unsigned.formatVersion(), unsigned.schemaVersion(),
                service.checksum(unsigned), unsigned.exportedAt(), unsigned.project(),
                unsigned.folders(), unsigned.definitions(), unsigned.topology(),
                unsigned.producer(), unsigned.includedSections(),
                unsigned.publications(), unsigned.schedules());
    }

    private static long insertActor(JdbcClient jdbc) {
        return jdbc.sql("""
                        insert into akis.kullanici(
                            gorunen_ad, kullanici_kodu, ad, durum, parola, parola_degistirilme_zamani)
                        values ('Round Trip Test', 'round_trip_test', 'Round Trip',
                                'AKTIF', '{argon2}test-only', current_timestamp)
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    private static UUID insertLogicalSchema(JdbcClient jdbc, String code, String name, String provider) {
        UUID uuid = UUID.randomUUID();
        jdbc.sql("""
                        insert into akis.mantiksal_sema(uuid, kod, ad, saglayici_turu)
                        values (:uuid, :code, :name, :provider)
                        """)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .param("provider", provider)
                .update();
        return uuid;
    }

    private static UUID insertEnvironment(JdbcClient jdbc, String code, String name) {
        UUID uuid = UUID.randomUUID();
        jdbc.sql("""
                        insert into akis.ortam(uuid, kod, ad)
                        values (:uuid, :code, :name)
                        """)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .update();
        return uuid;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }
}

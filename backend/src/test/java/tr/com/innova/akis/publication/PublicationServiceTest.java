package tr.com.innova.akis.publication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.execution.PilotRuntimePlanResolver;
import tr.com.innova.akis.execution.ProcedureRuntimePlanResolver;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.publication.PublicationModels.ApprovalActor;
import tr.com.innova.akis.publication.PublicationModels.ApprovalResult;
import tr.com.innova.akis.publication.PublicationModels.ApprovalRow;
import tr.com.innova.akis.publication.PublicationModels.CreateResult;
import tr.com.innova.akis.publication.PublicationModels.PublicationContext;
import tr.com.innova.akis.publication.PublicationModels.PublicationDraft;
import tr.com.innova.akis.publication.PublicationModels.PublicationRow;
import tr.com.innova.akis.publication.PublicationModels.ResolvedBinding;

class PublicationServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID SCENARIO_UUID = UUID.randomUUID();
    private static final UUID ENVIRONMENT_UUID = UUID.randomUUID();
    private static final UUID PUBLICATION_UUID = UUID.randomUUID();
    private static final ApprovalActor ACTOR = new ApprovalActor(
            44, UUID.randomUUID(), "Release Approver");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretValueSanitizer secretSanitizer = new SecretValueSanitizer();

    @Test
    void sameResolvedContextProducesOneIdempotentPublication() {
        FakeStore store = new FakeStore(context("DUSUK"), bindings());
        PublicationService service = service(store);

        CreateResult first = service.create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID);
        CreateResult second = service.create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.publication().uuid(), second.publication().uuid());
        assertEquals(first.publication().releaseHash(), second.publication().releaseHash());
        assertEquals(64, first.publication().releaseHash().length());
        assertEquals("AKTIF", first.publication().status());
        assertEquals(1, store.createCount);
        assertNotNull(first.publication().physicalManifest().get("definition"));
        assertNotNull(first.publication().physicalManifest().get("bindings"));
        assertEquals(2, first.publication().physicalManifest().get("manifestVersion").intValue());
        assertEquals("ORACLE", first.publication().physicalManifest()
                .get("bindings").get(0).get("databaseType").stringValue());
        assertEquals("TABLO", first.publication().physicalManifest()
                .get("bindings").get(0).get("dataObjectType").stringValue());
        assertEquals(64, first.publication().physicalManifest()
                .get("definition").get("contentHash").stringValue().length());
        assertEquals(2, first.publication().physicalManifest()
                .get("definition").get("schemaVersion").intValue());
        assertEquals(64, first.publication().physicalManifest()
                .get("runtimePlanHash").stringValue().length());
        assertEquals(PilotRuntimePlanResolver.PILOT_CAPABILITY,
                first.publication().physicalManifest()
                        .get("runtimeCapability").stringValue());
    }

    @Test
    void productionPublicationWaitsForApprovalAndApprovalActivatesIt() {
        FakeStore store = new FakeStore(context("URETIM"), bindings());
        PublicationService service = service(store);

        PublicationRow publication = service.create(
                PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();
        ApprovalResult approval = service.decide(
                PROJECT_UUID, publication.uuid(), ACTOR, "onay", null);
        ApprovalResult retry = service.decide(
                PROJECT_UUID, publication.uuid(), ACTOR, "ONAY", null);

        assertEquals("ONAY_BEKLIYOR", publication.status());
        assertEquals("AKTIF", approval.publication().status());
        assertTrue(approval.created());
        assertFalse(retry.created());
        assertEquals(approval.approval().uuid(), retry.approval().uuid());
        assertEquals(1, store.approvalCreateCount);
    }

    @Test
    void rejectsPublicationWhenTargetSnapshotIsMissing() {
        ResolvedBinding unresolved = binding();
        unresolved = new ResolvedBinding(
                unresolved.definitionDataObjectId(), unresolved.definitionDataObjectUuid(),
                unresolved.nodeCode(), unresolved.role(), unresolved.dataObjectUuid(),
                unresolved.dataObjectReference(), unresolved.dataObjectType(),
                unresolved.environmentSchemaBindingId(),
                unresolved.environmentSchemaBindingUuid(), unresolved.physicalSchemaId(),
                unresolved.physicalSchemaUuid(), unresolved.physicalSchemaReference(),
                unresolved.connectionVersionId(), unresolved.connectionVersionUuid(),
                unresolved.databaseType(),
                null, null, null, unresolved.bindingVersion(),
                unresolved.dataObjectStatus(), unresolved.modelStatus(),
                unresolved.logicalSchemaStatus(),
                unresolved.connectionStatus());
        PublicationService service = service(new FakeStore(
                context("DUSUK"), List.of(sourceBinding(), unresolved)));

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertEquals("TARGET_SCHEMA_SNAPSHOT_MISSING", error.code());
    }

    @Test
    void rejectsApprovalForLowRiskEnvironment() {
        FakeStore store = new FakeStore(context("DUSUK"), bindings());
        PublicationService service = service(store);
        PublicationRow publication = service.create(
                PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        ApiException error = assertThrows(ApiException.class, () -> service.decide(
                PROJECT_UUID, publication.uuid(), ACTOR, "ONAY", null));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("APPROVAL_NOT_REQUIRED", error.code());
    }

    @Test
    void bindingEvidenceChangesBothRuntimeAndReleaseHashes() {
        PublicationContext context = context("DUSUK");
        PublicationRow first = service(new FakeStore(context, bindings()))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();
        ResolvedBinding changedTarget = binding(
                "TARGET", "HEDEF", "INNOVA_ODI", "STG_HAKEDIS_TIPI",
                "c".repeat(64));
        PublicationRow changed = service(new FakeStore(
                context, List.of(sourceBinding(), changedTarget)))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        assertNotEquals(
                first.physicalManifest().path("runtimePlanHash").stringValue(),
                changed.physicalManifest().path("runtimePlanHash").stringValue());
        assertNotEquals(first.releaseHash(), changed.releaseHash());
    }

    @Test
    void rejectsLegacySecretBearingEnvironmentPolicy() {
        PublicationContext safe = context("DUSUK");
        PublicationContext unsafe = new PublicationContext(
                safe.projectId(), safe.scenarioId(), safe.scenarioUuid(),
                safe.definitionUuid(), safe.definitionVersionUuid(),
                safe.definitionSchemaVersion(), safe.definitionContentHash(),
                safe.planHash(), safe.scenarioPlan(), safe.environmentId(),
                safe.environmentUuid(), safe.environmentCode(), safe.environmentRisk(),
                safe.environmentPolicyVersion(),
                objectMapper.createObjectNode().put("password", "legacy-value"));

        ApiException error = assertThrows(
                ApiException.class,
                () -> service(new FakeStore(unsafe, bindings()))
                        .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID));

        assertEquals("SENSITIVE_VALUE_REJECTED", error.code());
    }

    @Test
    void rejectsLegacyOracleThinCredentialsBeforeManifestCreation() {
        PublicationContext safe = context("DUSUK");
        String secret = "inline-secret";
        PublicationContext unsafe = new PublicationContext(
                safe.projectId(), safe.scenarioId(), safe.scenarioUuid(),
                safe.definitionUuid(), safe.definitionVersionUuid(),
                safe.definitionSchemaVersion(), safe.definitionContentHash(),
                safe.planHash(), safe.scenarioPlan(), safe.environmentId(),
                safe.environmentUuid(), safe.environmentCode(), safe.environmentRisk(),
                safe.environmentPolicyVersion(), objectMapper.createObjectNode().put(
                        "jdbcUrl", "jdbc:oracle:thin:app/" + secret
                                + "@//db:1521/service"));
        FakeStore store = new FakeStore(unsafe, bindings());

        ApiException error = assertThrows(
                ApiException.class,
                () -> service(store).create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID));

        assertEquals("SENSITIVE_VALUE_REJECTED", error.code());
        assertFalse(error.getMessage().contains(secret));
        assertEquals(null, store.publication);
    }

    @Test
    void schemaV1MappingRemainsPublishableAsDefinitionOnly() {
        ObjectNode definition = mappingDefinition();
        ((ObjectNode) definition.get("writeStrategy")).put("kind", "APPEND");
        PublicationContext context = context("DUSUK", "MAPPING", 1, definition);

        PublicationRow publication = service(new FakeStore(context, bindings()))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        assertEquals(PilotRuntimePlanResolver.DEFINITION_ONLY_CAPABILITY,
                publication.physicalManifest().get("runtimeCapability").stringValue());
        assertFalse(publication.physicalManifest().has("runtimePlanHash"));
    }

    @Test
    void nonMappingScenarioRemainsPublishableAsDefinitionOnly() {
        ObjectNode definition = (ObjectNode) objectMapper.readTree("""
                {"tasks":[{"id":"READ","type":"SQL","connectionRole":"SOURCE",
                  "riskClass":"READ_ONLY","command":"SELECT 1 FROM DUAL"}]}
                """);
        PublicationContext context = context("DUSUK", "PROCEDURE", 1, definition);

        PublicationRow publication = service(new FakeStore(context, List.of()))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        assertEquals(PilotRuntimePlanResolver.DEFINITION_ONLY_CAPABILITY,
                publication.physicalManifest().get("runtimeCapability").stringValue());
        assertFalse(publication.physicalManifest().has("runtimePlanHash"));
    }

    @Test
    void procedureV2PublishesAsPinnedOracleProcedurePlan() {
        PublicationContext context = context(
                "DUSUK", "PROCEDURE", 2, procedureDefinition());
        FakeStore store = new FakeStore(context, procedureBindings());
        PublicationService service = service(store);
        PublicationRow publication = service
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        assertEquals(ProcedureRuntimePlanResolver.CAPABILITY,
                publication.physicalManifest().path("runtimeCapability").stringValue());
        assertEquals(tr.com.innova.akis.execution.ProcedurePolicyVersions.current(objectMapper),
                publication.physicalManifest().get("policyVersions"));
        assertEquals(64, publication.physicalManifest()
                .path("runtimePlanHash").stringValue().length());
        assertTrue(publication.physicalManifest().path("approvalRequired").booleanValue());
        assertEquals("ONAY_BEKLIYOR", publication.status());
        assertEquals("AKTIF", service.decide(
                PROJECT_UUID, publication.uuid(), ACTOR, "ONAY", null)
                .publication().status());
    }

    @Test
    void invalidProcedureV2IsRejectedInsteadOfPublishedAsDefinitionOnly() {
        ObjectNode invalid = procedureDefinition();
        ((ObjectNode) invalid.get("tasks").get(1))
                .put("command", "DELETE FROM TTBP.HAKEDIS_TIPI");
        PublicationContext context = context("DUSUK", "PROCEDURE", 2, invalid);

        ApiException error = assertThrows(ApiException.class, () -> service(new FakeStore(
                context, procedureBindings()))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertEquals("PROCEDURE_RUNTIME_PLAN_REJECTED", error.code());
    }

    @Test
    void unsupportedSchemaV2MappingShapesRemainDefinitionOnly() {
        ObjectNode append = mappingDefinition();
        ((ObjectNode) append.get("writeStrategy")).put("kind", "APPEND");
        PublicationRow appendPublication = service(new FakeStore(
                context("DUSUK", "MAPPING", 2, append), bindings()))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        ObjectNode expression = (ObjectNode) objectMapper.readTree("""
                {"datasets":[{"id":"SOURCE","role":"SOURCE"},
                             {"id":"LOOKUP","role":"SOURCE"},
                             {"id":"TARGET","role":"TARGET"}],
                 "columnMappings":[
                   {"expression":{"kind":"LITERAL","value":1},
                    "target":{"dataset":"TARGET","column":"ID"}}],
                 "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"}}
                """);
        PublicationRow expressionPublication = service(new FakeStore(
                context("DUSUK", "MAPPING", 2, expression), bindings()))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        assertEquals(PilotRuntimePlanResolver.DEFINITION_ONLY_CAPABILITY,
                appendPublication.physicalManifest()
                        .get("runtimeCapability").stringValue());
        assertEquals(PilotRuntimePlanResolver.DEFINITION_ONLY_CAPABILITY,
                expressionPublication.physicalManifest()
                        .get("runtimeCapability").stringValue());
        assertFalse(appendPublication.physicalManifest().has("runtimePlanHash"));
        assertFalse(expressionPublication.physicalManifest().has("runtimePlanHash"));
    }

    @Test
    void rejectsInactiveResourcesBeforeSigningPublication() {
        assertInactiveBindingRejected(
                "PASIF", "AKTIF", "AKTIF", "AKTIF", "DATA_OBJECT_INACTIVE");
        assertInactiveBindingRejected("AKTIF", "ARSIV", "AKTIF", "AKTIF", "MODEL_INACTIVE");
        assertInactiveBindingRejected("AKTIF", "AKTIF", "PASIF", "AKTIF", "LOGICAL_SCHEMA_INACTIVE");
        assertInactiveBindingRejected("AKTIF", "AKTIF", "AKTIF", "PASIF", "CONNECTION_INACTIVE");
    }

    @Test
    void apiViewsNeverExposeInternalNumericIds() {
        List<Class<?>> views = List.of(
                PublicationController.PublicationView.class,
                PublicationController.ApprovalView.class,
                PublicationController.ApprovalDecisionView.class);

        for (Class<?> view : views) {
            assertTrue(Arrays.stream(view.getRecordComponents())
                    .noneMatch(component -> component.getName().equalsIgnoreCase("id")));
        }
    }

    private PublicationContext context(String risk) {
        return context(risk, "MAPPING", 2, mappingDefinition());
    }

    private PublicationContext context(
            String risk, String definitionType, int schemaVersion, ObjectNode definition) {
        UUID definitionUuid = UUID.randomUUID();
        UUID definitionVersionUuid = UUID.randomUUID();
        String contentHash = sha256(canonicalize(definition).toString());
        ObjectNode scenarioPlan = scenarioPlan(
                definitionUuid, definitionVersionUuid, contentHash,
                definitionType, schemaVersion, definition);
        String planHash = sha256(canonicalize(scenarioPlan).toString());
        return new PublicationContext(
                10, 20, SCENARIO_UUID, definitionUuid, definitionVersionUuid,
                schemaVersion, contentHash, planHash, scenarioPlan,
                30, ENVIRONMENT_UUID, "TEST", risk, 1,
                objectMapper.createObjectNode().put("approvalCount", 1));
    }

    private ResolvedBinding binding() {
        return binding("TARGET", "HEDEF", "INNOVA_ODI", "STG_HAKEDIS_TIPI");
    }

    private ResolvedBinding sourceBinding() {
        return binding("SOURCE", "KAYNAK", "TTBP", "HAKEDIS_TIPI");
    }

    private List<ResolvedBinding> bindings() {
        return List.of(sourceBinding(), binding());
    }

    private ResolvedBinding binding(
            String nodeCode, String role, String schema, String object) {
        return binding(nodeCode, role, schema, object, "b".repeat(64));
    }

    private ResolvedBinding binding(
            String nodeCode,
            String role,
            String schema,
            String object,
            String fingerprint) {
        return new ResolvedBinding(
                40, UUID.randomUUID(), nodeCode, role, UUID.randomUUID(),
                object, "TABLO", 50L, UUID.randomUUID(), 60L, UUID.randomUUID(),
                schema, 70L, UUID.randomUUID(), "ORACLE", 80L,
                UUID.randomUUID(), fingerprint, 2,
                "AKTIF", "AKTIF", "AKTIF", "AKTIF");
    }

    private void assertInactiveBindingRejected(
            String dataObjectStatus,
            String modelStatus,
            String logicalSchemaStatus,
            String connectionStatus,
            String expectedCode) {
        ResolvedBinding active = sourceBinding();
        ResolvedBinding inactive = new ResolvedBinding(
                active.definitionDataObjectId(), active.definitionDataObjectUuid(),
                active.nodeCode(), active.role(), active.dataObjectUuid(),
                active.dataObjectReference(), active.dataObjectType(),
                active.environmentSchemaBindingId(), active.environmentSchemaBindingUuid(),
                active.physicalSchemaId(), active.physicalSchemaUuid(),
                active.physicalSchemaReference(), active.connectionVersionId(),
                active.connectionVersionUuid(), active.databaseType(),
                active.targetSnapshotId(), active.targetSnapshotUuid(),
                active.targetSnapshotFingerprint(), active.bindingVersion(),
                dataObjectStatus, modelStatus, logicalSchemaStatus, connectionStatus);

        ApiException error = assertThrows(ApiException.class, () -> service(new FakeStore(
                context("DUSUK"), List.of(inactive, binding())))
                .create(PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID));

        assertEquals(expectedCode, error.code());
    }

    private PublicationService service(PublicationStore store) {
        return new PublicationService(
                store,
                objectMapper,
                new PilotRuntimePlanResolver(objectMapper, secretSanitizer),
                new ProcedureRuntimePlanResolver(
                        objectMapper, secretSanitizer, new DefinitionContentValidator()),
                secretSanitizer);
    }

    private ObjectNode mappingDefinition() {
        return (ObjectNode) objectMapper.readTree("""
                {"datasets":[{"id":"SOURCE","role":"SOURCE"},
                             {"id":"TARGET","role":"TARGET"}],
                 "columnMappings":[
                   {"source":{"dataset":"SOURCE","column":"ID"},
                    "target":{"dataset":"TARGET","column":"ID"}}],
                 "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"}}
                """);
    }

    private ObjectNode procedureDefinition() {
        return (ObjectNode) objectMapper.readTree("""
                {"tasks":[
                  {"id":"TRUNCATE_TARGET","type":"SQL","connectionRole":"TARGET",
                   "riskClass":"DESTRUCTIVE","requiresApproval":true,"onError":"STOP",
                   "timeoutSeconds":60,"command":"TRUNCATE TABLE INNOVA_ODI.STG_HAKEDIS_TIPI"},
                  {"id":"READ_SOURCE","type":"SQL","connectionRole":"SOURCE",
                   "riskClass":"READ_ONLY","onError":"STOP","timeoutSeconds":300,
                   "command":"SELECT ID FROM TTBP.HAKEDIS_TIPI",
                   "output":{"kind":"ROWSET","maxRows":1000}},
                  {"id":"INSERT_TARGET","type":"SQL","connectionRole":"TARGET",
                   "riskClass":"DML","onError":"STOP","timeoutSeconds":300,
                   "command":"INSERT INTO INNOVA_ODI.STG_HAKEDIS_TIPI (ID) VALUES (:ID)",
                   "input":{"fromTask":"READ_SOURCE","mode":"BATCH","batchSize":250}},
                   {"id":"GATHER_TARGET_STATS","type":"PLSQL","connectionRole":"TARGET",
                    "riskClass":"DESTRUCTIVE","requiresApproval":true,"onError":"STOP",
                   "timeoutSeconds":300,
                   "command":"BEGIN DBMS_STATS.GATHER_TABLE_STATS('INNOVA_ODI','STG_HAKEDIS_TIPI'); END;"}
                ]}
                """);
    }

    private List<ResolvedBinding> procedureBindings() {
        ResolvedBinding source = binding(
                "READ_SOURCE", "KAYNAK", "TTBP", "HAKEDIS_TIPI");
        ResolvedBinding target = binding(
                "TRUNCATE_TARGET", "HEDEF", "INNOVA_ODI", "STG_HAKEDIS_TIPI");
        return List.of(
                source,
                target,
                copyBindingForNode(target, "INSERT_TARGET", 41),
                copyBindingForNode(target, "GATHER_TARGET_STATS", 42));
    }

    private ResolvedBinding copyBindingForNode(
            ResolvedBinding source, String nodeCode, long definitionBindingId) {
        return new ResolvedBinding(
                definitionBindingId, UUID.randomUUID(), nodeCode, source.role(),
                source.dataObjectUuid(), source.dataObjectReference(), source.dataObjectType(),
                source.environmentSchemaBindingId(), source.environmentSchemaBindingUuid(),
                source.physicalSchemaId(), source.physicalSchemaUuid(),
                source.physicalSchemaReference(), source.connectionVersionId(),
                source.connectionVersionUuid(), source.databaseType(), source.targetSnapshotId(),
                source.targetSnapshotUuid(), source.targetSnapshotFingerprint(),
                source.bindingVersion(), source.dataObjectStatus(), source.modelStatus(),
                source.logicalSchemaStatus(), source.connectionStatus());
    }

    private ObjectNode scenarioPlan(
            UUID definitionUuid,
            UUID definitionVersionUuid,
            String contentHash,
            String definitionType,
            int schemaVersion,
            ObjectNode definition) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("contentHash", contentHash);
        source.put("definitionType", definitionType);
        source.put("definitionUuid", definitionUuid.toString());
        source.put("definitionVersion", 1);
        source.put("definitionVersionUuid", definitionVersionUuid.toString());
        source.put("schemaVersion", schemaVersion);
        ObjectNode executable = objectMapper.createObjectNode();
        executable.set("definition", definition.deepCopy());
        executable.put("kind", definitionType);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("compiler", "AKIS");
        plan.put("compilerVersion", 2);
        plan.set("executable", executable);
        plan.set("source", source);
        return plan;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        return node.deepCopy();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private final class FakeStore implements PublicationStore {

        private final PublicationContext context;
        private final List<ResolvedBinding> bindings;
        private PublicationRow publication;
        private ApprovalRow approval;
        private int createCount;
        private int approvalCreateCount;

        private FakeStore(PublicationContext context, List<ResolvedBinding> bindings) {
            this.context = context;
            this.bindings = bindings;
        }

        @Override
        public boolean projectExists(UUID projectUuid) {
            return PROJECT_UUID.equals(projectUuid);
        }

        @Override
        public Optional<PublicationContext> lockContext(
                UUID projectUuid, UUID scenarioUuid, UUID environmentUuid) {
            return PROJECT_UUID.equals(projectUuid)
                    && SCENARIO_UUID.equals(scenarioUuid)
                    && ENVIRONMENT_UUID.equals(environmentUuid)
                    ? Optional.of(context) : Optional.empty();
        }

        @Override
        public List<ResolvedBinding> resolveBindings(PublicationContext ignored) {
            return bindings;
        }

        @Override
        public Optional<PublicationRow> findByReleaseHash(
                long scenarioId, long environmentId, String releaseHash) {
            return publication != null && publication.releaseHash().equals(releaseHash)
                    ? Optional.of(publication) : Optional.empty();
        }

        @Override
        public PublicationRow create(PublicationDraft draft, UUID publicationUuid) {
            createCount++;
            publication = new PublicationRow(
                    90, publicationUuid, context.scenarioUuid(), context.definitionUuid(),
                    context.definitionVersionUuid(), context.environmentUuid(),
                    context.environmentCode(), context.environmentRisk(), 1, draft.status(),
                    draft.releaseHash(), draft.dependencySummary(), draft.physicalManifest(),
                    "AKTIF".equals(draft.status()) ? OffsetDateTime.now() : null,
                    OffsetDateTime.now(), 1);
            return publication;
        }

        @Override
        public Optional<PublicationRow> find(UUID projectUuid, UUID publicationUuid) {
            return publication != null && publication.uuid().equals(publicationUuid)
                    ? Optional.of(publication) : Optional.empty();
        }

        @Override
        public List<PublicationRow> list(UUID projectUuid) {
            return publication == null ? List.of() : List.of(publication);
        }

        @Override
        public Optional<PublicationRow> lockPublication(
                UUID projectUuid, UUID publicationUuid) {
            return find(projectUuid, publicationUuid);
        }

        @Override
        public Optional<ApprovalActor> findActiveActor(String provider, String subject) {
            return Optional.of(ACTOR);
        }

        @Override
        public Optional<ApprovalRow> findLatestApproval(
                long publicationId, long actorId, String decision) {
            return approval != null && approval.decision().equals(decision)
                    ? Optional.of(approval) : Optional.empty();
        }

        @Override
        public ApprovalRow createApproval(
                PublicationRow source,
                ApprovalActor actor,
                String decision,
                String reason,
                UUID approvalUuid) {
            approvalCreateCount++;
            approval = new ApprovalRow(
                    approvalUuid, source.uuid(), actor.uuid(), actor.name(),
                    decision, OffsetDateTime.now(), reason);
            return approval;
        }

        @Override
        public PublicationRow transition(
                long publicationId, String expectedStatus, String targetStatus) {
            publication = new PublicationRow(
                    publication.id(), publication.uuid(), publication.scenarioUuid(),
                    publication.definitionUuid(), publication.definitionVersionUuid(),
                    publication.environmentUuid(), publication.environmentCode(),
                    publication.environmentRisk(), publication.publicationNumber(), targetStatus,
                    publication.releaseHash(), publication.dependencySummary(),
                    publication.physicalManifest(),
                    "AKTIF".equals(targetStatus) ? OffsetDateTime.now() : publication.publishedAt(),
                    publication.createdAt(), publication.version() + 1);
            return publication;
        }
    }
}

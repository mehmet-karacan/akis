package tr.com.innova.akis.publication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
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

    @Test
    void sameResolvedContextProducesOneIdempotentPublication() {
        FakeStore store = new FakeStore(context("DUSUK"), List.of(binding()));
        PublicationService service = new PublicationService(store, objectMapper);

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
    }

    @Test
    void productionPublicationWaitsForApprovalAndApprovalActivatesIt() {
        FakeStore store = new FakeStore(context("URETIM"), List.of(binding()));
        PublicationService service = new PublicationService(store, objectMapper);

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
                unresolved.dataObjectReference(), unresolved.environmentSchemaBindingId(),
                unresolved.environmentSchemaBindingUuid(), unresolved.physicalSchemaId(),
                unresolved.physicalSchemaUuid(), unresolved.physicalSchemaReference(),
                unresolved.connectionVersionId(), unresolved.connectionVersionUuid(),
                null, null, null, unresolved.bindingVersion());
        PublicationService service = new PublicationService(
                new FakeStore(context("DUSUK"), List.of(unresolved)), objectMapper);

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertEquals("TARGET_SCHEMA_SNAPSHOT_MISSING", error.code());
    }

    @Test
    void rejectsApprovalForLowRiskEnvironment() {
        FakeStore store = new FakeStore(context("DUSUK"), List.of(binding()));
        PublicationService service = new PublicationService(store, objectMapper);
        PublicationRow publication = service.create(
                PROJECT_UUID, SCENARIO_UUID, ENVIRONMENT_UUID).publication();

        ApiException error = assertThrows(ApiException.class, () -> service.decide(
                PROJECT_UUID, publication.uuid(), ACTOR, "ONAY", null));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("APPROVAL_NOT_REQUIRED", error.code());
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
        return new PublicationContext(
                10, 20, SCENARIO_UUID, UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), 30, ENVIRONMENT_UUID, "TEST", risk, 1,
                objectMapper.createObjectNode().put("approvalCount", 1));
    }

    private ResolvedBinding binding() {
        return new ResolvedBinding(
                40, UUID.randomUUID(), "TARGET", "HEDEF", UUID.randomUUID(),
                "STG_HAKEDIS_TIPI", 50L, UUID.randomUUID(), 60L, UUID.randomUUID(),
                "INNOVA_ODI", 70L, UUID.randomUUID(), 80L, UUID.randomUUID(),
                "b".repeat(64), 2);
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

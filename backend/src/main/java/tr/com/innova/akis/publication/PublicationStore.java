package tr.com.innova.akis.publication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.publication.PublicationModels.ApprovalActor;
import tr.com.innova.akis.publication.PublicationModels.ApprovalRow;
import tr.com.innova.akis.publication.PublicationModels.PublicationContext;
import tr.com.innova.akis.publication.PublicationModels.PublicationDraft;
import tr.com.innova.akis.publication.PublicationModels.PublicationRow;
import tr.com.innova.akis.publication.PublicationModels.ResolvedBinding;

interface PublicationStore {

    boolean projectExists(UUID projectUuid);

    Optional<PublicationContext> lockContext(
            UUID projectUuid, UUID scenarioUuid, UUID environmentUuid);

    List<ResolvedBinding> resolveBindings(PublicationContext context);
    /**
     * Node codes whose definition version was designed against an older schema snapshot than the one the environment would pin
     * now (the data object was re-snapshotted after the version was created). The runtime refuses such bindings, so publishing must too.
     */
    default List<String> staleDesignSnapshots(PublicationContext context, List<ResolvedBinding> bindings) { return List.of(); }
    default java.util.Map<String, List<String>> sensitiveColumns(PublicationContext context, List<ResolvedBinding> bindings) { return java.util.Map.of(); }

    /** Rejects the release when a protected source column is not text or the target column cannot hold the ciphertext. */
    default void verifySensitiveColumns(PublicationContext context, List<ResolvedBinding> bindings, java.util.Map<String, List<String>> sensitive) { }

    default tools.jackson.databind.JsonNode resolveVariableBindings(PublicationContext context) { return null; }

    Optional<PublicationRow> findByReleaseHash(
            long scenarioId, long environmentId, String releaseHash);

    PublicationRow create(PublicationDraft draft, UUID publicationUuid);

    Optional<PublicationRow> find(UUID projectUuid, UUID publicationUuid);

    List<PublicationRow> list(UUID projectUuid);

    Optional<PublicationRow> lockPublication(UUID projectUuid, UUID publicationUuid);

    Optional<ApprovalActor> findActiveActor(String provider, String subject);

    Optional<ApprovalRow> findLatestApproval(
            long publicationId, long actorId, String decision);

    ApprovalRow createApproval(
            PublicationRow publication,
            ApprovalActor actor,
            String decision,
            String reason,
            UUID approvalUuid);

    PublicationRow transition(long publicationId, String expectedStatus, String targetStatus);
}

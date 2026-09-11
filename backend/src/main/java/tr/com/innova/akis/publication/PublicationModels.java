package tr.com.innova.akis.publication;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

final class PublicationModels {

    private PublicationModels() {
    }

    record PublicationContext(
            long projectId,
            long scenarioId,
            UUID scenarioUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            int definitionSchemaVersion,
            String definitionContentHash,
            String planHash,
            JsonNode scenarioPlan,
            long environmentId,
            UUID environmentUuid,
            String environmentCode,
            String environmentRisk,
            int environmentPolicyVersion,
            JsonNode environmentPolicy) {
    }

    record ResolvedBinding(
            long definitionDataObjectId,
            UUID definitionDataObjectUuid,
            String nodeCode,
            String role,
            UUID dataObjectUuid,
            String dataObjectReference,
            String dataObjectType,
            Long environmentSchemaBindingId,
            UUID environmentSchemaBindingUuid,
            Long physicalSchemaId,
            UUID physicalSchemaUuid,
            String physicalSchemaReference,
            Long connectionVersionId,
            UUID connectionVersionUuid,
            String databaseType,
            Long targetSnapshotId,
            UUID targetSnapshotUuid,
            String targetSnapshotFingerprint,
            long bindingVersion,
            String dataObjectStatus,
            String modelStatus,
            String logicalSchemaStatus,
            String connectionStatus) {
    }

    record PublicationDraft(
            PublicationContext context,
            String releaseHash,
            String dependencySummary,
            JsonNode physicalManifest,
            List<ResolvedBinding> bindings,
            String status) {
    }

    record PublicationRow(
            long id,
            UUID uuid,
            UUID scenarioUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            UUID environmentUuid,
            String environmentCode,
            String environmentRisk,
            int publicationNumber,
            String status,
            String releaseHash,
            String dependencySummary,
            JsonNode physicalManifest,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            long version) {
    }

    record CreateResult(PublicationRow publication, boolean created) {
    }

    record ApprovalActor(long id, UUID uuid, String name) {
    }

    record ApprovalRow(
            UUID uuid,
            UUID publicationUuid,
            UUID actorUuid,
            String actorName,
            String decision,
            OffsetDateTime decidedAt,
            String reason) {
    }

    record ApprovalResult(
            PublicationRow publication,
            ApprovalRow approval,
            boolean created) {
    }
}

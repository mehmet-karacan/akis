package tr.com.innova.akis.topology;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

final class TopologyModels {

    private TopologyModels() {
    }

    record ProjectRef(long id, UUID uuid) {
    }

    record ConnectionRow(
            long id,
            long projectId,
            UUID uuid,
            String code,
            String databaseType,
            String status,
            String name,
            String description,
            long version) {
    }

    record ConnectionVersionRow(
            long id,
            UUID uuid,
            long connectionId,
            int versionNumber,
            String mode,
            String driverReference,
            String username,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String jndiName,
            String tlsMode,
            Integer port,
            int policyVersion,
            JsonNode policy,
            OffsetDateTime createdAt,
            String lifecycleStatus,
            long lifecycleVersion,
            Integer targetIdentityVersion,
            String targetFingerprint,
            UUID latestSuccessfulTestUuid,
            OffsetDateTime testedAt,
            OffsetDateTime activatedAt) {
    }

    record PhysicalSchemaRow(
            long id,
            UUID uuid,
            long connectionId,
            UUID connectionUuid,
            String code,
            String schemaReference,
            String status,
            String name,
            long version) {
    }

    record LogicalSchemaRow(
            long id,
            UUID uuid,
            String code,
            String status,
            String name,
            String description,
            long version) {
    }

    record EnvironmentRow(
            long id,
            UUID uuid,
            String code,
            String risk,
            String status,
            int policyVersion,
            JsonNode policy,
            String name,
            long version) {
    }

    record SchemaBindingRow(
            UUID uuid,
            UUID logicalSchemaUuid,
            UUID environmentUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            String status,
            long version) {
    }
}

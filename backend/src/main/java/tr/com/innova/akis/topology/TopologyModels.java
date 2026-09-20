package tr.com.innova.akis.topology;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Global topology rows (ODI master-repository model): no project scope, no versioning. */
final class TopologyModels {

    private TopologyModels() {
    }

    record ProjectRef(long id, UUID uuid) {
    }

    record ConnectionRow(
            long id,
            UUID uuid,
            String code,
            String name,
            String description,
            String databaseType,
            String mode,
            String driverReference,
            String host,
            Integer port,
            String serviceName,
            String sid,
            String databaseName,
            String jdbcUrlExtra,
            String jndiName,
            String username,
            boolean hasPassword,
            int fetchSize,
            int batchSize,
            int connectTimeoutMs,
            int readTimeoutMs,
            int queryTimeoutSeconds,
            String onConnectSql,
            String onDisconnectSql,
            OffsetDateTime lastTestedAt,
            Boolean lastTestPassed,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    record ConnectionCatalogRow(
            ConnectionRow connection,
            int physicalSchemaCount,
            int logicalSchemaCount) {
    }

    record ConnectionDependencyRow(UUID uuid, String type, String name) {
    }

    record ConnectionTestRow(
            long id,
            UUID uuid,
            long connectionId,
            int attemptNumber,
            String outcome,
            String errorCode,
            String databaseProduct,
            String databaseVersion,
            String driverName,
            String driverVersion,
            Integer databaseMajorVersion,
            Integer databaseMinorVersion,
            Integer targetIdentityVersion,
            String targetFingerprint,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            long durationMs) {
    }

    record PhysicalSchemaRow(
            long id,
            UUID uuid,
            long connectionId,
            UUID connectionUuid,
            String code,
            String name,
            String description,
            String databaseType,
            String catalogName,
            String schemaName,
            String workCatalogName,
            String workSchemaName,
            boolean defaultSchema,
            String loadingPrefix,
            String integrationPrefix,
            String errorPrefix,
            String tempPrefix,
            String objectPattern,
            String remoteObjectPattern,
            String sequencePattern,
            String status) {
    }

    record LogicalSchemaRow(
            long id,
            UUID uuid,
            String code,
            String name,
            String description,
            String databaseType,
            String status) {
    }

    record EnvironmentRow(
            long id,
            UUID uuid,
            String code,
            String name,
            String description,
            String risk,
            boolean defaultEnvironment,
            int policyVersion,
            JsonNode policy,
            String status) {
    }

    record SchemaBindingRow(
            UUID uuid,
            UUID logicalSchemaUuid,
            UUID environmentUuid,
            UUID physicalSchemaUuid,
            String databaseType) {
    }
}

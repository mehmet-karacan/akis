package tr.com.innova.akis.oracle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public final class OracleDiscoveryModels {

    private OracleDiscoveryModels() {
    }

    record ConnectionProfile(
            long projectId,
            long connectionId,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            String databaseType,
            String mode,
            String jndiName,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String tlsMode,
            int port,
            JsonNode policy,
            String secretProvider,
            String secretReferencePath,
            String secretStatus,
            String lifecycleStatus,
            long lifecycleStateVersion,
            UUID latestSuccessfulTestUuid,
            Integer targetIdentityVersion,
            String targetFingerprint) {

        ConnectionProfile(
                long projectId,
                long connectionId,
                UUID connectionUuid,
                UUID connectionVersionUuid,
                String databaseType,
                String mode,
                String jndiName,
                String driverReference,
                String host,
                String serviceName,
                String sid,
                String tlsMode,
                int port,
                JsonNode policy,
                String secretProvider,
                String secretReferencePath,
                String secretStatus,
                String lifecycleStatus,
                Integer targetIdentityVersion,
                String targetFingerprint) {
            this(projectId, connectionId, connectionUuid, connectionVersionUuid,
                    databaseType, mode, jndiName, driverReference, host, serviceName,
                    sid, tlsMode, port, policy, secretProvider, secretReferencePath,
                    secretStatus, lifecycleStatus, 1L, new UUID(0L, 0L),
                    targetIdentityVersion, targetFingerprint);
        }

        ConnectionProfile(
                long projectId,
                long connectionId,
                UUID connectionUuid,
                UUID connectionVersionUuid,
                String databaseType,
                String mode,
                String jndiName,
                String driverReference,
                String host,
                String serviceName,
                String sid,
                String tlsMode,
                int port,
                JsonNode policy,
                String secretProvider,
                String secretReferencePath,
                String secretStatus) {
            this(projectId, connectionId, connectionUuid, connectionVersionUuid,
                    databaseType, mode, jndiName, driverReference, host, serviceName,
                    sid, tlsMode, port, policy, secretProvider, secretReferencePath,
                    secretStatus, "ACTIVE", 1L, new UUID(0L, 0L),
                    OracleDatabaseIdentityFingerprintV1.IDENTITY_VERSION,
                    "0000000000000000000000000000000000000000000000000000000000000000");
        }

        ConnectionProfile(
                long projectId,
                long connectionId,
                UUID connectionUuid,
                UUID connectionVersionUuid,
                String databaseType,
                String driverReference,
                String host,
                String serviceName,
                String sid,
                String tlsMode,
                int port,
                JsonNode policy,
                String secretProvider,
                String secretReferencePath,
                String secretStatus) {
            this(projectId, connectionId, connectionUuid, connectionVersionUuid,
                    databaseType, "JDBC", null, driverReference, host, serviceName,
                    sid, tlsMode, port, policy, secretProvider,
                    secretReferencePath, secretStatus, "ACTIVE", 1L, new UUID(0L, 0L),
                    OracleDatabaseIdentityFingerprintV1.IDENTITY_VERSION,
                    "0000000000000000000000000000000000000000000000000000000000000000");
        }
    }

    /** Draft (unsaved) connection definition used by the pre-save test. */
    public record DraftConnection(
            String databaseType, String mode, String driverReference, String host, Integer port,
            String serviceName, String sid, String databaseName, String jndiName,
            String username, String password, Integer connectTimeoutMs, Integer readTimeoutMs,
            Integer queryTimeoutSeconds) {
    }

    record PhysicalSchemaProfile(
            UUID uuid,
            long connectionId,
            String schemaReference,
            String status) {
    }

    record DataObjectCaptureProfile(
            UUID uuid,
            String objectReference,
            String objectType,
            String status) {
    }

    record Credentials(String username, char[] password) implements AutoCloseable {

        @Override
        public void close() {
            java.util.Arrays.fill(password, '\0');
        }
    }

    public record ConnectionProbe(
            String databaseProduct,
            String databaseVersion,
            int databaseMajorVersion,
            int databaseMinorVersion,
            String driverName,
            String driverVersion,
            int targetIdentityVersion,
            String targetFingerprint) {

        ConnectionProbe(
                String databaseProduct,
                String databaseVersion,
                int databaseMajorVersion,
                int databaseMinorVersion,
                String driverName,
                String driverVersion) {
            this(databaseProduct, databaseVersion, databaseMajorVersion, databaseMinorVersion,
                    driverName, driverVersion, 0, null);
        }
    }

    record ColumnMetadata(
            String name,
            int jdbcType,
            String producerType,
            int ordinal,
            Integer precision,
            Integer scale,
            boolean nullable,
            String defaultExpression) {
    }

    record ConstraintMetadata(
            String name,
            String type,
            List<String> columns,
            String referencedOwner,
            String referencedTable) {
    }

    record TableMetadata(
            String owner,
            String name,
            String type,
            List<ColumnMetadata> columns,
            List<ConstraintMetadata> constraints) {
    }

    record DiscoveryResult(
            String owner,
            OffsetDateTime discoveredAt,
            boolean truncated,
            List<TableMetadata> tables) {
    }

    record SnapshotCapture(
            OffsetDateTime capturedAt,
            OracleSchemaSnapshotCodecV1.SnapshotDefinition definition) {
    }

    record GovernedSnapshotCapture(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID physicalSchemaUuid,
            UUID dataObjectUuid,
            long lifecycleStateVersion,
            UUID successfulTestUuid,
            int targetIdentityVersion,
            String targetFingerprint,
            SnapshotCapture capture) {
    }
}

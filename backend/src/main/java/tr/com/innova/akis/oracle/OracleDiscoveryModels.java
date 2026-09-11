package tr.com.innova.akis.oracle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

final class OracleDiscoveryModels {

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
            String secretStatus) {

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
                    secretReferencePath, secretStatus);
        }
    }

    record PhysicalSchemaProfile(
            UUID uuid,
            long connectionId,
            String schemaReference,
            String status) {
    }

    record Credentials(String username, char[] password) implements AutoCloseable {

        @Override
        public void close() {
            java.util.Arrays.fill(password, '\0');
        }
    }

    record ConnectionProbe(
            String databaseProduct,
            String databaseVersion,
            int databaseMajorVersion,
            int databaseMinorVersion,
            String driverName,
            String driverVersion) {
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
}

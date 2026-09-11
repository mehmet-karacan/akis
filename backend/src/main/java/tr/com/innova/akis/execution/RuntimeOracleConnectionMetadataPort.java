package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

interface RuntimeOracleConnectionMetadataPort {

    Optional<ConnectionProfile> find(PilotRuntimePlan.DatasetBinding binding);

    record ConnectionProfile(
            UUID projectUuid,
            UUID connectionVersionUuid,
            String mode,
            String jndiName,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            int port,
            String tlsMode,
            JsonNode policy,
            String secretProvider,
            String secretReferencePath) {

        public ConnectionProfile {
            policy = policy == null ? null : policy.deepCopy();
        }

        public ConnectionProfile(
                UUID projectUuid,
                UUID connectionVersionUuid,
                String driverReference,
                String host,
                String serviceName,
                String sid,
                int port,
                String tlsMode,
                JsonNode policy,
                String secretProvider,
                String secretReferencePath) {
            this(projectUuid, connectionVersionUuid, "JDBC", null,
                    driverReference, host, serviceName, sid, port, tlsMode,
                    policy, secretProvider, secretReferencePath);
        }

        @Override
        public JsonNode policy() {
            return policy == null ? null : policy.deepCopy();
        }
    }
}

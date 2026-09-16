package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

interface RunInputSnapshotStore {
    SnapshotClaim claim(SnapshotIdentity identity, UUID resolveToken);
    Snapshot finalizeSnapshot(UUID projectUuid, UUID runUuid, UUID resolveToken,
            long expectedVersion, ResolvedInput input);
    Optional<Snapshot> find(UUID projectUuid, UUID runUuid);

    record SnapshotIdentity(
            UUID projectUuid,
            UUID runUuid,
            UUID definitionVersionUuid,
            String releaseHash,
            String runtimePlanHash) { }

    record ResolvedInput(
            JsonNode variableVersions,
            JsonNode typedParameters,
            JsonNode resolvedVariables,
            JsonNode bindingVersionUuids,
            String sourceScn,
            String sourceSnapshotHash) { }

    record SnapshotClaim(UUID snapshotUuid, UUID resolveToken, long version,
            boolean alreadyComplete) { }

    record Snapshot(UUID snapshotUuid, UUID runUuid, String inputHash,
            String status, long version, ResolvedInput input) { }
}

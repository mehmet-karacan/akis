package tr.com.innova.akis.execution;

import java.util.UUID;
import java.util.Map;

import tr.com.innova.akis.discovery.SchemaFingerprintInput;

/**
 * Read-only control-plane port for the immutable schema bodies pinned into a
 * pilot runtime plan. Implementations must reject partial or cross-binding
 * matches instead of returning the closest snapshot by UUID.
 */
interface PinnedSchemaSnapshotPort {

    PinnedSnapshots load(MappingExecutionContract plan);

    record PinnedSnapshots(
            UUID projectUuid,
            UUID publicationUuid,
            PinnedSnapshot source,
            PinnedSnapshot target,
            Map<String, PinnedSnapshot> sources) {
        public PinnedSnapshots { sources = Map.copyOf(sources); }
        public PinnedSnapshots(UUID projectUuid, UUID publicationUuid, PinnedSnapshot source, PinnedSnapshot target) {
            this(projectUuid, publicationUuid, source, target, Map.of());
        }
    }

    record PinnedSnapshot(
            UUID schemaSnapshotUuid,
            String verifiedFingerprint,
            SchemaFingerprintInput body) {
    }
}

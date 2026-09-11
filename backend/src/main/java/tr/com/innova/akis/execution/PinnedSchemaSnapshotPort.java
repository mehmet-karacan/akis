package tr.com.innova.akis.execution;

import java.util.UUID;

import tr.com.innova.akis.discovery.SchemaFingerprintInput;

/**
 * Read-only control-plane port for the immutable schema bodies pinned into a
 * pilot runtime plan. Implementations must reject partial or cross-binding
 * matches instead of returning the closest snapshot by UUID.
 */
interface PinnedSchemaSnapshotPort {

    PinnedSnapshots load(PilotRuntimePlan plan);

    record PinnedSnapshots(
            UUID projectUuid,
            UUID publicationUuid,
            PinnedSnapshot source,
            PinnedSnapshot target) {
    }

    record PinnedSnapshot(
            UUID schemaSnapshotUuid,
            String verifiedFingerprint,
            SchemaFingerprintInput body) {
    }
}

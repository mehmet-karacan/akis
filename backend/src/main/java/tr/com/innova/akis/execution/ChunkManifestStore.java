package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

interface ChunkManifestStore {

    RecordedIntent record(Intent intent);
    boolean markDispatched(UUID chunkUuid, UUID runUuid, long runGeneration, String worker);
    boolean confirm(UUID chunkUuid, String targetReceiptReference, BigDecimal lastKey);
    boolean rollback(UUID chunkUuid, String errorCode);
    boolean unknown(UUID chunkUuid, String errorCode);
    Optional<RecordedIntent> find(UUID chunkUuid);

    record Intent(
            UUID projectUuid,
            UUID jobRequestUuid,
            String stepCode,
            UUID partitionUuid,
            UUID workspaceUuid,
            long sequence,
            String workUnitKey,
            String runtimePlanHash,
            String inputHash,
            BigDecimal lowerExclusive,
            BigDecimal upperInclusive,
            BigDecimal lastKey,
            String payloadHash,
            long rowCount,
            long byteCount) { }

    record RecordedIntent(
            UUID chunkUuid,
            Intent intent,
            String status,
            String targetReceiptReference) { }
}

package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

interface ExecutionChunkStore {

    ChunkPage list(UUID projectUuid, UUID runUuid, UUID stepUuid,
            long afterCursor, int size, String status);

    record ChunkPage(List<ChunkRow> items, Long nextCursor, boolean hasMore) {
        public ChunkPage { items = List.copyOf(items); }
    }

    record ChunkRow(
            long cursor,
            UUID uuid,
            long sequence,
            String partitionCode,
            BigDecimal lowerExclusive,
            BigDecimal upperInclusive,
            BigDecimal lastKey,
            String payloadHash,
            long rowCount,
            long byteCount,
            String status,
            String targetReceiptReference,
            OffsetDateTime createdAt) {
    }
}

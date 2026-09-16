package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ChunkExecutionCoordinatorTest {

    @Test
    void committedManifestSkipsSecondBusinessWrite() {
        FakeManifest manifest = new FakeManifest();
        manifest.initialStatus = "COMMIT_CONFIRMED";
        manifest.initialReceipt = "oracle-batch:verified";
        AtomicInteger writes = new AtomicInteger();
        var result = new ChunkExecutionCoordinator(manifest).execute(reader(), range(), context(),
                (evidence, batch) -> { writes.incrementAndGet(); throw new AssertionError(); },
                (evidence, receipt) -> receipt.equals("oracle-batch:verified"));

        assertEquals(0, writes.get());
        assertEquals(new BigDecimal("10"), result.lastCommittedKey());
    }

    @Test
    void postgresCommitProjectionCannotSkipWithoutTargetReceiptVerification() {
        FakeManifest manifest = new FakeManifest();
        manifest.initialStatus = "COMMIT_CONFIRMED";
        manifest.initialReceipt = "oracle-batch:unverified";

        assertThrows(ChunkReconciliationRequiredException.class,
                () -> new ChunkExecutionCoordinator(manifest).execute(reader(), range(), context(),
                        (evidence, batch) -> { throw new AssertionError(); },
                        (evidence, receipt) -> false));
    }

    @Test
    void unknownCommitStopsBeforeTheFollowingChunk() {
        FakeManifest manifest = new FakeManifest();
        AtomicInteger writes = new AtomicInteger();
        StreamingRowReader twoBatches = (range, consumer) -> {
            consumer.commit(batch("10", "b"));
            consumer.commit(batch("20", "c"));
            return new StreamingRowReader.ReadResult(2, 16, new BigDecimal("20"));
        };

        assertThrows(ChunkReconciliationRequiredException.class,
                () -> new ChunkExecutionCoordinator(manifest).execute(twoBatches, range(), context(),
                        (evidence, batch) -> {
                            writes.incrementAndGet();
                            return new OracleChunkWriteFacade.Result(
                                    OracleChunkWriteFacade.Outcome.OUTCOME_UNKNOWN, 0, 0);
                        }, (evidence, receipt) -> false));

        assertEquals(1, writes.get());
        assertEquals(List.of("OUTCOME_UNKNOWN"), manifest.terminalStates);
    }

    @Test
    void postgresCheckpointLossDoesNotCauseASecondWriteWithinTheCoordinator() {
        FakeManifest manifest = new FakeManifest();
        manifest.confirm = false;
        AtomicInteger writes = new AtomicInteger();

        assertThrows(ChunkCheckpointUnconfirmedException.class,
                () -> new ChunkExecutionCoordinator(manifest).execute(reader(), range(), context(),
                        (evidence, batch) -> {
                            writes.incrementAndGet();
                            return new OracleChunkWriteFacade.Result(
                                    OracleChunkWriteFacade.Outcome.COMMIT_CONFIRMED, 1, 8);
                        }, (evidence, receipt) -> false));

        assertEquals(1, writes.get());
    }

    private static StreamingRowReader reader() {
        return (range, consumer) -> {
            var batch = batch("10", "b");
            boolean committed = consumer.commit(batch);
            return new StreamingRowReader.ReadResult(committed ? 1 : 0, committed ? 8 : 0,
                    committed ? batch.lastKey() : range.lowerExclusive());
        };
    }

    private static StreamingRowReader.Batch batch(String key, String hashSeed) {
        return new StreamingRowReader.Batch(List.of(new StreamingRowReader.Row(
                new BigDecimal(key), List.of(new StreamingRowReader.Cell("NUMBER", key)), 8)),
                new BigDecimal(key), 8, hashSeed.repeat(64));
    }

    private static StreamingRowReader.Range range() {
        return new StreamingRowReader.Range(null, new BigDecimal("100"));
    }

    private static ChunkExecutionCoordinator.Context context() {
        return new ChunkExecutionCoordinator.Context(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "worker-1", "LOAD", UUID.randomUUID(), "P0",
                UUID.randomUUID(), "a".repeat(64), "d".repeat(64), 1);
    }

    private static final class FakeManifest implements ChunkManifestStore {
        private final Map<UUID, RecordedIntent> rows = new HashMap<>();
        private final List<String> terminalStates = new ArrayList<>();
        private String initialStatus = "INTENT_RECORDED";
        private String initialReceipt;
        private boolean confirm = true;

        public RecordedIntent record(Intent intent) {
            UUID id = UUID.randomUUID();
            RecordedIntent row = new RecordedIntent(id, intent, initialStatus, initialReceipt);
            rows.put(id, row); return row;
        }
        public boolean markDispatched(UUID id, UUID run, long generation, String worker) {
            rows.put(id, new RecordedIntent(id, rows.get(id).intent(), "DISPATCHED", null)); return true;
        }
        public boolean confirm(UUID id, String receipt, BigDecimal lastKey) {
            if (confirm) terminalStates.add("COMMIT_CONFIRMED"); return confirm;
        }
        public boolean rollback(UUID id, String code) { terminalStates.add("ROLLBACK_CONFIRMED"); return true; }
        public boolean unknown(UUID id, String code) { terminalStates.add("OUTCOME_UNKNOWN"); return true; }
        public Optional<RecordedIntent> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
    }
}

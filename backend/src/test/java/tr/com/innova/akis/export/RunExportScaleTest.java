package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.RunExportReader.EventRef;
import tr.com.innova.akis.export.RunExportReader.RunQuery;
import tr.com.innova.akis.export.RunExportReader.RunRef;
import tr.com.innova.akis.export.RunExportReader.StepRef;
import tr.com.innova.akis.knowledge.KmStepJournal;
import tr.com.innova.akis.knowledge.WorkObjectStore;

/** Opt-in synthetic heap/streaming gate; run with -Dakis.export.scale=true -DargLine=-Xmx128m. */
class RunExportScaleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID PROJECT = new UUID(1, 1);
    private static final UUID PUBLICATION = new UUID(2, 2);
    private static final OffsetDateTime TIME = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void streamsOneHundredThousandRunsAndThreeHundredThousandSteps() throws Exception {
        assumeTrue(Boolean.getBoolean("akis.export.scale"));
        assertTrue(Runtime.getRuntime().maxMemory() <= 160L * 1024 * 1024,
                "Scale gate must run in a constrained heap (-DargLine=-Xmx128m).");
        int count = Integer.getInteger("akis.export.scale.runs", 100_000);
        CountingReader reader = new CountingReader(count);
        RunExportProvider provider = new RunExportProvider(reader,
                new KmStepJournal(null, MAPPER), new WorkObjectStore(null), MAPPER);
        ExportContext context = new ExportContext(PROJECT, 1L, 1L, "runs", ExportScope.ALL,
                List.of(), List.of("status"), false, "tr-TR", "UTC",
                System.currentTimeMillis(), count);

        try (JsonExportWriter writer = JsonExportWriterImpl.builder(MAPPER)
                .outputStream(OutputStream.nullOutputStream()).build()) {
            provider.streamRecords(context, writer);
            writer.writeSummary(count, null, null, null, null);
        }

        assertEquals(count, reader.emittedRuns);
        assertEquals(3L * count, reader.emittedSteps);
        assertEquals((count + 15) / 16, reader.stepBatchCalls);
        assertEquals(0, reader.singleStepCalls);
        assertTrue(reader.maxBatchSize <= 16);
    }

    private static final class CountingReader extends RunExportReader {
        private final int count;
        long emittedRuns;
        long emittedSteps;
        int stepBatchCalls;
        int singleStepCalls;
        int maxBatchSize;

        CountingReader(int count) {
            super(null, MAPPER);
            this.count = count;
        }

        @Override
        void forEachRun(UUID projectUuid, RunQuery query, Consumer<RunRef> consumer) {
            for (int index = 1; index <= count; index++) {
                consumer.accept(new RunRef(new UUID(0, index), PUBLICATION, 1,
                        "MANUAL", "BASARILI", TIME, TIME, TIME));
                emittedRuns++;
            }
        }

        @Override
        void forEachStepBatch(UUID projectUuid, List<UUID> runUuids,
                BiConsumer<UUID, StepRef> consumer) {
            stepBatchCalls++;
            maxBatchSize = Math.max(maxBatchSize, runUuids.size());
            for (UUID runUuid : runUuids) {
                for (int ordinal = 1; ordinal <= 3; ordinal++) {
                    consumer.accept(runUuid, new StepRef(new UUID(ordinal, runUuid.getLeastSignificantBits()),
                            null, "STEP", "TASK", ordinal, "Step", "BASARILI", null, null,
                            TIME, TIME, 1L, null, null, "SELECT", null, null));
                    emittedSteps++;
                }
            }
        }

        @Override
        void forEachEventBatch(UUID projectUuid, List<UUID> runUuids,
                BiConsumer<UUID, EventRef> consumer) { }

        @Override
        void forEachStep(UUID projectUuid, UUID runUuid, Consumer<StepRef> consumer) {
            singleStepCalls++;
            throw new AssertionError("Unexpected per-run step query");
        }

        @Override
        void forEachEvent(UUID projectUuid, UUID runUuid, Consumer<EventRef> consumer) {
            throw new AssertionError("Unexpected per-run event query");
        }

        @Override
        Map<UUID, RunRef> findBatch(UUID projectUuid, List<UUID> runUuids) {
            throw new AssertionError("No child runs in this fixture");
        }
    }
}

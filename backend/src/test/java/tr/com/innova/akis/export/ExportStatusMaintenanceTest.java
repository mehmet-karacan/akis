package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.export.ExportModels.ExportJobRow;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.ExportModels.ExportStatus;

class ExportStatusMaintenanceTest {

    @TempDir
    Path tempDir;

    @Test
    void expiresRetainedJobsAndFailsOnlyWorkersOlderThanDuration() {
        RecordingRepository repository = new RecordingRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);
        ExportConfiguration configuration = new ExportConfiguration(tempDir, null, null, null, null, null, null);

        new ExportStatusMaintenance(repository, configuration, clock).poll();

        assertEquals(OffsetDateTime.parse("2026-09-27T10:00:00Z"), repository.expiryCutoff);
        assertEquals(OffsetDateTime.parse("2026-09-27T09:30:00Z"), repository.staleCutoff);
        assertEquals(OffsetDateTime.parse("2026-09-26T10:00:00Z"), repository.terminalCutoff);
    }

    @Test
    void deletesOnlyExpiredOutputsWithOwnedFilenames() throws IOException {
        RecordingRepository repository = new RecordingRepository();
        UUID uuid = UUID.randomUUID();
        String ownedName = "akis_runs_all_20260927T100000Z_" + uuid + "_v1.json";
        Path owned = tempDir.resolve(ownedName);
        Path unrelated = tempDir.resolve("unrelated.json");
        Files.writeString(owned, "export");
        Files.writeString(unrelated, "keep");
        repository.expiredOutputs = List.of(
                new ExportJobRepository.ExpiredOutput(1, uuid, ownedName),
                new ExportJobRepository.ExpiredOutput(2, uuid, "../unrelated.json"));
        Clock clock = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);

        new ExportStatusMaintenance(repository,
                new ExportConfiguration(tempDir, null, null, null, null, null, null), clock).poll();

        assertFalse(Files.exists(owned));
        assertTrue(Files.exists(unrelated));
        assertEquals(List.of(1L), repository.removedOutputIds);
    }

    @Test
    void removesOldTerminalTempButKeepsOldRunningAndUnownedFiles() throws IOException {
        RecordingRepository repository = new RecordingRepository();
        UUID terminalUuid = UUID.randomUUID();
        UUID runningUuid = UUID.randomUUID();
        repository.statuses.put(terminalUuid, ExportStatus.FAILED);
        repository.statuses.put(runningUuid, ExportStatus.RUNNING);
        Path terminalFile = tempDir.resolve("export-" + terminalUuid + "-abc.tmp");
        Path runningFile = tempDir.resolve("export-" + runningUuid + "-abc.tmp");
        Path unknownFile = tempDir.resolve("export-" + UUID.randomUUID() + "-abc.tmp");
        for (Path path : List.of(terminalFile, runningFile, unknownFile)) {
            Files.writeString(path, "temp");
            Files.setLastModifiedTime(path, FileTime.from(Instant.parse("2026-09-25T00:00:00Z")));
        }
        Clock clock = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);

        new ExportStatusMaintenance(repository,
                new ExportConfiguration(tempDir, null, null, null, null, null, null), clock).poll();

        assertFalse(Files.exists(terminalFile));
        assertTrue(Files.exists(runningFile));
        assertTrue(Files.exists(unknownFile));
    }

    private static final class RecordingRepository extends ExportJobRepository {
        private OffsetDateTime expiryCutoff;
        private OffsetDateTime staleCutoff;
        private OffsetDateTime terminalCutoff;
        private List<ExpiredOutput> expiredOutputs = List.of();
        private final java.util.List<Long> removedOutputIds = new java.util.ArrayList<>();
        private final Map<UUID, ExportStatus> statuses = new HashMap<>();

        private RecordingRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public int expireOlderThan(OffsetDateTime cutoff) {
            expiryCutoff = cutoff;
            return 0;
        }

        @Override
        public int failStaleRunning(OffsetDateTime startedBefore) {
            staleCutoff = startedBefore;
            return 0;
        }

        @Override
        public List<ExpiredOutput> listCleanupOutputs(OffsetDateTime terminalBefore, int limit) {
            terminalCutoff = terminalBefore;
            return expiredOutputs;
        }

        @Override
        public boolean removeCleanupOutput(ExpiredOutput output, OffsetDateTime terminalBefore) {
            removedOutputIds.add(output.outputId());
            return true;
        }

        @Override
        public Optional<ExportJobRow> findByUuid(UUID uuid) {
            ExportStatus status = statuses.get(uuid);
            if (status == null) return Optional.empty();
            OffsetDateTime now = OffsetDateTime.parse("2026-09-27T10:00:00Z");
            return Optional.of(new ExportJobRow(1, uuid, 1L, 1, "runs", "run-history",
                    ExportScope.ALL, null, List.of(), false, status, 0, 0, 0,
                    null, null, now.plusDays(1), now, now, null, now, 1));
        }
    }
}

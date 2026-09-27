package tr.com.innova.akis.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.export.ExportModels.ExportStatus;

/** Recovers jobs abandoned by a terminated worker and expires retained exports. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "akis.export.maintenance-enabled", havingValue = "true", matchIfMissing = true)
final class ExportStatusMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(ExportStatusMaintenance.class);
    private static final Pattern TEMP_NAME = Pattern.compile("export-([0-9a-f-]{36})-[^.]+\\.tmp");
    private static final int OUTPUT_BATCH_SIZE = 100;

    private final ExportJobRepository repository;
    private final ExportConfiguration configuration;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    ExportStatusMaintenance(ExportJobRepository repository, ExportConfiguration configuration) {
        this(repository, configuration, Clock.systemUTC());
    }

    ExportStatusMaintenance(ExportJobRepository repository, ExportConfiguration configuration, Clock clock) {
        this.repository = repository;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${akis.export.maintenance-delay-ms:300000}")
    void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            int expired = repository.expireOlderThan(now);
            int stale = repository.failStaleRunning(now.minus(configuration.maxDuration()));
            cleanTerminalOutputs(now.minus(configuration.retentionDuration()));
            cleanAbandonedTempFiles(now);
            if (expired != 0 || stale != 0) {
                LOG.info("Export status maintenance: expired={}, staleRunning={}", expired, stale);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Export status maintenance failed: {}", exception.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    private void cleanTerminalOutputs(OffsetDateTime terminalBefore) {
        Path spool = configuration.safeSpoolPath();
        if (Files.isSymbolicLink(spool)) return;
        for (ExportJobRepository.ExpiredOutput output : repository.listCleanupOutputs(
                terminalBefore, OUTPUT_BATCH_SIZE)) {
            String name = output.fileName();
            if (name == null || !name.matches("akis_[A-Za-z0-9_-]+_" + output.jobUuid() + "_v1\\.json")) {
                LOG.warn("Skipping export output with an invalid filename (outputId={})", output.outputId());
                continue;
            }
            Path file = spool.resolve(name);
            if (Files.isSymbolicLink(file)) continue;
            try {
                if (Files.deleteIfExists(file) || Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                    repository.removeCleanupOutput(output, terminalBefore);
                }
            } catch (IOException exception) {
                LOG.warn("Could not remove expired export file (outputId={}): {}",
                        output.outputId(), exception.getClass().getSimpleName());
            }
        }
    }

    private void cleanAbandonedTempFiles(OffsetDateTime now) {
        Path spool = configuration.safeSpoolPath();
        if (Files.isSymbolicLink(spool) || !Files.isDirectory(spool, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.list(spool)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> TEMP_NAME.matcher(path.getFileName().toString()).matches())
                    .filter(path -> oldEnough(path, now))
                    .filter(this::jobIsTerminal)
                    .limit(OUTPUT_BATCH_SIZE)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            LOG.warn("Could not remove abandoned export temp file: {}",
                                    exception.getClass().getSimpleName());
                        }
                    });
        } catch (IOException exception) {
            LOG.warn("Could not scan export spool: {}", exception.getClass().getSimpleName());
        }
    }

    private boolean oldEnough(Path path, OffsetDateTime now) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
                    .isBefore(now.toInstant().minus(configuration.retentionDuration()));
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean jobIsTerminal(Path path) {
        Matcher matcher = TEMP_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) return false;
        UUID jobUuid;
        try {
            jobUuid = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return repository.findByUuid(jobUuid)
                .map(job -> job.status() == ExportStatus.FAILED
                        || job.status() == ExportStatus.CANCELLED
                        || job.status() == ExportStatus.EXPIRED)
                .orElse(false);
    }
}

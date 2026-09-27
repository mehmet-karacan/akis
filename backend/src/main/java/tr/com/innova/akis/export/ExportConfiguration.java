package tr.com.innova.akis.export;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound configuration for export spool, limits and retention.
 */
@ConfigurationProperties(prefix = "akis.export")
public record ExportConfiguration(
        Path spoolPath,
        Long maxByteSize,
        Long maxRecords,
        Duration maxDuration,
        Duration retentionDuration,
        Integer maxConcurrentJobs,
        Integer jdbcFetchSize) {

    public ExportConfiguration {
        if (spoolPath == null) {
            spoolPath = Path.of(System.getProperty("java.io.tmpdir"), "akis-exports");
        }
        if (maxByteSize == null) {
            maxByteSize = 1L * 1024 * 1024 * 1024;
        }
        if (maxRecords == null) {
            maxRecords = 100_000L;
        }
        if (maxDuration == null) {
            maxDuration = Duration.ofMinutes(30);
        }
        if (retentionDuration == null) {
            retentionDuration = Duration.ofHours(24);
        }
        if (maxConcurrentJobs == null) {
            maxConcurrentJobs = 2;
        }
        if (jdbcFetchSize == null) {
            jdbcFetchSize = 1000;
        }
    }

    public Path safeSpoolPath() {
        return spoolPath.toAbsolutePath().normalize();
    }
}

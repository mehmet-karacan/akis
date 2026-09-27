package tr.com.innova.akis.export;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Immutable data-transfer objects for the job-based JSON export infrastructure.
 */
public final class ExportModels {

    private ExportModels() {
    }

    public enum ExportStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, EXPIRED
    }

    public enum ExportScope {
        VISIBLE, FILTERED, ALL, SELECTED
    }

    public record ExportColumn(
            String key,
            String label) {
    }

    public record ExportFilter(
            String field,
            String operator,
            JsonNode value) {
    }

    /**
     * Request to start an export job. The provider/resource combination is
     * allowlisted; the filter snapshot is stored verbatim and never mutated.
     */
    public record ExportRequest(
            String providerId,
            String resourceId,
            ExportScope scope,
            List<String> selectedColumns,
            List<ExportFilter> filters,
            boolean includeDetails,
            String locale,
            String timeZone) {
    }

    public record ExportJobRow(
            long id,
            UUID uuid,
            Long projectId,
            long creatorId,
            String providerId,
            String resourceId,
            ExportScope scope,
            JsonNode filterSnapshot,
            List<String> selectedColumns,
            boolean includeDetails,
            ExportStatus status,
            long processedRows,
            long resultRows,
            long byteSize,
            String errorCode,
            String errorMessage,
            OffsetDateTime expiryAt,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    public record ExportJobView(
            UUID uuid,
            String providerId,
            String resourceId,
            ExportScope scope,
            ExportStatus status,
            long processedRows,
            long resultRows,
            long byteSize,
            String errorCode,
            String errorMessage,
            OffsetDateTime expiryAt,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }

    public record ExportOutputRow(
            long id,
            long exportJobId,
            String filePath,
            String checksum,
            OffsetDateTime createdAt) {
    }

    /**
     * Context passed to a provider during streaming. It intentionally does not
     * expose raw connection credentials or mutable filter state.
     */
    public record ExportContext(
            UUID projectUuid,
            Long projectId,
            long creatorId,
            String resourceId,
            ExportScope scope,
            List<ExportFilter> filters,
            List<String> selectedColumns,
            boolean includeDetails,
            String locale,
            String timeZone,
            long startedAtEpochMilli,
            long maxRecords) {
    }
}

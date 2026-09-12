package tr.com.innova.akis.projectbundle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.metadata.DefinitionType;

public final class ProjectBundleModels {

    public static final String FORMAT = "akis.project-bundle";
    public static final int FORMAT_VERSION = 2;
    public static final int SCHEMA_VERSION = 2;

    private ProjectBundleModels() {
    }

    public record ProjectBundle(
            String format,
            int formatVersion,
            int schemaVersion,
            String checksum,
            OffsetDateTime exportedAt,
            ProjectEntry project,
            List<FolderEntry> folders,
            List<DefinitionEntry> definitions,
            TopologyEntry topology) {
    }

    public record ProjectEntry(
            String code,
            String status,
            String name,
            String description) {
    }

    /**
     * path and parentPath are portable stable references made from folder codes;
     * database identifiers and UUIDs deliberately never enter the bundle.
     */
    public record FolderEntry(
            String path,
            String parentPath,
            String code,
            String type,
            String status,
            String name,
            String description) {
    }

    /** A definition is identified in-bundle by the stable (type, code) pair. */
    public record DefinitionEntry(
            DefinitionType type,
            String code,
            String folderPath,
            String status,
            String name,
            String description,
            DraftEntry draft,
            List<VersionEntry> versions) {
    }

    public record DraftEntry(
            int schemaVersion,
            JsonNode content) {
    }

    public record VersionEntry(
            int versionNumber,
            int schemaVersion,
            String contentHash,
            JsonNode content,
            String description,
            OffsetDateTime createdAt) {
    }

    /** Portable design topology. Runtime test, discovery and secret values are excluded. */
    public record TopologyEntry(boolean sanitized, JsonNode definitions) {
    }

    public record BundleIssue(
            String path,
            String code,
            String message) {
    }

    public record BundleCounts(
            int folders,
            int definitions,
            int drafts,
            int versions) {
    }

    public record ValidationReport(
            boolean valid,
            BundleCounts counts,
            List<BundleIssue> issues) {
    }

    public enum ConflictPolicy {
        FAIL,
        RENAME,
        SKIP,
        NEW_VERSION
    }

    public record ImportResult(
            boolean imported,
            boolean dryRun,
            String projectCode,
            UUID projectUuid,
            BundleCounts counts) {
    }
}

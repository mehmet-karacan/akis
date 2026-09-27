package tr.com.innova.akis.projectbundle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

import tr.com.innova.akis.metadata.DefinitionType;

public final class ProjectBundleModels {

    public static final String FORMAT = "akis.project-bundle";
    public static final int FORMAT_VERSION = 3;
    public static final int SCHEMA_VERSION = 3;
    public static final int LEGACY_FORMAT_VERSION = 2;
    public static final int LEGACY_SCHEMA_VERSION = 2;
    public static final List<String> INCLUDED_SECTIONS = List.of(
            "project", "folders", "definitions", "topology", "publications", "schedules");

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
            TopologyEntry topology,
            ProducerEntry producer,
            List<String> includedSections,
            List<PublicationEntry> publications,
            List<ScheduleEntry> schedules) {

        public ProjectBundle(
                String format,
                int formatVersion,
                int schemaVersion,
                String checksum,
                OffsetDateTime exportedAt,
                ProjectEntry project,
                List<FolderEntry> folders,
                List<DefinitionEntry> definitions,
                TopologyEntry topology) {
            this(format, formatVersion, schemaVersion, checksum, exportedAt,
                    project, folders, definitions, topology,
                    formatVersion == FORMAT_VERSION
                            ? new ProducerEntry("akis-backend", "0.1.0-SNAPSHOT", "unknown")
                            : null,
                    formatVersion == FORMAT_VERSION ? INCLUDED_SECTIONS : null,
                    formatVersion == FORMAT_VERSION ? List.of() : null,
                    formatVersion == FORMAT_VERSION ? List.of() : null);
        }
    }

    public record ProducerEntry(
            String application,
            String version,
            String commit) {
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
            String status,
            String name,
            String description) {
    }

    /** A definition is identified in-bundle by the stable (type, code) pair. */
    @JsonSerialize(using = DefinitionEntrySerializer.class)
    public record DefinitionEntry(
            DefinitionType type,
            String code,
            String folderPath,
            String status,
            String name,
            String description,
            DraftEntry draft,
            List<VersionEntry> versions,
            UUID sourceUuid) {

        public DefinitionEntry(
                DefinitionType type,
                String code,
                String folderPath,
                String status,
                String name,
                String description,
                DraftEntry draft,
                List<VersionEntry> versions) {
            this(type, code, folderPath, status, name, description, draft, versions, null);
        }
    }

    public record DraftEntry(
            int schemaVersion,
            JsonNode content) {
    }

    @JsonSerialize(using = VersionEntrySerializer.class)
    public record VersionEntry(
            int versionNumber,
            int schemaVersion,
            String contentHash,
            JsonNode content,
            String description,
            OffsetDateTime createdAt,
            UUID sourceUuid) {

        public VersionEntry(
                int versionNumber,
                int schemaVersion,
                String contentHash,
                JsonNode content,
                String description,
                OffsetDateTime createdAt) {
            this(versionNumber, schemaVersion, contentHash, content, description, createdAt, null);
        }
    }

    static final class DefinitionEntrySerializer extends ValueSerializer<DefinitionEntry> {
        @Override
        public void serialize(
                DefinitionEntry value,
                JsonGenerator generator,
                SerializationContext provider) throws tools.jackson.core.JacksonException {
            generator.writeStartObject();
            generator.writeStringProperty("type", value.type().name());
            generator.writeStringProperty("code", value.code());
            if (value.folderPath() != null) {
                generator.writeStringProperty("folderPath", value.folderPath());
            }
            generator.writeStringProperty("status", value.status());
            generator.writeStringProperty("name", value.name());
            if (value.description() != null) {
                generator.writeStringProperty("description", value.description());
            }
            if (value.draft() != null) {
                generator.writeName("draft");
                provider.writeValue(generator, value.draft());
            }
            if (value.versions() != null) {
                generator.writeName("versions");
                provider.writeValue(generator, value.versions());
            }
            if (value.sourceUuid() != null) {
                generator.writeStringProperty("sourceUuid", value.sourceUuid().toString());
            }
            generator.writeEndObject();
        }
    }

    static final class VersionEntrySerializer extends ValueSerializer<VersionEntry> {
        @Override
        public void serialize(
                VersionEntry value,
                JsonGenerator generator,
                SerializationContext provider) throws tools.jackson.core.JacksonException {
            generator.writeStartObject();
            generator.writeNumberProperty("versionNumber", value.versionNumber());
            generator.writeNumberProperty("schemaVersion", value.schemaVersion());
            generator.writeStringProperty("contentHash", value.contentHash());
            generator.writeName("content");
            provider.writeValue(generator, value.content());
            if (value.description() != null) {
                generator.writeStringProperty("description", value.description());
            }
            generator.writeName("createdAt");
            provider.writeValue(generator, value.createdAt());
            if (value.sourceUuid() != null) {
                generator.writeStringProperty("sourceUuid", value.sourceUuid().toString());
            }
            generator.writeEndObject();
        }
    }

    /** Portable design topology. Runtime test, discovery and secret values are excluded. */
    public record TopologyEntry(boolean sanitized, JsonNode definitions) {
    }

    /**
     * A typed publication recipe: what design definition (at which version) should be
     * published where. No source ids/UUIDs, compiled runtime/scenario, approval, run,
     * credential or manifest data ever enters the bundle.
     */
    public record PublicationEntry(
            DefinitionType definitionType,
            String definitionCode,
            int definitionVersionNumber,
            String environmentCode) {
    }

    /**
     * A declarative schedule recipe. Runtime status, next/last fire time and run evidence
     * are deliberately excluded; only the trigger definition and its publication target
     * travel in the bundle.
     */
    public record ScheduleEntry(
            String code,
            String name,
            String cronExpression,
            String timeZone,
            String conflictPolicy,
            String misfirePolicy,
            String publicationPolicy,
            PublicationEntry publicationSelection,
            java.time.OffsetDateTime startsAt,
            java.time.OffsetDateTime endsAt) {
        public ScheduleEntry(String code, String name, String cronExpression, String timeZone,
                String conflictPolicy, String misfirePolicy, String publicationPolicy,
                PublicationEntry publicationSelection) {
            this(code, name, cronExpression, timeZone, conflictPolicy, misfirePolicy,
                    publicationPolicy, publicationSelection, null, null);
        }
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
            int versions,
            int publications,
            int schedules) {
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

    public enum GlobalResourceType {
        CONNECTION,
        PHYSICAL_SCHEMA,
        LOGICAL_SCHEMA,
        ENVIRONMENT
    }

    public enum GlobalBindingMode {
        BIND_EXISTING,
        CREATE_GLOBAL
    }

    /**
     * A source portable key must be bound explicitly. targetUuid is mandatory
     * for BIND_EXISTING; CREATE_GLOBAL is represented in the contract but may
     * require an administrator-supplied configuration before a plan is valid.
     */
    public record GlobalBinding(
            GlobalResourceType type,
            String sourceCode,
            GlobalBindingMode mode,
            UUID targetUuid,
            String newCode,
            JsonNode configuration) {
    }

    public record TargetPlanRequest(
            ProjectBundle bundle,
            List<GlobalBinding> globalBindings) {
    }

    public record TargetImportRequest(
            ProjectBundle bundle,
            List<GlobalBinding> globalBindings,
            String planDigest,
            long targetVersion) {
    }

    public record GlobalDependency(
            GlobalResourceType type,
            String sourceCode,
            String provider,
            boolean required,
            GlobalBindingMode mode,
            UUID targetUuid,
            String targetCode,
            boolean resolved,
            String message) {
    }

    public record TargetImportPlan(
            boolean valid,
            UUID targetProjectUuid,
            long targetVersion,
            String bundleChecksum,
            String planDigest,
            BundleCounts counts,
            Map<String, Integer> changes,
            List<GlobalDependency> globalDependencies,
            List<BundleIssue> issues) {
    }

    public record TargetImportResult(
            boolean imported,
            boolean replayed,
            UUID targetProjectUuid,
            long targetVersion,
            String bundleChecksum,
            String planDigest,
            BundleCounts counts) {
    }
}

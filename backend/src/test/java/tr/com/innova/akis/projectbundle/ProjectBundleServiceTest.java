package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ConflictPolicy;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DefinitionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DraftEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.FolderEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.VersionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DefinitionRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DraftRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ExportSnapshot;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.FolderRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ProjectRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.VersionRow;

class ProjectBundleServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dryRunValidatesButDoesNotWrite() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);

        var result = service.importBundle(validBundle(service), null, true);

        assertTrue(result.dryRun());
        assertFalse(result.imported());
        assertEquals(0, repository.writeCount);
        assertEquals("DEMO", result.projectCode());
    }

    @Test
    void importsFoldersByStablePathAndDefinitionsByTypeAndCode() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);

        var result = service.importBundle(validBundle(service), ConflictPolicy.FAIL, false);

        assertTrue(result.imported());
        assertNotNull(result.projectUuid());
        assertEquals(List.of("ROOT", "ROOT/CHILD"), repository.insertedFolderPaths);
        assertEquals(List.of("SEQUENCE:ORDER_SEQUENCE@ROOT/CHILD"), repository.insertedDefinitions);
        assertEquals(1, repository.insertedVersions);
        assertEquals(1, repository.insertedDrafts);
    }

    @Test
    void rejectsSecretValuesAndTamperedImmutableContent() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        JsonNode unsafe = json("""
                {"implementation":"REPOSITORY","start":1,"increment":1,"cycle":false,
                 "credentials":{"password":"do-not-import"}}
                """);
        VersionEntry version = new VersionEntry(
                1, 1, "0".repeat(64), unsafe, null, OffsetDateTime.now());
        ProjectBundle bundle = bundleWithDefinition(service, new DefinitionEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", "ROOT/CHILD", "AKTIF",
                "Order sequence", null, null, List.of(version)));

        var report = service.validate(bundle);

        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(
                issue -> issue.code().equals("SECRET_VALUE_FORBIDDEN")));
        assertThrows(ProjectBundleException.class,
                () -> service.importBundle(bundle, ConflictPolicy.FAIL, false));
        assertEquals(0, repository.writeCount);
    }

    @Test
    void exportRefusesToRewriteImmutableContentThatContainsSecrets() {
        StubRepository repository = new StubRepository(objectMapper);
        JsonNode stored = json("""
                {"implementation":"REPOSITORY","start":1,"increment":1,"cycle":false,
                 "apiKey":"must-not-leave","secretReferenceCode":"VAULT_ORDER"}
                """);
        repository.snapshot = new ExportSnapshot(
                new ProjectRow(7, UUID.randomUUID(), "DEMO", "AKTIF", "Demo", null),
                List.of(),
                List.of(new DefinitionRow(
                        8, null, DefinitionType.SEQUENCE, "ORDER_SEQUENCE",
                        "AKTIF", "Order sequence", null)),
                List.of(new DraftRow(8, 1, stored)),
                List.of(new VersionRow(
                        8, 1, 1, "f".repeat(64), stored, null, OffsetDateTime.now())));

        ProjectBundleException exception = assertThrows(ProjectBundleException.class,
                () -> service(repository).exportBundle(repository.snapshot.project().uuid()));

        assertEquals("SECRET_VALUE_FORBIDDEN", exception.code());
        assertTrue(exception.report().issues().stream().allMatch(
                issue -> issue.code().equals("SECRET_VALUE_FORBIDDEN")));
    }

    @Test
    void conflictPolicyFailsClosedAndImportIsTransactional() throws Exception {
        StubRepository repository = new StubRepository(objectMapper);
        repository.existingProjectCodes.add("DEMO");
        ProjectBundleService service = service(repository);

        ProjectBundleException exception = assertThrows(ProjectBundleException.class,
                () -> service.importBundle(validBundle(service), null, false));

        assertEquals("BUNDLE_CONFLICT", exception.code());
        assertEquals(0, repository.writeCount);
        assertNotNull(ProjectBundleService.class
                .getMethod("importBundle", ProjectBundle.class, ConflictPolicy.class, boolean.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void validateIsDatabaseIndependentAndRenameIsDeterministic() {
        StubRepository repository = new StubRepository(objectMapper);
        repository.existingProjectCodes.addAll(Set.of("DEMO", "DEMO_IMPORT_1"));
        ProjectBundleService service = service(repository);
        ProjectBundle bundle = validBundle(service);

        assertTrue(service.validate(bundle).valid());
        var dryRun = service.importBundle(bundle, ConflictPolicy.RENAME, true);

        assertEquals("DEMO_IMPORT_2", dryRun.projectCode());
        assertEquals(0, repository.writeCount);
    }

    @Test
    void checksumExcludesExportTimeButDetectsPayloadChanges() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle original = validBundle(service);
        ProjectBundle timeChanged = new ProjectBundle(
                original.format(), original.formatVersion(), original.schemaVersion(),
                original.checksum(), original.exportedAt().plusDays(1), original.project(),
                original.folders(), original.definitions(), original.topology());
        ProjectBundle nameChanged = new ProjectBundle(
                original.format(), original.formatVersion(), original.schemaVersion(),
                original.checksum(), original.exportedAt(),
                new ProjectEntry("DEMO", "AKTIF", "Changed", null),
                original.folders(), original.definitions(), original.topology());

        assertTrue(service.validate(timeChanged).valid());
        assertTrue(service.validate(nameChanged).issues().stream().anyMatch(
                issue -> issue.code().equals("BUNDLE_CHECKSUM_MISMATCH")));
    }

    @Test
    void rejectsDefinitionJsonBeyondDepthLimit() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        var content = objectMapper.createObjectNode();
        content.put("implementation", "REPOSITORY");
        content.put("start", 1);
        content.put("increment", 1);
        content.put("cycle", false);
        var cursor = content.putObject("metadata");
        for (int index = 0; index <= ProjectBundleService.MAX_JSON_DEPTH; index++) {
            cursor = cursor.putObject("child");
        }
        VersionEntry version = new VersionEntry(
                1, 1, "0".repeat(64), content, null, OffsetDateTime.now());
        ProjectBundle unsigned = unsignedBundle(new DefinitionEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", "ROOT/CHILD", "AKTIF",
                "Order sequence", null, null, List.of(version)));
        ProjectBundle bundle = sign(service, unsigned);

        assertTrue(service.validate(bundle).issues().stream().anyMatch(
                issue -> issue.code().equals("JSON_DEPTH_LIMIT_EXCEEDED")));
    }

    @Test
    void rejectsMissingRequiredCollectionsAndMarkersBeforeImport() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle unsigned = new ProjectBundle(
                ProjectBundleModels.FORMAT, 1, 1, null, null,
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                null, null, null);
        ProjectBundle bundle = sign(service, unsigned);

        var report = service.validate(bundle);

        Set<String> codes = report.issues().stream()
                .map(issue -> issue.code()).collect(java.util.stream.Collectors.toSet());
        assertFalse(report.valid());
        assertTrue(codes.containsAll(Set.of(
                "EXPORTED_AT_REQUIRED", "FOLDERS_REQUIRED",
                "DEFINITIONS_REQUIRED", "TOPOLOGY_REQUIRED")));
        assertThrows(ProjectBundleException.class,
                () -> service.importBundle(bundle, ConflictPolicy.FAIL, false));
        assertEquals(0, repository.writeCount);
    }

    @Test
    void rejectsCredentialClausesInsideScalarSql() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        JsonNode unsafe = json("""
                {"tasks":[{"id":"LINK","type":"SQL","connectionRole":"TARGET",
                 "riskClass":"DDL","requiresApproval":true,
                 "command":"CREATE DATABASE LINK X CONNECT TO U IDENTIFIED BY cleartext"}]}
                """);
        VersionEntry version = new VersionEntry(
                1, 1, "0".repeat(64), unsafe, null, OffsetDateTime.now());
        ProjectBundle bundle = bundleWithDefinition(service, new DefinitionEntry(
                DefinitionType.PROCEDURE, "CREATE_LINK", "ROOT/CHILD", "AKTIF",
                "Create link", null, null, List.of(version)));

        assertTrue(service.validate(bundle).issues().stream().anyMatch(
                issue -> issue.code().equals("SECRET_VALUE_FORBIDDEN")));
    }

    @Test
    void rejectsSqlPlusUserPasswordConnectionSyntax() {
        SecretValueSanitizer sanitizer = new SecretValueSanitizer();

        assertEquals(List.of("$.command"), sanitizer.sensitivePaths(
                json("{\"command\":\"CONNECT app/Sup3rSecret@DB\"}")));
    }

    private ProjectBundleService service(StubRepository repository) {
        SecretValueSanitizer sanitizer = new SecretValueSanitizer();
        return new ProjectBundleService(
                repository, new DefinitionContentValidator(), sanitizer, objectMapper);
    }

    private ProjectBundle validBundle(ProjectBundleService service) {
        JsonNode content = json("""
                {"implementation":"REPOSITORY","start":1,"increment":1,"cycle":false}
                """);
        VersionEntry version = new VersionEntry(
                1, 1, hash("""
                        {"cycle":false,"implementation":"REPOSITORY","increment":1,"start":1}
                        """.trim()), content, "Initial", OffsetDateTime.now());
        return bundleWithDefinition(service, new DefinitionEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", "ROOT/CHILD", "AKTIF",
                "Order sequence", null, new DraftEntry(1, content), List.of(version)));
    }

    private ProjectBundle bundleWithDefinition(
            ProjectBundleService service, DefinitionEntry definition) {
        return sign(service, unsignedBundle(definition));
    }

    private ProjectBundle unsignedBundle(DefinitionEntry definition) {
        return new ProjectBundle(
                ProjectBundleModels.FORMAT,
                ProjectBundleModels.FORMAT_VERSION,
                ProjectBundleModels.SCHEMA_VERSION,
                null,
                OffsetDateTime.now(),
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                List.of(
                        new FolderEntry(
                                "ROOT/CHILD", "ROOT", "CHILD", "GELISTIRME",
                                "AKTIF", "Child", null),
                        new FolderEntry(
                                "ROOT", null, "ROOT", "GELISTIRME",
                                "AKTIF", "Root", null)),
                List.of(definition),
                new TopologyEntry(true));
    }

    private ProjectBundle sign(ProjectBundleService service, ProjectBundle bundle) {
        return new ProjectBundle(
                bundle.format(), bundle.formatVersion(), bundle.schemaVersion(),
                service.checksum(bundle), bundle.exportedAt(), bundle.project(),
                bundle.folders(), bundle.definitions(), bundle.topology());
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class StubRepository extends ProjectBundleRepository {

        private int writeCount;
        private final Set<String> existingProjectCodes = new HashSet<>();
        private ExportSnapshot snapshot;
        private final List<String> insertedFolderPaths = new ArrayList<>();
        private final List<String> insertedDefinitions = new ArrayList<>();
        private long nextId = 100;
        private Long rootId;
        private Long childId;
        private int insertedDrafts;
        private int insertedVersions;

        private StubRepository(ObjectMapper objectMapper) {
            super(null, objectMapper);
        }

        @Override
        boolean projectCodeExists(String code) {
            return existingProjectCodes.contains(code);
        }

        @Override
        void lockProjectCodeNamespace() {
        }

        @Override
        ExportSnapshot loadSnapshot(UUID projectUuid) {
            return snapshot;
        }

        @Override
        ProjectRow insertProject(
                UUID uuid, String code, String status, String name, String description) {
            writeCount++;
            return new ProjectRow(9, uuid, code, status, name, description);
        }

        @Override
        long insertFolder(
                long projectId, Long parentId, String code, String type,
                String status, String name, String description) {
            writeCount++;
            long id = nextId++;
            if (parentId == null) {
                assertEquals("ROOT", code);
                rootId = id;
                insertedFolderPaths.add("ROOT");
            }
            else {
                assertEquals(rootId, parentId);
                childId = id;
                insertedFolderPaths.add("ROOT/CHILD");
            }
            return id;
        }

        @Override
        long insertDefinition(
                long projectId, Long folderId, DefinitionType type, String code,
                String status, String name, String description) {
            writeCount++;
            assertEquals(childId, folderId);
            insertedDefinitions.add(type.name() + ":" + code + "@ROOT/CHILD");
            return nextId++;
        }

        @Override
        void insertDraft(long definitionId, int schemaVersion, JsonNode content) {
            writeCount++;
            insertedDrafts++;
        }

        @Override
        void insertVersion(long definitionId, VersionRow version) {
            writeCount++;
            insertedVersions++;
        }
    }
}

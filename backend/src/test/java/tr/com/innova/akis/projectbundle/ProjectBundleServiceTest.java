package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ConflictPolicy;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DefinitionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.DraftEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.FolderEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.PublicationEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ScheduleEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TargetImportRequest;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.VersionEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DefinitionRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.DraftRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ExportSnapshot;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.FolderRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ProjectRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.ImportReceiptRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.VersionRow;
import tr.com.innova.akis.projectbundle.ProjectBundleRepository.GlobalResourceRow;

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
    void mappingSchemaVersionTwoRoundTripsThroughBundleValidation() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        JsonNode content = json("""
                {"columnMappings":[{"source":{"column":"ID","dataset":"SOURCE_1"},
                "target":{"column":"ID","dataset":"TARGET_1"}}],
                "datasets":[{"id":"SOURCE_1","role":"SOURCE"},{"id":"TARGET_1","role":"TARGET"}],
                "writeStrategy":{"kind":"ATOMIC_DELETE_INSERT"}}
                """);
        VersionEntry version = new VersionEntry(
                1, 2, hash(content.toString()), content, "Pilot", OffsetDateTime.now());
        ProjectBundle bundle = bundleWithDefinition(service, new DefinitionEntry(
                DefinitionType.MAPPING, "PILOT_MAPPING", "ROOT/CHILD", "AKTIF",
                "Pilot mapping", null, null, List.of(version)));

        assertTrue(service.validate(bundle).valid());
        assertTrue(service.importBundle(bundle, ConflictPolicy.FAIL, true).dryRun());
        assertEquals(0, repository.writeCount);
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
                new ProjectRow(7, UUID.randomUUID(), "DEMO", 1, "AKTIF", "Demo", null),
                List.of(),
                List.of(new DefinitionRow(
                        8, null, DefinitionType.SEQUENCE, "ORDER_SEQUENCE",
                        "AKTIF", "Order sequence", null)),
                List.of(new DraftRow(8, 1, stored)),
                List.of(new VersionRow(
                        8, 1, 1, "f".repeat(64), stored, null, OffsetDateTime.now())),
                List.of(), List.of());

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
    void readsLegacyV2ButRejectsUnknownMajorVersions() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle current = validBundle(service);
        ProjectBundle legacyUnsigned = new ProjectBundle(
                current.format(), ProjectBundleModels.LEGACY_FORMAT_VERSION,
                ProjectBundleModels.LEGACY_SCHEMA_VERSION, null, current.exportedAt(),
                current.project(), current.folders(), current.definitions(), current.topology(),
                null, null, null, null);
        ProjectBundle legacy = new ProjectBundle(
                legacyUnsigned.format(), legacyUnsigned.formatVersion(),
                legacyUnsigned.schemaVersion(), service.checksum(legacyUnsigned),
                legacyUnsigned.exportedAt(), legacyUnsigned.project(), legacyUnsigned.folders(),
                legacyUnsigned.definitions(), legacyUnsigned.topology(), null, null, null, null);

        assertTrue(service.validate(legacy).valid());

        ProjectBundle unknown = new ProjectBundle(
                current.format(), 4, 4, current.checksum(), current.exportedAt(),
                current.project(), current.folders(), current.definitions(), current.topology(),
                current.producer(), current.includedSections(),
                current.publications(), current.schedules());
        assertTrue(service.validate(unknown).issues().stream().anyMatch(
                issue -> issue.code().equals("UNSUPPORTED_FORMAT_VERSION")));
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
    void checksumDetectsPublicationAndScheduleChanges() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle original = validBundle(service);
        PublicationEntry publication = new PublicationEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, "PROD");
        ProjectBundle changedPublications = new ProjectBundle(
                original.format(), original.formatVersion(), original.schemaVersion(),
                original.checksum(), original.exportedAt(), original.project(),
                original.folders(), original.definitions(), original.topology(),
                original.producer(), original.includedSections(),
                List.of(publication), original.schedules());

        assertTrue(service.validate(original).valid());
        assertTrue(service.validate(changedPublications).issues().stream().anyMatch(
                issue -> issue.code().equals("BUNDLE_CHECKSUM_MISMATCH")));
    }

    @Test
    void requiresPublicationsAndSchedulesArraysForVersionThreeBundles() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle base = validBundle(service);
        ProjectBundle missingSections = new ProjectBundle(
                base.format(), base.formatVersion(), base.schemaVersion(), base.checksum(),
                base.exportedAt(), base.project(), base.folders(), base.definitions(),
                base.topology(), base.producer(), base.includedSections(), null, null);

        var report = service.validate(missingSections);

        Set<String> codes = report.issues().stream()
                .map(issue -> issue.code()).collect(java.util.stream.Collectors.toSet());
        assertFalse(report.valid());
        assertTrue(codes.containsAll(Set.of("PUBLICATIONS_REQUIRED", "SCHEDULES_REQUIRED")));
    }

    @Test
    void rejectsPublicationsAndSchedulesWithInvalidShapeOrUnresolvedReferences() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle base = validBundle(service);

        PublicationEntry unresolvedDefinition = new PublicationEntry(
                DefinitionType.SEQUENCE, "MISSING_DEFINITION", 1, "PROD");
        PublicationEntry unresolvedVersion = new PublicationEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 99, "PROD");
        PublicationEntry duplicateA = new PublicationEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, "PROD");
        PublicationEntry duplicateB = new PublicationEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, "PROD");
        PublicationEntry notInPublicationsList = new PublicationEntry(
                DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, "STAGING");

        ScheduleEntry badCron = new ScheduleEntry(
                "BAD_CRON", "Bad Cron", "not a cron", "Europe/Istanbul",
                "SKIP", "SKIP", "LATEST_ACTIVE", duplicateA);
        ScheduleEntry badZone = new ScheduleEntry(
                "BAD_ZONE", "Bad Zone", "0 0 * * * *", "Not/AZone",
                "SKIP", "SKIP", "LATEST_ACTIVE", duplicateA);
        ScheduleEntry badPolicy = new ScheduleEntry(
                "BAD_POLICY", "Bad Policy", "0 0 * * * *", "Europe/Istanbul",
                "MAYBE", "SKIP", "LATEST_ACTIVE", duplicateA);
        ScheduleEntry unresolvedSelection = new ScheduleEntry(
                "BAD_SELECTION", "Bad Selection", "0 0 * * * *", "Europe/Istanbul",
                "SKIP", "SKIP", "LATEST_ACTIVE", notInPublicationsList);

        ProjectBundle unsigned = new ProjectBundle(
                base.format(), base.formatVersion(), base.schemaVersion(), null,
                base.exportedAt(), base.project(), base.folders(), base.definitions(),
                base.topology(), base.producer(), base.includedSections(),
                List.of(unresolvedDefinition, unresolvedVersion, duplicateA, duplicateB),
                List.of(badCron, badZone, badPolicy, unresolvedSelection));
        ProjectBundle bundle = signFull(service, unsigned);

        var report = service.validate(bundle);

        Set<String> codes = report.issues().stream()
                .map(issue -> issue.code()).collect(java.util.stream.Collectors.toSet());
        assertFalse(report.valid());
        assertTrue(codes.containsAll(Set.of(
                "PUBLICATION_DEFINITION_UNRESOLVED", "PUBLICATION_VERSION_UNRESOLVED",
                "DUPLICATE_PUBLICATION", "INVALID_CRON_EXPRESSION", "INVALID_TIME_ZONE",
                "INVALID_CONFLICT_POLICY", "PUBLICATION_SELECTION_UNRESOLVED")));
    }

    @Test
    void exportIncludesPublicationAndScheduleRecipesWithoutSourceIdentifiers() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        JsonNode content = json("""
                {"implementation":"REPOSITORY","start":1,"increment":1,"cycle":false}
                """);
        String contentHash = hash(service.canonical(content).toString());
        repository.snapshot = new ExportSnapshot(
                new ProjectRow(7, UUID.randomUUID(), "DEMO", 1, "AKTIF", "Demo", null),
                List.of(),
                List.of(new DefinitionRow(
                        8, null, DefinitionType.SEQUENCE, "ORDER_SEQUENCE",
                        "AKTIF", "Order sequence", null)),
                List.of(),
                List.of(new VersionRow(8, 1, 1, contentHash, content, null, OffsetDateTime.now())),
                List.of(new ProjectBundleRepository.PublicationRow(
                        DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, "PROD")),
                List.of(new ProjectBundleRepository.ScheduleRow(
                        "DAILY_RUN", "Daily Run", "0 0 * * * *", "Europe/Istanbul",
                        "SKIP", "SKIP", "LATEST_ACTIVE",
                        DefinitionType.SEQUENCE, "ORDER_SEQUENCE", 1, "PROD")));

        ProjectBundle bundle = service.exportBundle(repository.snapshot.project().uuid());

        assertEquals(
                List.of("project", "folders", "definitions", "topology", "publications", "schedules"),
                bundle.includedSections());
        assertEquals(1, bundle.publications().size());
        PublicationEntry publication = bundle.publications().get(0);
        assertEquals(DefinitionType.SEQUENCE, publication.definitionType());
        assertEquals("ORDER_SEQUENCE", publication.definitionCode());
        assertEquals(1, publication.definitionVersionNumber());
        assertEquals("PROD", publication.environmentCode());

        assertEquals(1, bundle.schedules().size());
        ScheduleEntry schedule = bundle.schedules().get(0);
        assertEquals("DAILY_RUN", schedule.code());
        assertEquals("SKIP", schedule.conflictPolicy());
        assertEquals("SKIP", schedule.misfirePolicy());
        assertEquals("LATEST_ACTIVE", schedule.publicationPolicy());
        assertEquals(publication, schedule.publicationSelection());

        assertTrue(service.validate(bundle).valid());
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

    @Test
    void rejectsOracleThinInlineCredentialsForServiceAndSidSyntax() {
        SecretValueSanitizer sanitizer = new SecretValueSanitizer();

        assertEquals(List.of("$.jdbcUrl"), sanitizer.sensitivePaths(json(
                "{\"jdbcUrl\":\"jdbc:oracle:thin:app/Sup3rSecret@//db:1521/service\"}")));
        assertEquals(List.of("$.endpoint"), sanitizer.sensitivePaths(json(
                "{\"endpoint\":\"jdbc:oracle:thin:app/Sup3rSecret@db:1521:SID\"}")));
    }

    @Test
    void plansAndImportsIntoAnExistingEmptyTargetIdempotently() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle bundle = validBundle(service);

        var plan = service.planTargetImport(
                repository.target.uuid(), bundle, List.of());

        assertTrue(plan.valid());
        assertEquals(1, plan.targetVersion());
        assertEquals(0, repository.writeCount);

        var request = new TargetImportRequest(
                bundle, List.of(), plan.planDigest(), plan.targetVersion());
        var imported = service.importIntoTarget(
                repository.target.uuid(), request, "bundle-test-0001", 1);
        var replayed = service.importIntoTarget(
                repository.target.uuid(), request, "bundle-test-0001", 1);

        assertTrue(imported.imported());
        assertFalse(imported.replayed());
        assertEquals(2, imported.targetVersion());
        assertEquals(2, repository.receipt.targetVersion());
        assertTrue(replayed.replayed());
        assertEquals(imported.planDigest(), replayed.planDigest());
    }

    @Test
    void rejectsIdempotencyKeyReuseForAnotherPlan() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle bundle = validBundle(service);
        var plan = service.planTargetImport(repository.target.uuid(), bundle, List.of());
        var request = new TargetImportRequest(
                bundle, List.of(), plan.planDigest(), plan.targetVersion());
        service.importIntoTarget(
                repository.target.uuid(), request, "bundle-test-0002", 1);

        var changed = new TargetImportRequest(
                bundle, List.of(), "f".repeat(64), plan.targetVersion());
        ProjectBundleException exception = assertThrows(
                ProjectBundleException.class,
                () -> service.importIntoTarget(
                        repository.target.uuid(), changed, "bundle-test-0002", 1));

        assertEquals("IDEMPOTENCY_KEY_REUSED", exception.code());
    }

    @Test
    void packageDefinitionUuidIsRemappedOnImport() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID sourceDefUuid = UUID.randomUUID();
        UUID targetDefUuid = UUID.randomUUID();
        repository.definitionUuidByCode.put(
                DefinitionType.PACKAGE.name() + ":REFERENCED_PKG", targetDefUuid);
        JsonNode content = json("""
                {"firstStepId":"s1","steps":[{"id":"s1","type":"PROCEDURE","definitionUuid":"%s"}],"transitions":[]}
                """.formatted(sourceDefUuid));
        VersionEntry version = new VersionEntry(
                1, 1, hash(service.canonical(content).toString()), content, null, OffsetDateTime.now());
        DefinitionEntry referenced = new DefinitionEntry(
                DefinitionType.PACKAGE, "REFERENCED_PKG", "ROOT/CHILD", "AKTIF",
                "Referenced", null, null, List.of(), sourceDefUuid);
        DefinitionEntry importer = new DefinitionEntry(
                DefinitionType.PACKAGE, "IMPORTER", "ROOT/CHILD", "AKTIF",
                "Importer", null, null, List.of(version));
        ProjectBundle bundle = sign(service, unsignedBundleWithDefinitions(referenced, importer));

        var result = service.importBundle(bundle, ConflictPolicy.FAIL, false);

        assertTrue(result.imported());
        assertEquals(1, repository.capturedVersions.size());
        JsonNode imported = repository.capturedVersions.get(0).content();
        assertEquals(targetDefUuid.toString(), imported.path("steps").path(0).path("definitionUuid").asString());
        String expectedHash = hash(service.canonical(imported).toString());
        assertEquals(expectedHash, repository.capturedVersions.get(0).contentHash());
    }

    @Test
    void loadPlanScenarioVersionUuidIsRemappedOnImport() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID sourceVersionUuid = UUID.randomUUID();
        JsonNode content = json("""
                {"steps":[{"id":"s1","type":"SCENARIO","scenarioVersionUuid":"%s"}],"restartPolicy":"FAILED_STEP"}
                """.formatted(sourceVersionUuid));
        VersionEntry version = new VersionEntry(
                1, 1, hash(service.canonical(content).toString()), content, null, OffsetDateTime.now(), sourceVersionUuid);
        JsonNode scenarioContent = json("""
                {"implementation":"REPOSITORY","start":1,"increment":1,"cycle":false}
                """);
        VersionEntry scenarioVersion = new VersionEntry(
                1, 1, hash(service.canonical(scenarioContent).toString()),
                scenarioContent, null, OffsetDateTime.now(), sourceVersionUuid);
        DefinitionEntry scenario = new DefinitionEntry(
                DefinitionType.SEQUENCE, "SCENARIO_VERSION", "ROOT/CHILD", "AKTIF",
                "Scenario Version", null, null, List.of(scenarioVersion));
        DefinitionEntry definition = new DefinitionEntry(
                DefinitionType.LOAD_PLAN, "LP", "ROOT/CHILD", "AKTIF",
                "Load Plan", null, null, List.of(version));
        ProjectBundle bundle = sign(service, unsignedBundleWithDefinitions(scenario, definition));

        var result = service.importBundle(bundle, ConflictPolicy.FAIL, false);

        assertTrue(result.imported());
        VersionRow importedLoadPlan = repository.capturedVersions.stream()
                .filter(item -> item.content().path("steps").path(0)
                        .has("scenarioVersionUuid"))
                .findFirst().orElseThrow();
        VersionRow importedScenario = repository.capturedVersions.stream()
                .filter(item -> item.content().path("implementation").asText().equals("REPOSITORY"))
                .findFirst().orElseThrow();
        assertEquals(importedScenario.uuid().toString(),
                importedLoadPlan.content().path("steps").path(0)
                        .path("scenarioVersionUuid").asString());
    }

    @Test
    void variableRefreshQueryRejectedWithoutLogicalSchemaBinding() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID sourceLogicalSchemaUuid = UUID.randomUUID();
        JsonNode content = json("""
                {"dataType":"STRING","scope":"PROJECT","historyMode":"NONE","valueSource":"REFRESH_QUERY","logicalSchemaUuid":"%s","query":"select 1"}
                """.formatted(sourceLogicalSchemaUuid));
        VersionEntry version = new VersionEntry(
                1, 1, hash(service.canonical(content).toString()), content, null, OffsetDateTime.now());
        DefinitionEntry definition = new DefinitionEntry(
                DefinitionType.VARIABLE, "VAR", "ROOT/CHILD", "AKTIF",
                "Variable", null, null, List.of(version));
        ProjectBundle bundle = sign(service, unsignedBundle(definition));

        ProjectBundleException exception = assertThrows(ProjectBundleException.class,
                () -> service.importBundle(bundle, ConflictPolicy.FAIL, false));

        assertEquals("BUNDLE_IMPORT_REFERENCE_UNRESOLVED", exception.code());
        assertTrue(exception.report().issues().stream().anyMatch(
                issue -> issue.code().equals("VARIABLE_LOGICAL_SCHEMA_REFERENCE_UNRESOLVED")));
        assertEquals(0, repository.writeCount);
    }

    @Test
    void variableRefreshQueryRemappedWithLogicalSchemaBinding() {
        StubRepository repository = new StubRepository(objectMapper);
        repository.globalResourceCode = "LS_TARGET";
        ProjectBundleService service = service(repository);
        UUID sourceLogicalSchemaUuid = UUID.randomUUID();
        UUID targetLogicalSchemaUuid = UUID.randomUUID();
        JsonNode content = json("""
                {"dataType":"STRING","scope":"PROJECT","historyMode":"NONE","valueSource":"REFRESH_QUERY","logicalSchemaUuid":"%s","query":"select 1"}
                """.formatted(sourceLogicalSchemaUuid));
        VersionEntry version = new VersionEntry(
                1, 1, hash(service.canonical(content).toString()), content, null, OffsetDateTime.now());
        DefinitionEntry definition = new DefinitionEntry(
                DefinitionType.VARIABLE, "VAR", "ROOT/CHILD", "AKTIF",
                "Variable", null, null, List.of(version));
        ProjectBundle bundle = sign(service, unsignedBundle(definition));
        JsonNode topology = topologyWithLogicalSchema("LS", sourceLogicalSchemaUuid);
        bundle = new ProjectBundle(
                bundle.format(), bundle.formatVersion(), bundle.schemaVersion(),
                service.checksum(new ProjectBundle(
                        bundle.format(), bundle.formatVersion(), bundle.schemaVersion(), null,
                        bundle.exportedAt(), bundle.project(), bundle.folders(), bundle.definitions(),
                        new TopologyEntry(true, topology))),
                bundle.exportedAt(), bundle.project(), bundle.folders(), bundle.definitions(),
                new TopologyEntry(true, topology));

        var plan = service.planTargetImport(
                repository.target.uuid(), bundle,
                List.of(new GlobalBinding(
                        GlobalResourceType.LOGICAL_SCHEMA, "LS",
                        GlobalBindingMode.BIND_EXISTING, targetLogicalSchemaUuid, null, null)));

        assertTrue(plan.valid());
        var request = new TargetImportRequest(
                bundle,
                List.of(new GlobalBinding(
                        GlobalResourceType.LOGICAL_SCHEMA, "LS",
                        GlobalBindingMode.BIND_EXISTING, targetLogicalSchemaUuid, null, null)),
                plan.planDigest(), plan.targetVersion());
        var result = service.importIntoTarget(
                repository.target.uuid(), request, "bundle-test-variable", 1);

        assertTrue(result.imported());
        JsonNode imported = repository.capturedVersions.get(0).content();
        assertEquals(targetLogicalSchemaUuid.toString(), imported.path("logicalSchemaUuid").asString());
    }

    @Test
    void mappingV4ObjectReferenceFailsClosed() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID dataObjectUuid = UUID.randomUUID();
        UUID snapshotUuid = UUID.randomUUID();
        JsonNode content = json("""
                {"sources":[{"id":"SRC","alias":"S","dataObjectUuid":"%s","schemaSnapshotUuid":"%s"}],
                "target":{"id":"TGT","alias":"T","dataObjectUuid":"%s","schemaSnapshotUuid":"%s"},
                "joins":[],"filters":[],"columnMappings":[{"source":{"object":"SRC","column":"ID"},"target":{"object":"TGT","column":"ID"}}],
                "modules":{"loading":{"versionUuid":"%s","contentHash":"%s"},"integration":{"versionUuid":"%s","contentHash":"%s"}},
                "options":{"batchRows":100,"fetchRows":100,"maxRows":100000,"maxBytes":1048576,"allowEmptySource":false}}
                """.formatted(dataObjectUuid, snapshotUuid, dataObjectUuid, snapshotUuid,
                        UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), "b".repeat(64)));
        VersionEntry version = new VersionEntry(
                1, 4, hash(service.canonical(content).toString()), content, null, OffsetDateTime.now());
        DefinitionEntry definition = new DefinitionEntry(
                DefinitionType.MAPPING, "MAP_V4", "ROOT/CHILD", "AKTIF",
                "Mapping v4", null, null, List.of(version));
        ProjectBundle bundle = sign(service, unsignedBundle(definition));

        ProjectBundleException exception = assertThrows(ProjectBundleException.class,
                () -> service.importBundle(bundle, ConflictPolicy.FAIL, false));

        assertEquals("BUNDLE_IMPORT_REFERENCE_UNRESOLVED", exception.code());
        assertTrue(exception.report().issues().stream().anyMatch(
                issue -> issue.code().equals("MAPPING_V4_SCHEMA_SNAPSHOT_REFERENCE_UNRESOLVED")));
        assertEquals(0, repository.writeCount);
    }

    @Test
    void mappingV4ModulePinsUseTargetKnowledgeModuleVersionUuid() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID dataObjectUuid = UUID.randomUUID();
        UUID snapshotUuid = UUID.randomUUID();
        repository.portableTopologyResult = new ProjectBundleRepository.PortableTopologyImportResult(
                Map.of(dataObjectUuid, dataObjectUuid), Map.of(snapshotUuid, snapshotUuid));

        UUID sourceModuleVersionUuid = UUID.randomUUID();
        JsonNode kmContent = json("""
                {"kmType":"LKM","tasks":[],"options":[]}
                """);
        VersionEntry kmVersion = new VersionEntry(
                1, 1, hash(service.canonical(kmContent).toString()), kmContent, null,
                OffsetDateTime.now(), sourceModuleVersionUuid);
        DefinitionEntry kmDefinition = new DefinitionEntry(
                DefinitionType.KNOWLEDGE_MODULE, "KM_LOAD", "ROOT/CHILD", "AKTIF",
                "KM Loading", null, null, List.of(kmVersion));

        JsonNode mappingContent = json("""
                {"sources":[{"id":"SRC","alias":"S","dataObjectUuid":"%s","schemaSnapshotUuid":"%s"}],
                "target":{"id":"TGT","alias":"T","dataObjectUuid":"%s","schemaSnapshotUuid":"%s"},
                "joins":[],"filters":[],"columnMappings":[{"source":{"object":"SRC","column":"ID"},"target":{"object":"TGT","column":"ID"}}],
                "modules":{"loading":{"versionUuid":"%s","contentHash":"%s"},"integration":{"versionUuid":"%s","contentHash":"%s"}},
                "options":{"batchRows":100,"fetchRows":100,"maxRows":100000,"maxBytes":1048576,"allowEmptySource":false}}
                """.formatted(dataObjectUuid, snapshotUuid, dataObjectUuid, snapshotUuid,
                        sourceModuleVersionUuid, "a".repeat(64), sourceModuleVersionUuid, "b".repeat(64)));
        VersionEntry mappingVersion = new VersionEntry(
                1, 4, hash(service.canonical(mappingContent).toString()), mappingContent, null,
                OffsetDateTime.now());
        DefinitionEntry mappingDefinition = new DefinitionEntry(
                DefinitionType.MAPPING, "MAP_V4", "ROOT/CHILD", "AKTIF",
                "Mapping v4", null, null, List.of(mappingVersion));

        ProjectBundle unsigned = unsignedBundleWithDefinitions(kmDefinition, mappingDefinition);
        ObjectNode topology = (ObjectNode) emptyTopology();
        ((ArrayNode) topology.get("connections")).addObject().put("code", "CONNECTION");
        ((ArrayNode) topology.get("physicalSchemas")).addObject()
                .put("code", "PHYSICAL").put("connectionCode", "CONNECTION");
        ObjectNode dataObject = ((ArrayNode) topology.get("dataObjects")).addObject();
        dataObject.put("sourceUuid", dataObjectUuid.toString());
        dataObject.put("sourceSchemaSnapshotUuid", snapshotUuid.toString());
        dataObject.put("sourcePhysicalSchemaCode", "PHYSICAL");
        dataObject.put("sourceConnectionCode", "CONNECTION");
        dataObject.put("sourceSnapshotFingerprint", "f".repeat(64));
        ProjectBundle bundle = sign(service, new ProjectBundle(
                unsigned.format(), unsigned.formatVersion(), unsigned.schemaVersion(),
                null, unsigned.exportedAt(), unsigned.project(), unsigned.folders(),
                unsigned.definitions(), new TopologyEntry(true, topology),
                unsigned.producer(), unsigned.includedSections(),
                unsigned.publications(), unsigned.schedules()));

        UUID targetConnectionUuid = UUID.randomUUID();
        UUID targetSchemaUuid = UUID.randomUUID();
        repository.globalResources.put(targetConnectionUuid, new GlobalResourceRow(
                GlobalResourceType.CONNECTION, targetConnectionUuid,
                "CONNECTION_TARGET", "POSTGRESQL", null));
        repository.globalResources.put(targetSchemaUuid, new GlobalResourceRow(
                GlobalResourceType.PHYSICAL_SCHEMA, targetSchemaUuid,
                "PHYSICAL_TARGET", "POSTGRESQL", "CONNECTION_TARGET"));
        List<GlobalBinding> bindings = List.of(
                new GlobalBinding(GlobalResourceType.CONNECTION, "CONNECTION",
                        GlobalBindingMode.BIND_EXISTING, targetConnectionUuid, null, null),
                new GlobalBinding(GlobalResourceType.PHYSICAL_SCHEMA, "CONNECTION::PHYSICAL",
                        GlobalBindingMode.BIND_EXISTING, targetSchemaUuid, null, null));

        var plan = service.planTargetImport(repository.target.uuid(), bundle, bindings);
        assertTrue(plan.valid(), () -> plan.issues().toString());
        var result = service.importIntoTarget(repository.target.uuid(),
                new TargetImportRequest(bundle, bindings, plan.planDigest(), plan.targetVersion()),
                "mapping-v4-km-remap", 1);

        assertTrue(result.imported());
        VersionRow kmRow = repository.capturedVersions.stream()
                .filter(row -> row.content().has("kmType"))
                .findFirst().orElseThrow();
        VersionRow mappingRow = repository.capturedVersions.stream()
                .filter(row -> row.content().has("modules"))
                .findFirst().orElseThrow();

        assertNotNull(kmRow.uuid());
        assertEquals(kmRow.uuid().toString(),
                mappingRow.content().path("modules").path("loading").path("versionUuid").asString());
        assertEquals(kmRow.uuid().toString(),
                mappingRow.content().path("modules").path("integration").path("versionUuid").asString());
        assertNotEquals(sourceModuleVersionUuid.toString(),
                mappingRow.content().path("modules").path("loading").path("versionUuid").asString());
    }

    @Test
    void legacyV2BundleWithoutUnresolvedReferencesImportsSuccessfully() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle current = validBundle(service);
        ProjectBundle legacyUnsigned = new ProjectBundle(
                current.format(), ProjectBundleModels.LEGACY_FORMAT_VERSION,
                ProjectBundleModels.LEGACY_SCHEMA_VERSION, null, current.exportedAt(),
                current.project(), current.folders(), current.definitions(), current.topology(),
                null, null, null, null);
        ProjectBundle legacy = new ProjectBundle(
                legacyUnsigned.format(), legacyUnsigned.formatVersion(),
                legacyUnsigned.schemaVersion(), service.checksum(legacyUnsigned),
                legacyUnsigned.exportedAt(), legacyUnsigned.project(), legacyUnsigned.folders(),
                legacyUnsigned.definitions(), legacyUnsigned.topology(), null, null, null, null);

        var result = service.importBundle(legacy, ConflictPolicy.FAIL, false);

        assertTrue(result.imported());
    }

    @Test
    void legacyV2BundleWithUnresolvedLogicalSchemaIsRejected() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID sourceUuid = UUID.randomUUID();
        ObjectNode topology = (ObjectNode) emptyTopology();
        ObjectNode schema = ((ArrayNode) topology.get("logicalSchemas")).addObject();
        schema.put("code", "UNBOUND_LS");
        schema.put("name", "Unbound");
        schema.put("sourceUuid", sourceUuid.toString());
        ProjectBundle base = validBundle(service);
        ProjectBundle legacyUnsigned = new ProjectBundle(
                base.format(), ProjectBundleModels.LEGACY_FORMAT_VERSION,
                ProjectBundleModels.LEGACY_SCHEMA_VERSION, null, base.exportedAt(),
                base.project(), base.folders(), base.definitions(), new TopologyEntry(true, topology),
                null, null, null, null);
        ProjectBundle legacy = new ProjectBundle(
                legacyUnsigned.format(), legacyUnsigned.formatVersion(),
                legacyUnsigned.schemaVersion(), service.checksum(legacyUnsigned),
                legacyUnsigned.exportedAt(), legacyUnsigned.project(), legacyUnsigned.folders(),
                legacyUnsigned.definitions(), legacyUnsigned.topology(), null, null, null, null);

        ProjectBundleException exception = assertThrows(ProjectBundleException.class,
                () -> service.importBundle(legacy, ConflictPolicy.FAIL, false));

        assertEquals("BUNDLE_IMPORT_REFERENCE_UNRESOLVED", exception.code());
        assertTrue(exception.report().issues().stream().anyMatch(
                issue -> issue.code().equals(BundleV2Adapter.UNRESOLVED_LEGACY_REFERENCE)
                        && issue.path().equals("topology.definitions.logicalSchemas[0].sourceUuid")));
    }

    @Test
    void legacyV2BundleWithUnresolvedPackageDefinitionReferenceIsRejected() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID missingUuid = UUID.randomUUID();
        JsonNode content = json("""
                {"firstStepId":"s1","steps":[{"id":"s1","type":"PROCEDURE","definitionUuid":"%s"}],"transitions":[]}
                """.formatted(missingUuid));
        VersionEntry version = new VersionEntry(
                1, 1, "0".repeat(64), content, null, OffsetDateTime.now());
        DefinitionEntry importer = new DefinitionEntry(
                DefinitionType.PACKAGE, "IMPORTER", "ROOT/CHILD", "AKTIF",
                "Importer", null, null, List.of(version));
        ProjectBundle legacyUnsigned = new ProjectBundle(
                ProjectBundleModels.FORMAT, ProjectBundleModels.LEGACY_FORMAT_VERSION,
                ProjectBundleModels.LEGACY_SCHEMA_VERSION, null, OffsetDateTime.now(),
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                List.of(
                        new FolderEntry("ROOT/CHILD", "ROOT", "CHILD", "AKTIF", "Child", null),
                        new FolderEntry("ROOT", null, "ROOT", "AKTIF", "Root", null)),
                List.of(importer),
                new TopologyEntry(true, emptyTopology()),
                null, null, null, null);
        ProjectBundle legacy = new ProjectBundle(
                legacyUnsigned.format(), legacyUnsigned.formatVersion(),
                legacyUnsigned.schemaVersion(), service.checksum(legacyUnsigned),
                legacyUnsigned.exportedAt(), legacyUnsigned.project(), legacyUnsigned.folders(),
                legacyUnsigned.definitions(), legacyUnsigned.topology(), null, null, null, null);

        ProjectBundleException exception = assertThrows(ProjectBundleException.class,
                () -> service.importBundle(legacy, ConflictPolicy.FAIL, false));

        assertEquals("BUNDLE_IMPORT_REFERENCE_UNRESOLVED", exception.code());
        assertTrue(exception.report().issues().stream().anyMatch(
                issue -> issue.code().equals(BundleV2Adapter.UNRESOLVED_LEGACY_REFERENCE)
                        && issue.path().equals("steps[0].definitionUuid")));
    }

    @Test
    void legacyV2BundleWithResolvableDefinitionSelfReferenceRemapsCorrectly() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID sourceDefUuid = UUID.randomUUID();
        UUID targetDefUuid = UUID.randomUUID();
        repository.definitionUuidByCode.put(
                DefinitionType.PACKAGE.name() + ":REFERENCED_PKG", targetDefUuid);
        JsonNode content = json("""
                {"firstStepId":"s1","steps":[{"id":"s1","type":"PROCEDURE","definitionUuid":"%s"}],"transitions":[]}
                """.formatted(sourceDefUuid));
        String contentHash = hash(service.canonical(content).toString());
        VersionEntry version = new VersionEntry(
                1, 1, contentHash, content, null, OffsetDateTime.now());
        DefinitionEntry referenced = new DefinitionEntry(
                DefinitionType.PACKAGE, "REFERENCED_PKG", "ROOT/CHILD", "AKTIF",
                "Referenced", null, null, List.of(), sourceDefUuid);
        DefinitionEntry importer = new DefinitionEntry(
                DefinitionType.PACKAGE, "IMPORTER", "ROOT/CHILD", "AKTIF",
                "Importer", null, null, List.of(version));
        ProjectBundle legacyUnsigned = new ProjectBundle(
                ProjectBundleModels.FORMAT, ProjectBundleModels.LEGACY_FORMAT_VERSION,
                ProjectBundleModels.LEGACY_SCHEMA_VERSION, null, OffsetDateTime.now(),
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                List.of(
                        new FolderEntry("ROOT/CHILD", "ROOT", "CHILD", "AKTIF", "Child", null),
                        new FolderEntry("ROOT", null, "ROOT", "AKTIF", "Root", null)),
                List.of(referenced, importer),
                new TopologyEntry(true, emptyTopology()),
                null, null, null, null);
        ProjectBundle legacy = new ProjectBundle(
                legacyUnsigned.format(), legacyUnsigned.formatVersion(),
                legacyUnsigned.schemaVersion(), service.checksum(legacyUnsigned),
                legacyUnsigned.exportedAt(), legacyUnsigned.project(), legacyUnsigned.folders(),
                legacyUnsigned.definitions(), legacyUnsigned.topology(), null, null, null, null);

        var result = service.importBundle(legacy, ConflictPolicy.FAIL, false);

        assertTrue(result.imported());
        assertEquals(1, repository.capturedVersions.size());
        JsonNode imported = repository.capturedVersions.get(0).content();
        assertEquals(targetDefUuid.toString(), imported.path("steps").path(0).path("definitionUuid").asString());
    }

    @Test
    void v3BundleIsNotAffectedByAdapter() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        ProjectBundle bundle = validBundle(service);

        var result = service.importBundle(bundle, ConflictPolicy.FAIL, false);

        assertTrue(result.imported());
    }

    @Test
    void contentHashIsRecomputedAfterReferenceRewrite() {
        StubRepository repository = new StubRepository(objectMapper);
        ProjectBundleService service = service(repository);
        UUID sourceDefUuid = UUID.randomUUID();
        UUID targetDefUuid = UUID.randomUUID();
        repository.definitionUuidByCode.put(
                DefinitionType.PACKAGE.name() + ":REFERENCED_PKG", targetDefUuid);
        JsonNode content = json("""
                {"firstStepId":"s1","steps":[{"id":"s1","type":"PROCEDURE","definitionUuid":"%s"}],"transitions":[]}
                """.formatted(sourceDefUuid));
        String originalHash = hash(service.canonical(content).toString());
        VersionEntry version = new VersionEntry(
                1, 1, originalHash, content, null, OffsetDateTime.now());
        DefinitionEntry referenced = new DefinitionEntry(
                DefinitionType.PACKAGE, "REFERENCED_PKG", "ROOT/CHILD", "AKTIF",
                "Referenced", null, null, List.of(), sourceDefUuid);
        DefinitionEntry importer = new DefinitionEntry(
                DefinitionType.PACKAGE, "IMPORTER", "ROOT/CHILD", "AKTIF",
                "Importer", null, null, List.of(version));
        ProjectBundle bundle = sign(service, unsignedBundleWithDefinitions(referenced, importer));

        service.importBundle(bundle, ConflictPolicy.FAIL, false);

        assertEquals(1, repository.capturedVersions.size());
        VersionRow inserted = repository.capturedVersions.get(0);
        assertNotNull(inserted.contentHash());
        assertFalse(inserted.contentHash().equals(originalHash));
        assertEquals(hash(service.canonical(inserted.content()).toString()), inserted.contentHash());
    }

    private ProjectBundle unsignedBundleWithDefinitions(DefinitionEntry... definitions) {
        return new ProjectBundle(
                ProjectBundleModels.FORMAT,
                ProjectBundleModels.FORMAT_VERSION,
                ProjectBundleModels.SCHEMA_VERSION,
                null,
                OffsetDateTime.now(),
                new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                List.of(
                        new FolderEntry(
                                "ROOT/CHILD", "ROOT", "CHILD", "AKTIF", "Child", null),
                        new FolderEntry(
                                        "ROOT", null, "ROOT", "AKTIF", "Root", null)),
                List.of(definitions),
                new TopologyEntry(true, emptyTopology()));
    }

    private JsonNode topologyWithLogicalSchema(String code, UUID sourceUuid) {
        ObjectNode topology = (ObjectNode) emptyTopology();
        ArrayNode logicalSchemas = (ArrayNode) topology.get("logicalSchemas");
        ObjectNode schema = logicalSchemas.addObject();
        schema.put("code", code);
        schema.put("name", code);
        schema.put("sourceUuid", sourceUuid.toString());
        return topology;
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
                                "ROOT/CHILD", "ROOT", "CHILD", "AKTIF", "Child", null),
                        new FolderEntry(
                                "ROOT", null, "ROOT", "AKTIF", "Root", null)),
                List.of(definition),
                new TopologyEntry(true, emptyTopology()));
    }

    private ProjectBundle sign(ProjectBundleService service, ProjectBundle bundle) {
        return new ProjectBundle(
                bundle.format(), bundle.formatVersion(), bundle.schemaVersion(),
                service.checksum(bundle), bundle.exportedAt(), bundle.project(),
                bundle.folders(), bundle.definitions(), bundle.topology());
    }

    /** Like {@link #sign}, but preserves producer/includedSections/publications/schedules. */
    private ProjectBundle signFull(ProjectBundleService service, ProjectBundle bundle) {
        return new ProjectBundle(
                bundle.format(), bundle.formatVersion(), bundle.schemaVersion(),
                service.checksum(bundle), bundle.exportedAt(), bundle.project(),
                bundle.folders(), bundle.definitions(), bundle.topology(),
                bundle.producer(), bundle.includedSections(),
                bundle.publications(), bundle.schedules());
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
        private final List<VersionRow> capturedVersions = new ArrayList<>();
        private final Map<String, UUID> definitionUuidByCode = new HashMap<>();
        private final Map<UUID, UUID> versionUuidBySource = new HashMap<>();
        private long nextId = 100;
        private Long rootId;
        private Long childId;
        private int insertedDrafts;
        private int insertedVersions;
        private ProjectRow target = new ProjectRow(
                50, UUID.randomUUID(), "TARGET", 1, "AKTIF", "Target", null);
        private ImportReceiptRow receipt;
        private String globalResourceCode = "LS";
        private final Map<UUID, GlobalResourceRow> globalResources = new HashMap<>();

        private StubRepository(ObjectMapper objectMapper) {
            super(null, objectMapper);
        }

        @Override
        boolean projectCodeExists(String code) {
            return existingProjectCodes.contains(code);
        }

        @Override
        Optional<ProjectRow> findProject(UUID projectUuid) {
            return target.uuid().equals(projectUuid) ? Optional.of(target) : Optional.empty();
        }

        @Override
        ProjectRow lockProject(UUID projectUuid) {
            if (!target.uuid().equals(projectUuid)) throw new java.util.NoSuchElementException();
            return target;
        }

        @Override
        boolean projectContentIsEmpty(long projectId) {
            return true;
        }

        @Override
        long advanceProjectVersion(long projectId, long expectedVersion) {
            if (target.version() != expectedVersion) throw new java.util.NoSuchElementException();
            target = new ProjectRow(
                    target.id(), target.uuid(), target.code(), target.version() + 1,
                    target.status(), target.name(), target.description());
            return target.version();
        }

        @Override
        Optional<ImportReceiptRow> findImportReceipt(long projectId, String idempotencyKey) {
            return Optional.ofNullable(receipt);
        }

        @Override
        void saveImportReceipt(
                long projectId, String idempotencyKey, String bundleChecksum,
                String planDigest, long targetVersion, JsonNode result, Long actorId) {
            receipt = new ImportReceiptRow(
                    bundleChecksum, planDigest, targetVersion, result);
        }

        @Override
        void lockProjectCodeNamespace() {
        }

        @Override
        ExportSnapshot loadSnapshot(UUID projectUuid) {
            return snapshot;
        }

        @Override
        JsonNode loadPortableTopology(long projectId) {
            return emptyTopology();
        }

        @Override
        Map<UUID, UUID> importPortableTopology(long projectId, JsonNode topology) {
            return Map.of();
        }

        @Override
        Map<UUID, UUID> importPortableTopology(long projectId, JsonNode topology, String bundleChecksum) {
            return Map.of();
        }

        private ProjectBundleRepository.PortableTopologyImportResult portableTopologyResult =
                new ProjectBundleRepository.PortableTopologyImportResult(Map.of(), Map.of());

        @Override
        ProjectBundleRepository.PortableTopologyImportResult importPortableTopologyWithSnapshots(
                long projectId, JsonNode topology, String bundleChecksum) {
            return portableTopologyResult;
        }

        @Override
        ProjectRow insertProject(
                UUID uuid, String code, String status, String name, String description) {
            writeCount++;
            return new ProjectRow(9, uuid, code, 1, status, name, description);
        }

        @Override
        long insertFolder(
                long projectId, Long parentId, String code,
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
        UUID findDefinitionUuid(long projectId, DefinitionType type, String code) {
            return definitionUuidByCode.get(type.name() + ":" + code);
        }

        @Override
        Optional<GlobalResourceRow> findGlobalResource(GlobalResourceType type, UUID uuid) {
            GlobalResourceRow resource = globalResources.get(uuid);
            return Optional.of(resource == null
                    ? new GlobalResourceRow(type, uuid, globalResourceCode, "POSTGRESQL", null)
                    : resource);
        }

        @Override
        UUID findVersionUuid(long definitionId, int versionNumber) {
            return UUID.randomUUID();
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
            capturedVersions.add(version);
        }
    }

    private static JsonNode emptyTopology() {
        var value = new ObjectMapper().createObjectNode();
        for (String name : List.of(
                "connections", "physicalSchemas", "logicalSchemas", "environments",
                "schemaBindings", "models", "submodels", "dataObjects")) {
            value.putArray(name);
        }
        return value;
    }
}

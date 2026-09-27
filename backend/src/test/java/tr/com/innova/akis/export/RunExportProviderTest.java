package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.export.ExportModels.ExportContext;
import tr.com.innova.akis.export.ExportModels.ExportFilter;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.RunExportReader.EventRef;
import tr.com.innova.akis.export.RunExportReader.RunQuery;
import tr.com.innova.akis.export.RunExportReader.RunRef;
import tr.com.innova.akis.export.RunExportReader.StepRef;
import tr.com.innova.akis.knowledge.KmStepJournal;
import tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;
import tr.com.innova.akis.knowledge.WorkObjectStore;

class RunExportProviderTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exportsAllRunsWithinMaxRecords() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));
        reader.addRun(run("run-2", "BASARISIZ"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        provider.streamRecords(context(ExportScope.ALL, List.of(), 10), writer);

        assertEquals(2, writer.records.size());
        assertEquals(1, reader.stepBatchCalls);
        assertEquals(1, reader.eventBatchCalls);
        assertEquals(run1.runUuid().toString(), text(writer.records.get(0), "runUuid"));
        assertEquals(run2.runUuid().toString(), text(writer.records.get(1), "runUuid"));
    }

    @Test
    void maxRecordsCapsTopLevelRuns() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));
        reader.addRun(run("run-2", "BASARILI"));
        reader.addRun(run("run-3", "BASARILI"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        ExportException error = assertThrows(ExportException.class,
                () -> provider.streamRecords(context(ExportScope.ALL, List.of(), 2), writer));

        assertEquals("EXPORT_RUN_LIMIT", error.code());
        assertEquals(2, writer.records.size());
    }

    @Test
    void selectedScopeExportsOnlyGivenUuids() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID selected = UUID.fromString("11111111-1111-1111-1111-111111111111");
        reader.addRun(new RunRef(selected, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addRun(run("run-2", "BASARILI"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        ExportFilter filter = new ExportFilter("runUuids", "in",
                MAPPER.createArrayNode().add(selected.toString()));
        provider.streamRecords(context(ExportScope.SELECTED, List.of(filter), 10), writer);

        assertEquals(1, writer.records.size());
        assertEquals(selected.toString(), text(writer.records.get(0), "runUuid"));
        assertEquals(1, reader.childBatchCalls);
    }

    @Test
    void selectedScopeRejectsMissingRunInsteadOfSilentlyDroppingIt() {
        CapturingJsonExportWriter writer = writer();
        ExportFilter filter = new ExportFilter("runUuids", "in", MAPPER.createArrayNode()
                .add("11111111-1111-1111-1111-111111111111"));

        ExportException error = assertThrows(ExportException.class,
                () -> provider(new CapturingRunExportReader()).streamRecords(
                        context(ExportScope.SELECTED, List.of(filter), 10), writer));

        assertEquals("EXPORT_SELECTED_RUN_NOT_FOUND", error.code());
        assertTrue(writer.records.isEmpty());
    }

    @Test
    void selectedScopeRejectsMalformedAndEmptySelections() {
        RunExportProvider provider = provider(new CapturingRunExportReader());
        ExportFilter malformed = new ExportFilter("runUuids", "in", MAPPER.createArrayNode().add("not-a-uuid"));

        ExportException invalid = assertThrows(ExportException.class,
                () -> provider.streamRecords(context(ExportScope.SELECTED, List.of(malformed), 10), writer()));
        assertEquals("EXPORT_SELECTION_INVALID", invalid.code());
        ExportException empty = assertThrows(ExportException.class,
                () -> provider.streamRecords(context(ExportScope.SELECTED, List.of(), 10), writer()));
        assertEquals("EXPORT_SELECTION_INVALID", empty.code());
    }

    @Test
    void selectedScopeRejectsSelectionsBeyondLimitInsteadOfTruncating() {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));
        reader.addRun(run("run-2", "BASARILI"));
        ExportFilter filter = new ExportFilter("runUuids", "in", MAPPER.createArrayNode()
                .add(run1.runUuid().toString()).add(run2.runUuid().toString()));
        CapturingJsonExportWriter writer = writer();

        ExportException error = assertThrows(ExportException.class,
                () -> provider(reader).streamRecords(context(ExportScope.SELECTED, List.of(filter), 1), writer));

        assertEquals("EXPORT_SELECTION_LIMIT", error.code());
        assertTrue(writer.records.isEmpty());
    }

    @Test
    void filteredScopeAppliesStatusFilterViaSql() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));
        reader.addRun(run("run-2", "BASARISIZ"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        ExportFilter filter = new ExportFilter("statuses", "eq", MAPPER.valueToTree("BASARILI"));
        provider.streamRecords(context(ExportScope.FILTERED, List.of(filter), 10), writer);

        assertEquals(1, writer.records.size());
        assertEquals(run1.runUuid().toString(), text(writer.records.get(0), "runUuid"));
        RunQuery query = reader.lastQuery;
        assertNotNull(query);
        assertEquals("BASARILI", query.statuses());
    }

    @Test
    void filteredScopeAppliesEnvironmentDefinitionTypeScheduledAndTimeRange() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        OffsetDateTime from = OffsetDateTime.now().minusDays(2);
        OffsetDateTime to = OffsetDateTime.now();
        List<ExportFilter> filters = List.of(
                new ExportFilter("environment", "eq", MAPPER.valueToTree("PROD")),
                new ExportFilter("definitionType", "eq", MAPPER.valueToTree("ETL")),
                new ExportFilter("scheduled", "eq", MAPPER.valueToTree(true)),
                new ExportFilter("from", "eq", MAPPER.valueToTree(from.toString())),
                new ExportFilter("to", "eq", MAPPER.valueToTree(to.toString())));

        provider.streamRecords(context(ExportScope.FILTERED, filters, 10), writer);

        RunQuery query = reader.lastQuery;
        assertNotNull(query);
        assertEquals("PROD", query.environmentCode());
        assertEquals("ETL", query.definitionType());
        assertEquals(Boolean.TRUE, query.scheduled());
        assertEquals(from, query.fromTime());
        assertEquals(to, query.toTime());
    }

    @Test
    void visibleScopeDefaultsToRecentViewWithTimeWindow() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        provider.streamRecords(context(ExportScope.VISIBLE, List.of(), 10), writer);

        RunQuery query = reader.lastQuery;
        assertNotNull(query);
        assertEquals("RECENT", query.view());
        assertNotNull(query.fromTime());
        assertNotNull(query.toTime());
    }

    @Test
    void visibleScopePreservesExplicitViewFilter() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARISIZ"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        ExportFilter filter = new ExportFilter("view", "eq", MAPPER.valueToTree("FAILED"));
        provider.streamRecords(context(ExportScope.VISIBLE, List.of(filter), 10), writer);

        assertEquals("FAILED", reader.lastQuery.view());
    }

    @Test
    void queryFilterSearchesDefinitionNameAndCode() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        ExportFilter filter = new ExportFilter("query", "contains", MAPPER.valueToTree("invoice"));
        provider.streamRecords(context(ExportScope.FILTERED, List.of(filter), 10), writer);

        assertEquals("invoice", reader.lastQuery.query());
    }

    @Test
    void childRunsAreIncludedAndRespectMaxRecords() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID parent = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID child = UUID.fromString("22222222-2222-2222-2222-222222222222");
        reader.addRun(new RunRef(parent, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "child-step", "TASK", 1, "Child",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, child));
        reader.addRun(new RunRef(child, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        provider.streamRecords(context(ExportScope.ALL, List.of(), 2), writer);

        assertEquals(2, writer.records.size());
        assertEquals(parent.toString(), text(writer.records.get(0), "runUuid"));
        assertEquals(child.toString(), text(writer.records.get(1), "runUuid"));
        assertEquals(parent.toString(), text(writer.records.get(0), "rootRunUuid"));
        assertEquals(parent.toString(), text(writer.records.get(1), "rootRunUuid"));
        assertEquals(parent.toString(), text(writer.records.get(1), "parentRunUuid"));
        String packageStepUuid = writer.records.get(0).get("steps").get(0).get("uuid").asText();
        assertEquals(packageStepUuid, text(writer.records.get(1), "packageStepUuid"));
    }

    @Test
    void siblingChildRunsShareOneLookupAndPreserveParentFirstOrder() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID parent = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID uuid : List.of(parent, first, second)) {
            reader.addRun(new RunRef(uuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        }
        reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "first", "TASK", 1, "First",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, first));
        reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "second", "TASK", 2, "Second",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, second));

        CapturingJsonExportWriter writer = writer();
        provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 3), writer);

        assertEquals(List.of(parent.toString(), first.toString(), second.toString()),
                writer.records.stream().map(record -> text(record, "runUuid")).toList());
        assertEquals(1, reader.childBatchCalls);
        assertEquals(2, reader.stepBatchCalls);
        assertEquals(2, reader.eventBatchCalls);
    }

    @Test
    void childRunsAcrossOneRootBatchShareLookupWithoutChangingDepthFirstOrder() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        List<UUID> parents = new ArrayList<>();
        List<UUID> children = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            parents.add(UUID.randomUUID());
            children.add(UUID.randomUUID());
        }
        for (UUID parent : parents) {
            reader.addRun(new RunRef(parent, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        }
        for (int index = 0; index < 16; index++) {
            UUID parent = parents.get(index);
            UUID child = children.get(index);
            reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "child-step", "TASK", 1, "Child",
                    "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                    null, null, null, null, null, child));
            reader.addRun(new RunRef(child, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        }

        CapturingJsonExportWriter writer = writer();
        provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 32), writer);

        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            expected.add(parents.get(index).toString());
            expected.add(children.get(index).toString());
        }
        assertEquals(expected, writer.records.stream().map(record -> text(record, "runUuid")).toList());
        assertEquals(1, reader.childBatchCalls);
    }

    @Test
    void grandchildrenAcrossOneChildWaveShareLookupAndKeepDepthFirstOrder() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID root = UUID.randomUUID();
        reader.addRun(new RunRef(root, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        List<UUID> children = new ArrayList<>();
        List<UUID> grandchildren = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            UUID child = UUID.randomUUID();
            UUID grandchild = UUID.randomUUID();
            children.add(child);
            grandchildren.add(grandchild);
            reader.addRun(new RunRef(child, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
            reader.addRun(new RunRef(grandchild, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
            reader.addStep(root, new StepRef(UUID.randomUUID(), null, "child-" + index, "TASK", index + 1,
                    "Child", "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                    null, null, null, null, null, child));
            reader.addStep(child, new StepRef(UUID.randomUUID(), null, "grandchild", "TASK", 1,
                    "Grandchild", "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                    null, null, null, null, null, grandchild));
        }

        CapturingJsonExportWriter writer = writer();
        provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 33), writer);

        List<String> expected = new ArrayList<>();
        expected.add(root.toString());
        for (int index = 0; index < 16; index++) {
            expected.add(children.get(index).toString());
            expected.add(grandchildren.get(index).toString());
        }
        assertEquals(expected, writer.records.stream().map(record -> text(record, "runUuid")).toList());
        assertEquals(2, reader.childBatchCalls);
    }

    @Test
    void moreThanOneChildWaveUsesOnlyTwoLookups() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID root = UUID.randomUUID();
        reader.addRun(new RunRef(root, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        for (int index = 0; index < 17; index++) {
            UUID child = UUID.randomUUID();
            reader.addRun(new RunRef(child, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
            reader.addStep(root, new StepRef(UUID.randomUUID(), null, "child-" + index, "TASK", index + 1,
                    "Child", "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                    null, null, null, null, null, child));
        }

        CapturingJsonExportWriter writer = writer();
        provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 18), writer);

        assertEquals(18, writer.records.size());
        assertEquals(2, reader.childBatchCalls);
    }

    @Test
    void unusuallyLargeRunFallsBackToStreamingWithoutLosingSteps() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        RunRef run = run("run-1", "BASARILI");
        reader.addRun(run);
        for (int ordinal = 0; ordinal < 2_049; ordinal++) {
            reader.addStep(run.runUuid(), new StepRef(UUID.randomUUID(), null,
                    "step-" + ordinal, "TASK", ordinal, "Step", "BASARILI", null, null,
                    OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null, null));
        }

        CapturingJsonExportWriter writer = writer();
        provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 10), writer);

        assertEquals(1, writer.records.size());
        assertEquals(2_049, writer.records.get(0).get("steps").size());
        assertEquals(1, reader.singleStepCalls);
        assertEquals(1, reader.stepBatchCalls);
        assertEquals(1, reader.eventBatchCalls);
    }

    @Test
    void largeChildBatchSplitsWithoutLosingSiblingOrder() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID parent = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID uuid : List.of(parent, first, second)) {
            reader.addRun(new RunRef(uuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        }
        reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "first", "TASK", 1, "First",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, first));
        reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "second", "TASK", 2, "Second",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, second));
        for (int ordinal = 0; ordinal < 2_049; ordinal++) {
            reader.addStep(first, new StepRef(UUID.randomUUID(), null, "step-" + ordinal,
                    "TASK", ordinal, "Step", "BASARILI", null, null,
                    OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null, null));
        }

        CapturingJsonExportWriter writer = writer();
        provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 3), writer);

        assertEquals(List.of(parent.toString(), first.toString(), second.toString()),
                writer.records.stream().map(record -> text(record, "runUuid")).toList());
        assertEquals(2_049, writer.records.get(1).get("steps").size());
        assertEquals(1, reader.singleStepCalls);
        assertEquals(2, reader.stepBatchCalls);
        assertEquals(2, reader.eventBatchCalls);
    }

    @Test
    void childRunBeyondLimitFailsInsteadOfSilentlyTruncatingPackageHistory() {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        reader.addRun(new RunRef(parent, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "child-step", "TASK", 1, "Child",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, child));
        reader.addRun(new RunRef(child, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));

        ExportException failure = assertThrows(ExportException.class,
                () -> provider(reader).streamRecords(
                        context(ExportScope.ALL, List.of(), 1), writer()));
        assertEquals("EXPORT_RUN_TREE_LIMIT", failure.code());
    }

    @Test
    void missingChildFailsInsteadOfPublishingAnIncompleteArchive() {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID parent = UUID.randomUUID();
        UUID missingChild = UUID.randomUUID();
        reader.addRun(new RunRef(parent, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addStep(parent, new StepRef(UUID.randomUUID(), null, "child-step", "TASK", 1, "Child",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, missingChild));

        ExportException failure = assertThrows(ExportException.class,
                () -> provider(reader).streamRecords(
                        context(ExportScope.ALL, List.of(), 10), writer()));
        assertEquals("EXPORT_CHILD_RUN_MISSING", failure.code());
    }

    @Test
    void detailReaderFailureDoesNotBecomeAJsonErrorField() {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        reader.addRun(run("run-1", "BASARILI"));
        KmStepJournal failingJournal = new KmStepJournal(null, MAPPER) {
            @Override
            public void forEachRowBatch(UUID projectUuid, List<UUID> runUuids,
                    java.util.function.BiConsumer<UUID, Row> consumer) {
                throw new IllegalStateException("sensitive database detail");
            }
        };
        CapturingJsonExportWriter writer = writer();
        RunExportProvider provider = new RunExportProvider(
                reader, failingJournal, new FakeWorkObjectStore(), MAPPER);

        assertThrows(IllegalStateException.class, () -> provider.streamRecords(
                context(ExportScope.ALL, List.of(), 10, true), writer));
        assertTrue(writer.records.isEmpty());
    }

    @Test
    void duplicateRunsAreOnlyExportedOnce() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID runUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        provider.streamRecords(context(ExportScope.ALL, List.of(), 10), writer);

        assertEquals(1, writer.records.size());
    }

    @Test
    void stepRowCountsAreAggregated() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID runUuid = UUID.randomUUID();
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addStep(runUuid, step("select-step", "SELECT", 100L));
        reader.addStep(runUuid, step("insert-step", "INSERT", 50L));
        reader.addStep(runUuid, step("update-step", "UPDATE", 25L));
        reader.addStep(runUuid, step("delete-step", "DELETE", 10L));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        provider.streamRecords(context(ExportScope.ALL, List.of(), 10), writer);

        JsonNode record = writer.records.get(0);
        assertEquals(100L, record.get("selectedRows").asLong());
        assertEquals(50L, record.get("insertedRows").asLong());
        assertEquals(25L, record.get("updatedRows").asLong());
        assertEquals(10L, record.get("deletedRows").asLong());
    }

    @Test
    void rowCountsAboveJavascriptSafeIntegerRemainExact() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID runUuid = UUID.randomUUID();
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        long count = 9_007_199_254_740_993L;
        reader.addStep(runUuid, step("large", "INSERT", count));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonExportWriter writer = JsonExportWriterImpl.builder(MAPPER).outputStream(output).build()) {
            provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 10), writer);
        }

        JsonNode record = MAPPER.readTree(output.toString(StandardCharsets.UTF_8))
                .path("records").get(0);
        assertTrue(record.path("insertedRows").isTextual());
        assertEquals(Long.toString(count), record.path("insertedRows").asText());
        assertTrue(record.path("steps").get(0).path("rowCount").isTextual());
        assertEquals(Long.toString(count), record.path("steps").get(0).path("rowCount").asText());
    }

    @Test
    void rowCountOverflowFailsInsteadOfWrappingNegative() {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID runUuid = UUID.randomUUID();
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addStep(runUuid, step("first", "INSERT", Long.MAX_VALUE));
        reader.addStep(runUuid, step("second", "INSERT", 1));

        ExportException failure = assertThrows(ExportException.class,
                () -> provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 10), writer()));
        assertEquals("EXPORT_ROW_COUNT_OVERFLOW", failure.code());
    }

    @Test
    void sourceRoleContributesToSelectedRows() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID runUuid = UUID.randomUUID();
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        reader.addStep(runUuid, new StepRef(UUID.randomUUID(), null, "src", "TASK", 1, "Source",
                "BASARILI", "SOURCE", null, OffsetDateTime.now(), OffsetDateTime.now(),
                77L, null, null, null, null, null));

        RunExportProvider provider = provider(reader);
        CapturingJsonExportWriter writer = writer();

        provider.streamRecords(context(ExportScope.ALL, List.of(), 10), writer);

        assertEquals(77L, writer.records.get(0).get("selectedRows").asLong());
    }

    @Test
    void exportsExecutedSqlEvidenceForEachRunStep() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        UUID runUuid = UUID.randomUUID();
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        JsonNode sqlEvidence = MAPPER.readTree("[{\"site\":\"SOURCE\",\"sql\":\"select 1\"}]");
        reader.addStep(runUuid, new StepRef(UUID.randomUUID(), null, "src", "TASK", 1, "Source",
                "BASARILI", "SOURCE", null, OffsetDateTime.now(), OffsetDateTime.now(),
                1L, null, null, "SELECT", null, null, sqlEvidence));

        CapturingJsonExportWriter writer = writer();
        provider(reader).streamRecords(context(ExportScope.ALL, List.of(), 10), writer);

        assertEquals(sqlEvidence, writer.records.get(0).get("steps").get(0).get("executedSql"));
    }

    @Test
    void includeDetailsAddsKmAndWorkObjectDetails() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        FakeKmStepJournal journal = new FakeKmStepJournal();
        FakeWorkObjectStore store = new FakeWorkObjectStore();
        RunExportProvider provider = new RunExportProvider(reader, journal, store, MAPPER);
        reader.addRun(run("run-1", "BASARILI"));
        reader.addRun(run("run-2", "BASARILI"));

        CapturingJsonExportWriter writer = writer();
        provider.streamRecords(context(ExportScope.ALL, List.of(), 10, true), writer);

        JsonNode details = writer.records.get(0).get("details");
        assertNotNull(details);
        assertTrue(details.get("kmSteps").isArray());
        assertTrue(details.get("workObjects").isArray());
        assertEquals("OK", text(details, "reconciliation/outcome"));
        assertEquals(2, writer.records.size());
        assertEquals(1, journal.batchRowCalls);
        assertEquals(1, journal.batchReconciliationCalls);
        assertEquals(0, journal.singleRowCalls);
        assertEquals(1, store.batchCalls);
        assertEquals(0, store.singleCalls);
    }

    @Test
    void oversizedKmDetailsStreamOnlyThatRunWithoutRepeatingBatchQueries() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        RunRef large = run("run-1", "BASARILI");
        RunRef small = run("run-2", "BASARILI");
        reader.addRun(large);
        reader.addRun(small);
        int[] batchCalls = {0};
        int[] singleCalls = {0};
        KmStepJournal journal = new KmStepJournal(null, MAPPER) {
            @Override
            public void forEachRowBatch(UUID projectUuid, List<UUID> runUuids,
                    BiConsumer<UUID, Row> consumer) {
                batchCalls[0]++;
                for (int ordinal = 0; ordinal < 2_049; ordinal++) {
                    consumer.accept(large.runUuid(), new Row(1, ordinal, "KM", "SELECT", "SOURCE",
                            "SLOT", "SUCCEEDED", 1L, null, null, null));
                }
                consumer.accept(small.runUuid(), new Row(1, 1, "KM", "SELECT", "SOURCE",
                        "SLOT", "SUCCEEDED", 1L, null, null, null));
            }

            @Override
            public void forEachRow(UUID projectUuid, UUID runUuid, Consumer<Row> consumer) {
                singleCalls[0]++;
                assertEquals(large.runUuid(), runUuid);
                for (int ordinal = 0; ordinal < 2_049; ordinal++) {
                    consumer.accept(new Row(1, ordinal, "KM", "SELECT", "SOURCE",
                            "SLOT", "SUCCEEDED", 1L, null, null, null));
                }
            }

            @Override
            public Map<UUID, Reconciliation> reconciliationBatch(UUID projectUuid, List<UUID> runUuids) {
                return Map.of();
            }

            @Override
            public Reconciliation reconciliation(UUID projectUuid, UUID runUuid) {
                return null;
            }
        };
        CapturingJsonExportWriter writer = writer();
        new RunExportProvider(reader, journal, new FakeWorkObjectStore(), MAPPER)
                .streamRecords(context(ExportScope.ALL, List.of(), 10, true), writer);

        assertEquals(2, writer.records.size());
        assertEquals(2_049, writer.records.get(0).get("details").get("kmSteps").size());
        assertEquals(1, writer.records.get(1).get("details").get("kmSteps").size());
        assertEquals(1, batchCalls[0]);
        assertEquals(1, singleCalls[0]);
    }

    @Test
    void detailsOnlyEmittedOncePerRunEvenWithChildReferences() throws Exception {
        CapturingRunExportReader reader = new CapturingRunExportReader();
        KmStepJournal journal = new FakeKmStepJournal();
        WorkObjectStore store = new FakeWorkObjectStore();
        RunExportProvider provider = new RunExportProvider(reader, journal, store, MAPPER);

        UUID runUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        reader.addRun(new RunRef(runUuid, UUID.randomUUID(), 1, "MANUAL", "BASARILI",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        // The run references itself as a child step to simulate a repeated detail path.
        reader.addStep(runUuid, new StepRef(UUID.randomUUID(), null, "loop", "TASK", 1, "Loop",
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, runUuid));

        CapturingJsonExportWriter writer = writer();
        provider.streamRecords(context(ExportScope.ALL, List.of(), 10, true), writer);

        assertEquals(1, writer.records.size());
        assertFalse(writer.records.get(0).get("details").isNull());
    }

    private RunExportProvider provider(CapturingRunExportReader reader) {
        return new RunExportProvider(reader, new FakeKmStepJournal(), new FakeWorkObjectStore(), MAPPER);
    }

    private CapturingJsonExportWriter writer() {
        return new CapturingJsonExportWriter(MAPPER);
    }

    private ExportContext context(ExportScope scope, List<ExportFilter> filters, long maxRecords) {
        return context(scope, filters, maxRecords, false);
    }

    private ExportContext context(ExportScope scope, List<ExportFilter> filters, long maxRecords, boolean includeDetails) {
        return new ExportContext(
                PROJECT_UUID, 1L, 1L, "runs", scope, filters, List.of("status"),
                includeDetails, "tr-TR", "UTC", System.currentTimeMillis(), maxRecords);
    }

    private final RunRef run1 = new RunRef(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.randomUUID(), 1, "MANUAL", "BASARILI",
            OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    private final RunRef run2 = new RunRef(UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.randomUUID(), 1, "MANUAL", "BASARISIZ",
            OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    private final RunRef run3 = new RunRef(UUID.fromString("00000000-0000-0000-0000-000000000003"),
            UUID.randomUUID(), 1, "MANUAL", "BASARILI",
            OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());

    private RunRef run(String uuidSuffix, String status) {
        RunRef base = switch (uuidSuffix) {
            case "run-1" -> run1;
            case "run-2" -> run2;
            case "run-3" -> run3;
            default -> throw new IllegalArgumentException("Unknown test run suffix: " + uuidSuffix);
        };
        return new RunRef(base.runUuid(), base.publicationUuid(), base.attemptNumber(),
                base.startType(), status, base.createdAt(), base.startedAt(), base.finishedAt());
    }

    private StepRef step(String code, String logCounter, long rowCount) {
        return new StepRef(UUID.randomUUID(), null, code, "TASK", 1, code,
                "BASARILI", null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                rowCount, null, null, logCounter, null, null);
    }

    private String text(JsonNode node, String path) {
        String[] parts = path.split("/");
        JsonNode current = node;
        for (String part : parts) {
            current = current.get(part);
            if (current == null) {
                return null;
            }
        }
        return current.isNull() ? null : current.asText();
    }

    private static final class CapturingRunExportReader extends RunExportReader {

        private final List<RunRef> runs = new ArrayList<>();
        private final List<StepHolder> steps = new ArrayList<>();
        int stepBatchCalls;
        int eventBatchCalls;
        int childBatchCalls;
        int singleStepCalls;
        RunQuery lastQuery;

        CapturingRunExportReader() {
            super(null, MAPPER);
        }

        void addRun(RunRef run) {
            runs.add(run);
        }

        void addStep(UUID runUuid, StepRef step) {
            steps.add(new StepHolder(runUuid, step));
        }

        @Override
        void forEachRun(UUID projectUuid, RunQuery query, Consumer<RunRef> consumer) {
            this.lastQuery = query;
            runs.stream()
                    .filter(r -> query.statuses() == null || query.statuses().isBlank()
                            || r.status().equals(query.statuses()))
                    .limit(query.limit() > 0 ? query.limit() : Long.MAX_VALUE)
                    .forEach(consumer);
        }

        @Override
        Optional<RunRef> find(UUID projectUuid, UUID runUuid) {
            return runs.stream()
                    .filter(r -> r.runUuid().equals(runUuid))
                    .findFirst();
        }

        @Override
        java.util.Map<UUID, RunRef> findBatch(UUID projectUuid, List<UUID> runUuids) {
            childBatchCalls++;
            java.util.Map<UUID, RunRef> found = new java.util.HashMap<>();
            runs.stream().filter(r -> runUuids.contains(r.runUuid()))
                    .forEach(r -> found.put(r.runUuid(), r));
            return found;
        }

        @Override
        void forEachStep(UUID projectUuid, UUID runUuid, Consumer<StepRef> consumer) {
            singleStepCalls++;
            steps.stream()
                    .filter(s -> s.runUuid.equals(runUuid))
                    .map(s -> s.step)
                    .forEach(consumer);
        }

        @Override
        void forEachStepBatch(UUID projectUuid, List<UUID> runUuids, BiConsumer<UUID, StepRef> consumer) {
            stepBatchCalls++;
            steps.stream().filter(s -> runUuids.contains(s.runUuid))
                    .forEach(s -> consumer.accept(s.runUuid, s.step));
        }

        @Override
        void forEachEventBatch(UUID projectUuid, List<UUID> runUuids, BiConsumer<UUID, EventRef> consumer) {
            eventBatchCalls++;
        }

        @Override
        void forEachEvent(UUID projectUuid, UUID runUuid, Consumer<EventRef> consumer) {
            // no events in tests
        }

        record StepHolder(UUID runUuid, StepRef step) {
        }
    }

    private static final class CapturingJsonExportWriter implements JsonExportWriter {

        private final ObjectMapper objectMapper;
        private final List<JsonNode> records = new ArrayList<>();

        CapturingJsonExportWriter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void writeHeader(String dataset, String resourceId, ExportScope scope,
                String locale, String timeZone, List<ExportFilter> filters,
                String providerDescription) {
        }

        @Override
        public void writeRecord(JsonNode record) {
            records.add(record);
        }

        @Override
        public void writeRecord(Consumer<JsonObjectBuilder> builder) throws IOException {
            JsonObjectBuilder b = new JsonObjectBuilder(objectMapper);
            builder.accept(b);
            records.add(b.build());
        }

        @Override
        public void writeStreamingRecord(Consumer<JsonRecordWriter> writer) throws IOException {
            var node = objectMapper.createObjectNode();
            final tools.jackson.databind.node.ArrayNode[] currentArray = new tools.jackson.databind.node.ArrayNode[1];
            final java.util.Deque<tools.jackson.databind.node.ObjectNode> parents = new java.util.ArrayDeque<>();
            final tools.jackson.databind.node.ObjectNode[] currentObject = new tools.jackson.databind.node.ObjectNode[] { node };
            writer.accept(new JsonRecordWriter() {
                public void put(String key, String value) { if (value == null) currentObject[0].putNull(key); else currentObject[0].put(key, value); }
                public void put(String key, long value) { currentObject[0].put(key, value); }
                public void put(String key, Long value) { if (value == null) currentObject[0].putNull(key); else currentObject[0].put(key, value); }
                public void put(String key, JsonNode value) { currentObject[0].set(key, value == null ? objectMapper.nullNode() : value); }
                public void startArray(String key) { currentArray[0] = objectMapper.createArrayNode(); currentObject[0].set(key, currentArray[0]); }
                public void writeArrayValue(JsonNode value) { currentArray[0].add(value); }
                public void endArray() { currentArray[0] = null; }
                public void startObject(String key) { var child = objectMapper.createObjectNode(); currentObject[0].set(key, child); parents.push(currentObject[0]); currentObject[0] = child; }
                public void endObject() { currentObject[0] = parents.pop(); }
            });
            records.add(node);
        }

        @Override
        public void writeSummary(long processedRows, Long selected, Long inserted, Long updated, Long deleted) {
        }

        @Override
        public void writeDetails(JsonNode details) {
        }

        @Override
        public void close() {
        }

        @Override
        public long recordCount() {
            return records.size();
        }
    }

    private static final class FakeKmStepJournal extends KmStepJournal {
        int batchRowCalls;
        int batchReconciliationCalls;
        int singleRowCalls;

        FakeKmStepJournal() {
            super(null, MAPPER);
        }

        @Override
        public List<Row> list(UUID projectUuid, UUID runUuid) {
            return List.of(new Row(1, 1, "STEP", "SELECT", "SITE", "SLOT",
                    "SUCCEEDED", 42L, null, OffsetDateTime.now(), OffsetDateTime.now(),
                    MAPPER.createObjectNode().put("sql", "select * from t")));
        }

        @Override
        public void forEachRow(UUID projectUuid, UUID runUuid, java.util.function.Consumer<Row> consumer) {
            singleRowCalls++;
            list(projectUuid, runUuid).forEach(consumer);
        }

        @Override
        public void forEachRowBatch(UUID projectUuid, List<UUID> runUuids,
                java.util.function.BiConsumer<UUID, Row> consumer) {
            batchRowCalls++;
            runUuids.forEach(runUuid -> list(projectUuid, runUuid)
                    .forEach(row -> consumer.accept(runUuid, row)));
        }

        @Override
        public Map<UUID, Reconciliation> reconciliationBatch(UUID projectUuid, List<UUID> runUuids) {
            batchReconciliationCalls++;
            Map<UUID, Reconciliation> result = new HashMap<>();
            runUuids.forEach(runUuid -> result.put(runUuid, reconciliation(projectUuid, runUuid)));
            return result;
        }

        @Override
        public Reconciliation reconciliation(UUID projectUuid, UUID runUuid) {
            return new Reconciliation("OK", 42L);
        }
    }

    private static final class FakeWorkObjectStore extends WorkObjectStore {
        int batchCalls;
        int singleCalls;

        FakeWorkObjectStore() {
            super(null);
        }

        @Override
        public List<ObjectRow> list(UUID projectUuid, UUID runUuid) {
            return List.of(new ObjectRow(UUID.randomUUID(), "SLOT", "DB", "OWNER", "NAME",
                    1L, "hash", State.ALLOCATED, 10L, 100L, "payload"));
        }

        @Override
        public void forEachObjectRow(UUID projectUuid, UUID runUuid, java.util.function.Consumer<ObjectRow> consumer) {
            singleCalls++;
            list(projectUuid, runUuid).forEach(consumer);
        }

        @Override
        public void forEachObjectRowBatch(UUID projectUuid, List<UUID> runUuids,
                java.util.function.BiConsumer<UUID, ObjectRow> consumer) {
            batchCalls++;
            runUuids.forEach(runUuid -> list(projectUuid, runUuid)
                    .forEach(row -> consumer.accept(runUuid, row)));
        }
    }
}

package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.MetadataModels.DefinitionRow;
import tr.com.innova.akis.metadata.MetadataModels.DraftRow;
import tr.com.innova.akis.metadata.MetadataModels.ProjectRow;

class MetadataServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetadataService service = new MetadataService(
            null, objectMapper, new DefinitionContentValidator());

    @Test
    void validatesEveryDefinitionTypeContract() {
        Map<DefinitionType, String> validContent = Map.of(
                DefinitionType.MAPPING,
                """
                {"datasets":[{"id":"s","role":"SOURCE"},{"id":"t","role":"TARGET"}],
                 "columnMappings":[{"source":{"dataset":"s","column":"ID"},
                                    "target":{"dataset":"t","column":"ID"}}],
                 "writeStrategy":{"kind":"APPEND"}}
                """,
                DefinitionType.REUSABLE_MAPPING,
                "{\"inputs\":[],\"outputs\":[],\"nodes\":[]}",
                DefinitionType.PACKAGE,
                "{\"firstStepId\":\"START\",\"steps\":[{\"id\":\"START\",\"type\":\"MAPPING\"}],\"transitions\":[]}",
                DefinitionType.PROCEDURE,
                "{\"tasks\":[{\"id\":\"read\",\"type\":\"SQL\",\"connectionRole\":\"SOURCE\",\"riskClass\":\"READ_ONLY\",\"command\":\"select 1 from dual\"}]}",
                DefinitionType.VARIABLE,
                "{\"dataType\":\"STRING\",\"scope\":\"PROJECT\",\"historyMode\":\"NONE\",\"valueSource\":\"INPUT\"}",
                DefinitionType.SEQUENCE,
                "{\"implementation\":\"REPOSITORY\",\"start\":1,\"increment\":1,\"cycle\":false}",
                DefinitionType.USER_FUNCTION,
                "{\"returnType\":\"STRING\",\"parameters\":[],\"implementations\":[]}",
                DefinitionType.KNOWLEDGE_MODULE,
                "{\"kmType\":\"IKM\",\"tasks\":[],\"options\":[]}",
                DefinitionType.LOAD_PLAN,
                "{\"steps\":[{\"id\":\"run\",\"type\":\"SCENARIO\",\"scenarioVersionUuid\":\"0c174612-4159-44d6-82bf-bb1d2fce3089\"}],\"restartPolicy\":\"FAILED_STEP\"}"
        );

        validContent.forEach((type, content) -> assertDoesNotThrow(
                () -> service.validateContent(type, json(content)), type.name()));
        assertEquals(DefinitionType.values().length, validContent.size());
    }

    @Test
    void rejectsInvalidVariableScope() {
        ApiException exception = assertThrows(ApiException.class, () -> service.validateContent(
                DefinitionType.VARIABLE,
                json("{\"dataType\":\"STRING\",\"scope\":\"OTHER\",\"historyMode\":\"NONE\",\"valueSource\":\"INPUT\"}")));

        assertEquals("VALIDATION_FAILED", exception.code());
    }

    @Test
    void rejectsZeroSequenceIncrement() {
        assertThrows(ApiException.class, () -> service.validateContent(
                DefinitionType.SEQUENCE,
                json("{\"implementation\":\"REPOSITORY\",\"start\":1,\"increment\":0,\"cycle\":false}")));
    }

    @Test
    void canonicalizationIgnoresObjectPropertyOrderButPreservesArrayOrder() {
        JsonNode first = service.canonicalize(json("{\"b\":2,\"a\":1,\"items\":[1,2]}"));
        JsonNode reorderedProperties = service.canonicalize(
                json("{\"items\":[1,2],\"a\":1,\"b\":2}"));
        JsonNode reorderedArray = service.canonicalize(
                json("{\"items\":[2,1],\"a\":1,\"b\":2}"));

        assertEquals(first.toString(), reorderedProperties.toString());
        assertNotEquals(first.toString(), reorderedArray.toString());
    }

    @Test
    void permitsIncompleteDraftButRejectsItAtImmutableVersionBoundary() {
        StubRepository repository = new StubRepository(objectMapper);
        MetadataService draftService = new MetadataService(
                repository, objectMapper, new DefinitionContentValidator());

        DraftRow draft = assertDoesNotThrow(() -> draftService.saveDraft(
                repository.projectUuid, repository.definitionUuid, 0L, 1, json("{}")));
        assertEquals(1, draft.version());

        ApiException exception = assertThrows(ApiException.class, () -> draftService.createVersion(
                repository.projectUuid, repository.definitionUuid, draft.version(), null));
        assertEquals("VALIDATION_FAILED", exception.code());
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    private static final class StubRepository extends MetadataRepository {

        private final UUID projectUuid = UUID.randomUUID();
        private final UUID definitionUuid = UUID.randomUUID();
        private DraftRow draft;

        private StubRepository(ObjectMapper objectMapper) {
            super(null, objectMapper);
        }

        @Override
        Optional<ProjectRow> findProject(UUID uuid) {
            return projectUuid.equals(uuid)
                    ? Optional.of(new ProjectRow(
                            1, projectUuid, "TEST", "AKTIF", "Test", null, 1, null))
                    : Optional.empty();
        }

        @Override
        Optional<DefinitionRow> findDefinition(long projectId, UUID uuid) {
            return projectId == 1 && definitionUuid.equals(uuid)
                    ? Optional.of(new DefinitionRow(
                            2, 1L, definitionUuid, UUID.randomUUID(), DefinitionType.PACKAGE,
                            "PACKAGE", "AKTIF", "Package", null, 1))
                    : Optional.empty();
        }

        @Override
        Optional<DraftRow> findDraft(long definitionId) {
            return Optional.ofNullable(draft);
        }

        @Override
        DraftRow createDraft(long definitionId, int schemaVersion, JsonNode content) {
            draft = new DraftRow(UUID.randomUUID(), definitionId, schemaVersion, content, 1);
            return draft;
        }

        @Override
        void lockDefinition(long definitionId) {
            // In-memory test double: no concurrent writer exists.
        }
    }
}

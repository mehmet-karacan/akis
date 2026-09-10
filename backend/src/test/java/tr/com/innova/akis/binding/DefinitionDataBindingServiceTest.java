package tr.com.innova.akis.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tr.com.innova.akis.binding.BindingModels.BindingRow;
import tr.com.innova.akis.binding.BindingModels.CreateBinding;
import tr.com.innova.akis.binding.BindingModels.DataObjectRef;
import tr.com.innova.akis.binding.BindingModels.DefinitionVersionRef;
import tr.com.innova.akis.binding.BindingModels.ProjectRef;
import tr.com.innova.akis.binding.BindingModels.SchemaSnapshotRef;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionType;

class DefinitionDataBindingServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID DEFINITION_UUID = UUID.randomUUID();
    private static final UUID VERSION_UUID = UUID.randomUUID();
    private static final UUID DATA_OBJECT_UUID = UUID.randomUUID();
    private static final UUID SNAPSHOT_UUID = UUID.randomUUID();

    @Test
    void createsAppendOnlyBindingForMappingVersion() {
        FakeStore store = new FakeStore(DefinitionType.MAPPING);
        DefinitionDataBindingService service = new DefinitionDataBindingService(store);

        BindingRow result = service.create(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID, " SOURCE_1 ",
                BindingRole.KAYNAK, DATA_OBJECT_UUID, SNAPSHOT_UUID);

        assertEquals("SOURCE_1", store.created.nodeCode());
        assertEquals(BindingRole.KAYNAK, result.role());
        assertEquals(DATA_OBJECT_UUID, result.dataObjectUuid());
        assertEquals(SNAPSHOT_UUID, result.schemaSnapshotUuid());
    }

    @Test
    void allowsReusableMappingVersion() {
        FakeStore store = new FakeStore(DefinitionType.REUSABLE_MAPPING);
        DefinitionDataBindingService service = new DefinitionDataBindingService(store);

        service.create(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID, "OUTPUT",
                BindingRole.HEDEF, DATA_OBJECT_UUID, SNAPSHOT_UUID);

        assertEquals(BindingRole.HEDEF, store.created.role());
    }

    @Test
    void rejectsDefinitionsWithoutDataNodes() {
        DefinitionDataBindingService service = new DefinitionDataBindingService(
                new FakeStore(DefinitionType.PROCEDURE));

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID, "NODE",
                BindingRole.KAYNAK, DATA_OBJECT_UUID, SNAPSHOT_UUID));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertTrue(error.getMessage().contains("Mapping"));
    }

    @Test
    void rejectsSnapshotOwnedByAnotherDataObject() {
        FakeStore store = new FakeStore(DefinitionType.MAPPING);
        store.snapshotDataObjectId = 999;
        DefinitionDataBindingService service = new DefinitionDataBindingService(store);

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID, "NODE",
                BindingRole.KAYNAK, DATA_OBJECT_UUID, SNAPSHOT_UUID));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertTrue(error.getMessage().contains("veri nesnesine"));
    }

    @Test
    void rejectsDuplicateNodeWithinDefinitionVersion() {
        FakeStore store = new FakeStore(DefinitionType.MAPPING);
        store.duplicateNode = true;
        DefinitionDataBindingService service = new DefinitionDataBindingService(store);

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID, "NODE",
                BindingRole.KAYNAK, DATA_OBJECT_UUID, SNAPSHOT_UUID));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("BINDING_ALREADY_EXISTS", error.code());
    }

    @Test
    void hidesBindingsOutsideRequestedVersion() {
        FakeStore store = new FakeStore(DefinitionType.MAPPING);
        store.found = Optional.empty();
        DefinitionDataBindingService service = new DefinitionDataBindingService(store);

        ApiException error = assertThrows(ApiException.class, () -> service.get(
                PROJECT_UUID, DEFINITION_UUID, VERSION_UUID, UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }

    @Test
    void apiViewNeverExposesNumericInternalIdentifier() {
        assertFalse(Arrays.stream(
                        DefinitionDataBindingController.BindingView.class.getRecordComponents())
                .anyMatch(component -> component.getName().equalsIgnoreCase("id")));
    }

    private static final class FakeStore implements DefinitionDataBindingStore {

        private static final long PROJECT_ID = 10;
        private static final long VERSION_ID = 20;
        private static final long DATA_OBJECT_ID = 30;

        private final DefinitionType type;
        private long snapshotDataObjectId = DATA_OBJECT_ID;
        private boolean duplicateNode;
        private CreateBinding created;
        private Optional<BindingRow> found = Optional.of(row(UUID.randomUUID()));

        private FakeStore(DefinitionType type) {
            this.type = type;
        }

        @Override
        public Optional<ProjectRef> findProject(UUID projectUuid) {
            return projectUuid.equals(PROJECT_UUID)
                    ? Optional.of(new ProjectRef(PROJECT_ID))
                    : Optional.empty();
        }

        @Override
        public Optional<DefinitionVersionRef> findDefinitionVersion(
                long projectId, UUID definitionUuid, UUID definitionVersionUuid) {
            return projectId == PROJECT_ID
                            && definitionUuid.equals(DEFINITION_UUID)
                            && definitionVersionUuid.equals(VERSION_UUID)
                    ? Optional.of(new DefinitionVersionRef(
                            VERSION_ID, DEFINITION_UUID, VERSION_UUID, type))
                    : Optional.empty();
        }

        @Override
        public Optional<DataObjectRef> findDataObject(long projectId, UUID dataObjectUuid) {
            return Optional.of(new DataObjectRef(DATA_OBJECT_ID, DATA_OBJECT_UUID));
        }

        @Override
        public Optional<SchemaSnapshotRef> findSchemaSnapshot(
                long projectId, UUID schemaSnapshotUuid) {
            return Optional.of(new SchemaSnapshotRef(
                    40, SNAPSHOT_UUID, snapshotDataObjectId));
        }

        @Override
        public boolean nodeExists(long definitionVersionId, String nodeCode) {
            return duplicateNode;
        }

        @Override
        public BindingRow create(CreateBinding binding) {
            this.created = binding;
            return new BindingRow(
                    binding.uuid(), binding.definitionUuid(), binding.definitionVersionUuid(),
                    binding.nodeCode(), binding.role(), binding.dataObjectUuid(),
                    binding.schemaSnapshotUuid(), OffsetDateTime.now());
        }

        @Override
        public Optional<BindingRow> find(
                long projectId, long definitionVersionId, UUID bindingUuid) {
            return found;
        }

        @Override
        public List<BindingRow> list(long projectId, long definitionVersionId) {
            return found.stream().toList();
        }

        private static BindingRow row(UUID uuid) {
            return new BindingRow(
                    uuid, DEFINITION_UUID, VERSION_UUID, "NODE", BindingRole.KAYNAK,
                    DATA_OBJECT_UUID, SNAPSHOT_UUID, OffsetDateTime.now());
        }
    }
}

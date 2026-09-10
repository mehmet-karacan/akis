package tr.com.innova.akis.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConnectionVersionRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.CreateSnapshot;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.DataObjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.PhysicalSchemaRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ProjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.metadata.ApiException;

class SchemaSnapshotServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID DATA_OBJECT_UUID = UUID.randomUUID();
    private static final UUID PHYSICAL_SCHEMA_UUID = UUID.randomUUID();
    private static final UUID CONNECTION_VERSION_UUID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void apiViewsDoNotExposeInternalNumericIdentifiers() {
        List<Class<?>> views = List.of(
                SchemaSnapshotController.SnapshotView.class,
                SchemaSnapshotController.ColumnView.class,
                SchemaSnapshotController.ConstraintView.class);

        for (Class<?> view : views) {
            assertTrue(Arrays.stream(view.getRecordComponents())
                    .noneMatch(component -> component.getName().equalsIgnoreCase("id")));
        }
    }

    @Test
    void createsCanonicalAppendOnlySnapshotWithCalculatedFingerprint() {
        FakeStore store = new FakeStore(31, 31);
        SchemaSnapshotService service = new SchemaSnapshotService(store, objectMapper);

        SnapshotRow result = service.create(
                PROJECT_UUID, DATA_OBJECT_UUID, PHYSICAL_SCHEMA_UUID,
                CONNECTION_VERSION_UUID, " 19c ", OffsetDateTime.parse("2026-09-10T12:00:00Z"),
                1, objectMapper.createObjectNode().put("z", 2).put("a", 1),
                List.of(column(" NAME ", 2), column("ID", 1)),
                List.of(constraint("PK_TEST", List.of("ID"))));

        assertNotNull(store.created);
        assertEquals("19c", store.created.engineVersion());
        assertEquals("ID", store.created.columns().get(1).reference());
        assertEquals(64, result.fingerprint().length());
        assertTrue(result.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(List.of("ID"), store.created.constraints().getFirst().columnReferences());
    }

    @Test
    void rejectsConnectionVersionOwnedByAnotherConnection() {
        SchemaSnapshotService service = new SchemaSnapshotService(
                new FakeStore(31, 77), objectMapper);

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                PROJECT_UUID, DATA_OBJECT_UUID, PHYSICAL_SCHEMA_UUID,
                CONNECTION_VERSION_UUID, "19c", OffsetDateTime.now(), 1,
                objectMapper.createObjectNode(), List.of(column("ID", 1)), List.of()));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertTrue(error.getMessage().contains("aynı bağlantıya"));
    }

    @Test
    void rejectsConstraintColumnOutsideSnapshot() {
        SchemaSnapshotService service = new SchemaSnapshotService(
                new FakeStore(31, 31), objectMapper);

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                PROJECT_UUID, DATA_OBJECT_UUID, PHYSICAL_SCHEMA_UUID,
                CONNECTION_VERSION_UUID, "19c", OffsetDateTime.now(), 1,
                objectMapper.createObjectNode(), List.of(column("ID", 1)),
                List.of(constraint("PK_TEST", List.of("OTHER_ID")))));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertTrue(error.getMessage().contains("ait olmayan"));
    }

    @Test
    void hidesSnapshotBelongingToAnotherDataObject() {
        FakeStore store = new FakeStore(31, 31);
        store.returnedDataObjectUuid = UUID.randomUUID();
        SchemaSnapshotService service = new SchemaSnapshotService(store, objectMapper);

        ApiException error = assertThrows(ApiException.class, () -> service.get(
                PROJECT_UUID, DATA_OBJECT_UUID, UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }

    private ColumnInput column(String reference, int ordinal) {
        return new ColumnInput(
                reference, "number", "decimal", ordinal, 18, 0, null, null,
                false, null, reference.trim());
    }

    private ConstraintInput constraint(String reference, List<String> columns) {
        return new ConstraintInput(
                reference, "pk", true, 1, objectMapper.createObjectNode(), reference, columns);
    }

    private static final class FakeStore implements SchemaSnapshotStore {

        private final long physicalConnectionId;
        private final long versionConnectionId;
        private CreateSnapshot created;
        private UUID returnedDataObjectUuid = DATA_OBJECT_UUID;

        private FakeStore(long physicalConnectionId, long versionConnectionId) {
            this.physicalConnectionId = physicalConnectionId;
            this.versionConnectionId = versionConnectionId;
        }

        @Override
        public Optional<ProjectRef> findProject(UUID projectUuid) {
            return Optional.of(new ProjectRef(10));
        }

        @Override
        public Optional<DataObjectRef> findDataObject(long projectId, UUID dataObjectUuid) {
            return Optional.of(new DataObjectRef(20, DATA_OBJECT_UUID));
        }

        @Override
        public Optional<PhysicalSchemaRef> findPhysicalSchema(
                long projectId, UUID physicalSchemaUuid) {
            return Optional.of(new PhysicalSchemaRef(
                    30, PHYSICAL_SCHEMA_UUID, physicalConnectionId));
        }

        @Override
        public Optional<ConnectionVersionRef> findConnectionVersion(
                long projectId, UUID connectionVersionUuid) {
            return Optional.of(new ConnectionVersionRef(
                    40, CONNECTION_VERSION_UUID, versionConnectionId));
        }

        @Override
        public SnapshotRow create(CreateSnapshot snapshot) {
            this.created = snapshot;
            return row(snapshot.uuid(), snapshot.dataObjectUuid(), snapshot.fingerprint());
        }

        @Override
        public Optional<SnapshotRow> find(long projectId, UUID snapshotUuid) {
            return Optional.of(row(snapshotUuid, returnedDataObjectUuid, "0".repeat(64)));
        }

        @Override
        public List<SnapshotRow> list(long projectId, UUID dataObjectUuid) {
            return List.of();
        }

        private SnapshotRow row(UUID uuid, UUID dataObjectUuid, String fingerprint) {
            return new SnapshotRow(
                    50, uuid, dataObjectUuid, PHYSICAL_SCHEMA_UUID,
                    CONNECTION_VERSION_UUID, fingerprint, "19c", OffsetDateTime.now(),
                    1, new ObjectMapper().createObjectNode(), OffsetDateTime.now(),
                    List.of(), List.of());
        }
    }
}

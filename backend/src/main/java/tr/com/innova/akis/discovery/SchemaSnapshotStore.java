package tr.com.innova.akis.discovery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConnectionVersionRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.CreateSnapshot;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.DataObjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.PhysicalSchemaRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ProjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;

interface SchemaSnapshotStore {

    Optional<ProjectRef> findProject(UUID projectUuid);

    Optional<DataObjectRef> findDataObject(long projectId, UUID dataObjectUuid);

    Optional<PhysicalSchemaRef> findPhysicalSchema(long projectId, UUID physicalSchemaUuid);

    Optional<ConnectionVersionRef> findConnectionVersion(
            long projectId, UUID connectionVersionUuid);

    SnapshotRow create(CreateSnapshot snapshot);

    Optional<SnapshotRow> find(long projectId, UUID snapshotUuid);

    List<SnapshotRow> list(long projectId, UUID dataObjectUuid);
}

package tr.com.innova.akis.binding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.binding.BindingModels.BindingRow;
import tr.com.innova.akis.binding.BindingModels.BindingCandidateRow;
import tr.com.innova.akis.binding.BindingModels.CreateBinding;
import tr.com.innova.akis.binding.BindingModels.DataObjectRef;
import tr.com.innova.akis.binding.BindingModels.DefinitionVersionRef;
import tr.com.innova.akis.binding.BindingModels.ProjectRef;
import tr.com.innova.akis.binding.BindingModels.SchemaSnapshotRef;

interface DefinitionDataBindingStore {

    Optional<ProjectRef> findProject(UUID projectUuid);

    Optional<DefinitionVersionRef> findDefinitionVersion(
            long projectId, UUID definitionUuid, UUID definitionVersionUuid);

    Optional<DataObjectRef> findDataObject(long projectId, UUID dataObjectUuid);

    Optional<SchemaSnapshotRef> findSchemaSnapshot(long projectId, UUID schemaSnapshotUuid);

    boolean nodeExists(long definitionVersionId, String nodeCode);

    BindingRow create(CreateBinding binding);

    Optional<BindingRow> find(
            long projectId, long definitionVersionId, UUID bindingUuid);

    List<BindingRow> list(long projectId, long definitionVersionId);

    List<BindingCandidateRow> listTrustedSnapshotCandidates(long projectId);
}

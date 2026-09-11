package tr.com.innova.akis.execution;

/** Adapts a verified Procedure binding to the shared Oracle session contract. */
final class ProcedureOracleBindingAdapter {

    private ProcedureOracleBindingAdapter() {
    }

    static PilotRuntimePlan.DatasetBinding source(
            ProcedureRuntimePlan.TaskBinding binding) {
        if (binding == null
                || binding.role() != ProcedureRuntimePlan.ConnectionRole.SOURCE
                || !"TABLO".equals(binding.dataObjectType())) {
            throw new IllegalArgumentException("Procedure source binding is invalid.");
        }
        return new PilotRuntimePlan.DatasetBinding(
                binding.taskId(),
                PilotRuntimePlan.DatasetRole.SOURCE,
                PilotRuntimePlan.DatabaseType.ORACLE,
                PilotRuntimePlan.DataObjectType.TABLE,
                binding.definitionDataObjectUuid(),
                binding.dataObjectUuid(),
                binding.environmentSchemaBindingUuid(),
                binding.physicalSchemaUuid(),
                binding.connectionVersionUuid(),
                binding.schemaSnapshotUuid(),
                binding.bindingVersion(),
                binding.schemaSnapshotFingerprint(),
                binding.physicalIdentity(),
                binding.owner(),
                binding.objectName());
    }

    static PilotRuntimePlan.DatasetBinding target(
            ProcedureRuntimePlan.TaskBinding binding) {
        if (binding == null
                || binding.role() != ProcedureRuntimePlan.ConnectionRole.TARGET
                || !"TABLO".equals(binding.dataObjectType())) {
            throw new IllegalArgumentException("Procedure target binding is invalid.");
        }
        return new PilotRuntimePlan.DatasetBinding(
                binding.taskId(),
                PilotRuntimePlan.DatasetRole.TARGET,
                PilotRuntimePlan.DatabaseType.ORACLE,
                PilotRuntimePlan.DataObjectType.TABLE,
                binding.definitionDataObjectUuid(),
                binding.dataObjectUuid(),
                binding.environmentSchemaBindingUuid(),
                binding.physicalSchemaUuid(),
                binding.connectionVersionUuid(),
                binding.schemaSnapshotUuid(),
                binding.bindingVersion(),
                binding.schemaSnapshotFingerprint(),
                binding.physicalIdentity(),
                binding.owner(),
                binding.objectName());
    }
}

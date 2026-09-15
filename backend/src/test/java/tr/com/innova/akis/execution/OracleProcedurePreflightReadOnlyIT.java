package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Explicit live diagnostic: catalog/identity SELECTs only; never submits or executes a run. */
class OracleProcedurePreflightReadOnlyIT {

    @Test
    void verifiesPublishedTargetWithoutExecutingProcedure() {
        String id = System.getenv("AKIS_LIVE_PREFLIGHT_RUN_UUID");
        org.junit.jupiter.api.Assumptions.assumeTrue(id != null && !id.isBlank());
        var ds = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
        var jdbc = JdbcClient.create(ds);
        var mapper = new ObjectMapper();
        var executions = new JdbcPinnedExecutionContextStore(jdbc, mapper);
        var plans = new ProcedureRuntimePlanResolver(mapper, new tr.com.innova.akis.projectbundle.SecretValueSanitizer(),
            new tr.com.innova.akis.metadata.DefinitionContentValidator());
        var snapshots = new JdbcPinnedSchemaSnapshotStore(jdbc, mapper);
        var connections = new RuntimeOracleConnectionProvider(new JdbcRuntimeOracleConnectionMetadataStore(jdbc, mapper), mapper,
            System::getenv, (url, properties) -> {
                try { return java.sql.DriverManager.getConnection(url, properties); }
                catch (java.sql.SQLException failure) {
                    // Do not expose the JDBC URL, credentials, or vendor message.
                    throw new AssertionError("Oracle connect failed: vendorCode=" + failure.getErrorCode()
                        + ", sqlState=" + failure.getSQLState());
                }
            }, Runnable::run);
        var context = executions.find(UUID.fromString(id)).orElseThrow();
        var plan = plans.resolve(context.releaseHash(), context.planHash(), context.scenarioPlan(), context.physicalManifest());
        var task = plan.tasks().stream().filter(t -> t.connectionRole() == ProcedureRuntimePlan.ConnectionRole.TARGET).findFirst().orElseThrow();
        var binding = plan.bindings().get(task.id());
        var pinned = snapshots.loadProcedureTarget(plan, task, binding, "AKTIF");
        var adapted = ProcedureOracleBindingAdapter.target(binding);
        try (var session = connections.openTargetIdentityRead(adapted)) {
            var targetPlan = new PilotRuntimePlan(PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(), plan.releaseHash(), plan.scenarioPlanHash(),
                plan.definitionUuid(), plan.definitionVersionUuid(), 1, null, adapted,
                List.of(), PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT, plan.canonicalPlan());
            new JdbcOracleSchemaPreflight(mapper).verifyTarget(targetPlan, session.connection(),
                new JdbcOracleSchemaPreflight.ExpectedSnapshot(pinned.snapshot().schemaSnapshotUuid(), pinned.snapshot().body()));
            new JdbcOracleTargetIdentityReader().read(session.connection(), binding.owner(), "TABLE", binding.objectName());
        }
    }
}

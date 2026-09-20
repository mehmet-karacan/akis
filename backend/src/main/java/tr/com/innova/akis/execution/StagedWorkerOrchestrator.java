package tr.com.innova.akis.execution;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.*;
import static tr.com.innova.akis.execution.ProcedureWorkerOrchestrator.*;
import static tr.com.innova.akis.execution.RunExecutionTransitionPort.*;
import static tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;

/** Executes one already-claimed run. Never claims again or retries a target publication. */
@Component
final class StagedWorkerOrchestrator {
    private final StagedRuntimePlanResolver plans;
    private final JdbcPinnedSchemaSnapshotStore snapshots;
    private final RuntimeOracleConnectionProvider connections;
    private final StagedWorkSessionFactory workSessions;
    private final WorkerLeaseService leases;
    private final OracleTargetFencePort fences;
    private final RunExecutionTransitionPort transitions;
    private final StagedPublishFacade publisher;
    private final WorkObjectStore objects;
    private final WorkAreaPolicyService policies;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final KmStepJournal journal;
    StagedWorkerOrchestrator(StagedRuntimePlanResolver plans,JdbcPinnedSchemaSnapshotStore snapshots,RuntimeOracleConnectionProvider connections,
            StagedWorkSessionFactory workSessions,WorkerLeaseService leases,OracleTargetFencePort fences,RunExecutionTransitionPort transitions,
            StagedPublishFacade publisher,WorkObjectStore objects,WorkAreaPolicyService policies,ObjectMapper mapper,KmStepJournal journal,
            @Value("${akis.execution.staged-runtime-enabled:false}") boolean enabled) {
        this.plans=plans;this.snapshots=snapshots;this.connections=connections;this.workSessions=workSessions;this.leases=leases;
        this.fences=fences;this.transitions=transitions;this.publisher=publisher;this.objects=objects;this.policies=policies;this.mapper=mapper;this.enabled=enabled;this.journal=journal;
    }
    RunOnceResult run(PinnedExecutionContextPort.PinnedExecutionContext context,LeaseGate gate) {
        StagedRuntimePlan plan;
        PinnedSchemaSnapshotPort.PinnedSnapshots pinned;
        StagedColumnLayout layout;
        try {
            if(!enabled) throw new IllegalStateException();
            plan=plans.resolve(context.releaseHash(),context.planHash(),context.scenarioPlan(),context.physicalManifest());
            pinned=snapshots.load(plan);layout=StagedColumnLayout.create(plan,pinned.target().body());
            if(!pinned.projectUuid().equals(plan.projectUuid()) || !pinned.publicationUuid().equals(context.publicationUuid())) throw new IllegalStateException();
        } catch(RuntimeException failure) { failPreflight(gate);return new FailedSafely("KM_PREFLIGHT_REJECTED"); }
        RuntimeOracleSession source=null,control=null,data=null;
        RunLeasePort.TargetFenceToken targetFence=null;
        var intent=new AtomicReference<PublishIntentEvidence>();
        try {
            source=connections.openSource(plan.source());
            String databaseIdentity;
            OracleTargetIdentityV1.CanonicalTargetIdentity identity;
            try(var target=connections.openTargetIdentityRead(plan.target())) {
                new JdbcOracleSchemaPreflight(mapper).verifyStaged(plan,source.connection(),pinned,target.connection());
                databaseIdentity=OracleWorkTableManager.databaseIdentity(target.connection());
                identity=new JdbcOracleTargetIdentityReader().read(target.connection(),plan.target().owner(),"TABLE",plan.target().objectName());
            }
            gate.checkpoint();
            control=workSessions.open(plan,true);data=workSessions.open(plan,false);
            if(!databaseIdentity.equals(OracleWorkTableManager.databaseIdentity(control.connection()))
                    || !databaseIdentity.equals(OracleWorkTableManager.databaseIdentity(data.connection()))) throw new IllegalStateException();
            targetFence=gate.execute(run->leases.acquireTarget(run,identity.canonicalTargetHash(),identity.targetIdentityVersion()));
            var fence=targetFence;
            var fenceResult=fences.acquire(new OracleTargetFencePort.OracleTargetFenceCommand(plan,context,fence));
            if(!(fenceResult instanceof OracleTargetFencePort.CommitConfirmed)) throw new IllegalStateException();
            requireAccepted(gate.execute(run->transitions.completePreflight(new ActiveExecutionToken(run,fence))));
            var stage=plan.staging();var prefix=stage.path("prefixes");
            var owner=new WorkObjectStore.Owner(plan.projectUuid(),context.runUuid(),fence.runGeneration(),fence.workerReference());
            String workName=new WorkObjectPrefixes(prefix.path("loading").asText(),prefix.path("integration").asText(),prefix.path("error").asText())
                    .objectName("LOADING",plan.projectUuid(),context.runUuid(),fence.runGeneration(),"WORK_SOURCE_1");
            var table=new JdbcStagingTransfer.Table(stage.path("owner").asText(),workName);
            var workArea=new WorkObjectStore.WorkArea(UUID.fromString(stage.path("physicalSchemaUuid").asText()),stage.path("workAreaPolicy").path("version").asLong());
            var manager=new OracleWorkTableManager(objects);
            var workConnection=control.connection();
            OracleKmRuntime.Guard guard=new OracleKmRuntime.Guard() {
                public void preflight() {
                    var policy=policies.get(plan.projectUuid(),workArea.physicalSchemaUuid());
                    if(policy.version()!=workArea.policyVersion()) throw new IllegalStateException("Çalışma politikası değişmiş.");
                    WorkAreaPolicyService.requireAllowed(policy,plan.definition().options(),table.owner().equals(plan.target().owner()));
                }
                public void checkpoint() { gate.checkpoint(); }
                public void verifyWork(OracleWorkTableManager.Created object) {
                    try { manager.verify(workConnection,object,30); }
                    catch(SQLException failure) { throw new IllegalStateException("Çalışma nesnesi doğrulanamadı."); }
                }
            };
            var created=new AtomicReference<OracleWorkTableManager.Created>();
            List<JdbcStagingTransfer.QuerySource> querySources;
            if(plan.definition().sources().isEmpty()) querySources=List.of(new JdbcStagingTransfer.QuerySource(plan.source().datasetId(),plan.source().datasetId(),new JdbcStagingTransfer.Table(plan.source().owner(),plan.source().objectName())));
            else querySources=plan.definition().sources().stream().map(reference->{
                var binding=plan.sources().stream().filter(candidate->candidate.datasetId().equals(reference.id())).findFirst().orElseThrow();
                var columns=pinned.sources().get(reference.id()).body().columns().stream().map(tr.com.innova.akis.discovery.SchemaFingerprintInput.Column::reference).collect(java.util.stream.Collectors.toSet());
                return new JdbcStagingTransfer.QuerySource(reference.id(),reference.alias(),new JdbcStagingTransfer.Table(binding.owner(),binding.objectName()),columns);
            }).toList();
            var contract=new OracleKmRuntime.Contract(owner,plan.program(),databaseIdentity,plan.target().owner(),
                    new JdbcStagingTransfer.Table(plan.source().owner(),plan.source().objectName()),table,layout.work(),layout.transfer(),
                    plan.definition().options(),layout.quality(),30,workArea,
                    new JdbcStagingTransfer.QueryOptions(plan.definition().booleanOption("loading","DISTINCT"),plan.definition().stringOption("loading","ORACLE_HINT"),querySources,plan.definition().joins(),plan.definition().filters()));
            var runtime=new OracleKmRuntime(contract,source.connection(),control.connection(),data.connection(),StagedWorkSessionFactory.transaction(data),
                    manager,objects,new JdbcStagingTransfer(),new JdbcWorkQualityChecks(),guard,(object,seal)->{
                        created.set(object);
                        var evidence=new PublishIntentEvidence(plan.runtimePlanHash(),StagedPublishFacade.publishKey(plan,context,fence),seal.payloadHash(),seal.rows(),seal.logicalBytes());
                        intent.set(evidence);
                        requireAccepted(gate.execute(run->transitions.beginPublish(new ActiveExecutionToken(run,fence),evidence)));
                        return publisher.publish(plan,context,fence,object,seal,()->gate.checkpoint());
                    });
            journal.prepare(owner,plan.runtimePlanHash(),plan.program());
            var results=AkisKmInterpreter.execute(plan.modules(),runtime,journal.observer(owner));
            var evidence=Objects.requireNonNull(intent.get());
            requireAccepted(gate.completeTerminal(run->transitions.completeSuccessfully(new ActiveExecutionToken(run,fence),evidence),StagedWorkerOrchestrator::accepted));
            int warnings=0;
            try { manager.cleanup(control.connection(),owner,created.get().uuid(),30); }
            catch(RuntimeException cleanupFailure) { warnings=1; }
            return new Succeeded(results.size(),warnings,evidence.rowCount(),evidence.byteCount());
        } catch(SQLException | RuntimeException failure) {
            var fence=targetFence;
            // Once intent is persisted, conservative reconciliation takes precedence over a blind retry.
            if(intent.get()!=null && fence!=null) {
                try { gate.completeTerminal(run->transitions.markOutcomeUnknown(new ActiveExecutionToken(run,fence)),StagedWorkerOrchestrator::accepted); } catch(RuntimeException ignored) { }
                return new OutcomeUnknown("KM_RECONCILIATION_REQUIRED");
            }
            if(fence==null) failPreflight(gate);
            else try { gate.completeTerminal(run->transitions.failSafely(new ActiveExecutionToken(run,fence),"KM_EXECUTION_REJECTED"),StagedWorkerOrchestrator::accepted); } catch(RuntimeException ignored) { }
            return new FailedSafely("KM_EXECUTION_REJECTED");
        } finally { close(data);close(control);close(source); }
    }
    private void failPreflight(LeaseGate gate) {
        try { gate.completeTerminal(run->transitions.failPreflightSafely(run,"KM_PREFLIGHT_REJECTED"),StagedWorkerOrchestrator::accepted); } catch(RuntimeException ignored) { }
    }
    private static boolean accepted(MutationResult result) { return result!=null && result.outcome()==MutationOutcome.ACCEPTED; }
    private static void requireAccepted(MutationResult result) { if(!accepted(result)) throw new IllegalStateException("KM çalışma yetkisi reddedildi."); }
    private static void close(RuntimeOracleSession session) { if(session!=null) try { session.close(); } catch(RuntimeException ignored) { } }
}

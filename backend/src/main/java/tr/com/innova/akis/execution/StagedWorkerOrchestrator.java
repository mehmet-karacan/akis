package tr.com.innova.akis.execution;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.*;
import static tr.com.innova.akis.execution.ProcedureWorkerOrchestrator.*;
import static tr.com.innova.akis.execution.RunExecutionTransitionPort.*;
import static tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;

/** Executes one already-claimed run. Never claims again or retries a target publication. */
@Component
final class StagedWorkerOrchestrator {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(StagedWorkerOrchestrator.class);
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
    private final PinnedExecutionContextPort contexts;
    StagedWorkerOrchestrator(StagedRuntimePlanResolver plans,JdbcPinnedSchemaSnapshotStore snapshots,RuntimeOracleConnectionProvider connections,
            StagedWorkSessionFactory workSessions,WorkerLeaseService leases,OracleTargetFencePort fences,RunExecutionTransitionPort transitions,
            StagedPublishFacade publisher,WorkObjectStore objects,WorkAreaPolicyService policies,ObjectMapper mapper,KmStepJournal journal,
            PinnedExecutionContextPort contexts,@Value("${akis.execution.staged-runtime-enabled:false}") boolean enabled,
            @Value("${akis.execution.staged-fault-injection:}") String faultInjection) {
        this.plans=plans;this.snapshots=snapshots;this.connections=connections;this.workSessions=workSessions;this.leases=leases;
        this.fences=fences;this.transitions=transitions;this.publisher=publisher;this.objects=objects;this.policies=policies;this.mapper=mapper;this.enabled=enabled;this.journal=journal;
        this.contexts=contexts;this.faultInjection=faultInjection==null?"":faultInjection.trim();
    }
    /**
     * Test-only: "PUBLISH" fails the target publication after the publish intent is recorded (outcome unknown, reconcile first);
     * "BEFORE_PUBLISH" fails once the work table is sealed but before any intent (plain failure, RESUME adopts the work table).
     */
    private final String faultInjection;
    /** What a RESUME attempt can adopt from the failed attempt: the sealed work table and the steps completed before it. */
    record ResumePoint(UUID previousRun,WorkObjectStore.ObjectRow sealed,int completedSteps) { }
    private Optional<ResumePoint> resumePoint(StagedRuntimePlan plan,PinnedExecutionContextPort.PinnedExecutionContext context) {
        var previous=contexts.resumeOrigin(context.runUuid());
        if(previous.isEmpty()) return Optional.empty();
        var rows=journal.list(plan.projectUuid(),previous.get());
        long generation=rows.stream().mapToLong(KmStepJournal.Row::generation).max().orElse(0);
        var recorded=rows.stream().filter(r->r.generation()==generation).toList();
        var steps=plan.program().steps();
        int completed=0;
        for(int i=0;i<Math.min(recorded.size(),steps.size());i++) {
            var actual=recorded.get(i);var expected=steps.get(i);
            if(actual.ordinal()!=i+1 || !actual.stepCode().equals(expected.id()) || !actual.operation().equals(expected.operation().name())
                    || !Set.of("SUCCEEDED","SKIPPED").contains(actual.state())) break;
            if(expected.operation()==AkisKmLanguage.Operation.ATOMIC_REPLACE) break;
            completed=i+1;
        }
        boolean sealedReached=steps.subList(0,completed).stream().anyMatch(s->s.operation()==AkisKmLanguage.Operation.SEAL_WORK);
        var sealed=objects.list(plan.projectUuid(),previous.get()).stream()
                .filter(row->row.state()==WorkObjectLifecycle.State.SEALED && "WORK_SOURCE_1".equals(row.slot()) && row.objectId()!=null && row.payloadHash()!=null)
                .findFirst();
        if(!sealedReached || sealed.isEmpty()) return Optional.empty();
        return Optional.of(new ResumePoint(previous.get(),sealed.get(),completed));
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
        } catch(RuntimeException failure) {
            LOG.warn("KM run {} preflight rejected: {}{}",context.runUuid(),failure.toString(),failure.getCause()==null?"":" <- "+failure.getCause());
            failPreflight(gate);return new FailedSafely("KM_PREFLIGHT_REJECTED");
        }
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
            // ODI-style name after the target (AKIS_C$_<TARGET>); a table an earlier attempt still holds gets _2, _3…
            var prefixes=new WorkObjectPrefixes(prefix.path("loading").asText(),prefix.path("integration").asText(),prefix.path("error").asText());
            int nameLimit=Math.max(30,Math.min(128,control.connection().getMetaData().getMaxTableNameLength()));
            String workPattern=plan.definition().stringOption("loading","WORK_TABLE_PATTERN");
            String firstSource=plan.sources().isEmpty()?plan.source().objectName():plan.sources().getFirst().objectName();
            String workName=prefixes.patternObjectName("LOADING",workPattern,plan.target().objectName(),firstSource,"WORK_SOURCE_1",0,nameLimit);
            for(int sequence=2;objects.nameInUse(databaseIdentity,stage.path("owner").asText(),workName);sequence++) {
                if(sequence>99) throw new IllegalStateException("Hedef için çok fazla bekleyen çalışma tablosu var; önce temizleyin.");
                workName=prefixes.patternObjectName("LOADING",workPattern,plan.target().objectName(),firstSource,"WORK_SOURCE_1",sequence,nameLimit);
            }
            // RESUME: the adopted table keeps the name of the attempt that created it.
            var resume=resumePoint(plan,context);
            if(resume.isPresent()) {
                var adopted=resume.get().sealed();
                if(!adopted.owner().equals(stage.path("owner").asText()) || !adopted.databaseIdentity().equals(databaseIdentity)) throw new IllegalStateException("Devralınacak çalışma tablosu bu hedefe ait değil.");
                workName=adopted.name();
            }
            var table=new JdbcStagingTransfer.Table(stage.path("owner").asText(),workName);
            var workArea=new WorkObjectStore.WorkArea(UUID.fromString(stage.path("physicalSchemaUuid").asText()),stage.path("workAreaPolicy").path("version").asLong());
            var manager=new OracleWorkTableManager(objects);
            var workConnection=control.connection();
            StagedKmRuntime.Guard guard=new StagedKmRuntime.Guard() {
                public void preflight() {
                    var policy=policies.get(plan.projectUuid(),workArea.physicalSchemaUuid());
                    if(policy.version()!=workArea.policyVersion()) throw new IllegalStateException("Çalışma politikası değişmiş.");
                    WorkAreaPolicyService.requireAllowed(policy,plan.definition().options(),table.owner().equals(plan.target().owner()));
                }
                public void checkpoint() { gate.checkpoint(); }
                public void verifyWork(WorkTableManagerPort.Created object) {
                    try { manager.verify(workConnection,object,30); }
                    catch(SQLException failure) { throw new IllegalStateException("Çalışma nesnesi doğrulanamadı."); }
                }
            };
            var created=new AtomicReference<WorkTableManagerPort.Created>();
            List<JdbcStagingTransfer.QuerySource> querySources;
            if(plan.definition().sources().isEmpty()) querySources=List.of(new JdbcStagingTransfer.QuerySource(plan.source().datasetId(),plan.source().datasetId(),new JdbcStagingTransfer.Table(plan.source().owner(),plan.source().objectName())));
            else querySources=plan.definition().sources().stream().map(reference->{
                var binding=plan.sources().stream().filter(candidate->candidate.datasetId().equals(reference.id())).findFirst().orElseThrow();
                var columns=pinned.sources().get(reference.id()).body().columns().stream().map(tr.com.innova.akis.discovery.SchemaFingerprintInput.Column::reference).collect(java.util.stream.Collectors.toSet());
                return new JdbcStagingTransfer.QuerySource(reference.id(),reference.alias(),new JdbcStagingTransfer.Table(binding.owner(),binding.objectName()),columns);
            }).toList();
            // Per-run batch override (job parameter) replaces the pinned batch/fetch size; Options re-validates the bounds.
            var pinnedOptions=plan.definition().options();
            int batchOverride=contexts.jobParameters(context.runUuid()).path("batchRows").asInt(0);
            var options=batchOverride>0?new StagedMappingDefinition.Options(batchOverride,batchOverride,pinnedOptions.maxRows(),pinnedOptions.maxBytes(),pinnedOptions.allowEmptySource()):pinnedOptions;
            if(batchOverride>0) LOG.info("KM run {} uses batch override {} (pinned {})",context.runUuid(),batchOverride,pinnedOptions.batchRows());
            // Pinned column protection: encrypt marked source columns while they are read into the work table.
            JsonNode sensitive=context.physicalManifest().path("sensitiveColumns");
            var transferColumns=layout.transfer().stream().map(column->{
                if(column.expression()!=null || column.sourceObject()==null) return column;
                for(JsonNode name:sensitive.path(column.sourceObject())) if(name.asText().equalsIgnoreCase(column.source())) return column.protectedColumn();
                return column;
            }).toList();
            var contract=new StagedKmRuntime.Contract(owner,plan.program(),databaseIdentity,plan.target().owner(),
                    new JdbcStagingTransfer.Table(plan.source().owner(),plan.source().objectName()),table,layout.work(),transferColumns,
                    options,layout.quality(),30,workArea,
                    new JdbcStagingTransfer.QueryOptions(plan.definition().booleanOption("loading","DISTINCT"),plan.definition().stringOption("loading","ORACLE_HINT"),querySources,plan.definition().joins(),plan.definition().filters()));
            var runtime=new StagedKmRuntime(contract,source.connection(),control.connection(),data.connection(),StagedWorkSessionFactory.transaction(data),
                    manager,objects,new JdbcStagingTransfer(),new JdbcWorkQualityChecks(),guard,(object,seal)->{
                        created.set(object);
                        var evidence=new PublishIntentEvidence(plan.runtimePlanHash(),StagedPublishFacade.publishKey(plan,context,fence),seal.payloadHash(),seal.rows(),seal.logicalBytes());
                        if("BEFORE_PUBLISH".equals(faultInjection) && contexts.resumeOrigin(context.runUuid()).isEmpty()) throw new IllegalStateException("Test hatası: hedef yazımı kasıtlı olarak başarısız (niyet öncesi).");
                        intent.set(evidence);
                        requireAccepted(gate.execute(run->transitions.beginPublish(new ActiveExecutionToken(run,fence),evidence)));
                        if("PUBLISH".equals(faultInjection) && contexts.resumeOrigin(context.runUuid()).isEmpty()) throw new IllegalStateException("Test hatası: hedef yayını kasıtlı olarak başarısız.");
                        return publisher.publish(plan,context,fence,object,seal,()->gate.checkpoint());
                    });
            journal.prepare(owner,plan.runtimePlanHash(),plan.program());
            var resumeFrom=AkisKmInterpreter.Resume.NONE;
            if(resume.isPresent()) {
                var point=resume.get();
                if(!point.sealed().structureHash().equals(OracleWorkStructure.expected(layout.work()))) throw new IllegalStateException("Devralınan çalışma tablosunun yapısı plandan farklı.");
                var owned=objects.adopt(owner,point.previousRun(),point.sealed().uuid());
                var adoptedTable=new WorkTableManagerPort.Created(owned.uuid(),owned.databaseIdentity(),table,owned.objectId(),owned.structureHash());
                created.set(adoptedTable);
                runtime.adopt(adoptedTable,new JdbcStagingTransfer.Result(owned.rows(),owned.bytes(),owned.payloadHash()));
                resumeFrom=new AkisKmInterpreter.Resume(point.completedSteps(),owned.rows());
            }
            var results=AkisKmInterpreter.execute(plan.modules(),runtime,journal.observer(owner),resumeFrom);
            var evidence=Objects.requireNonNull(intent.get());
            requireAccepted(gate.completeTerminal(run->transitions.completeSuccessfully(new ActiveExecutionToken(run,fence),evidence),StagedWorkerOrchestrator::accepted));
            int warnings=0;
            try { manager.cleanup(control.connection(),owner,created.get().uuid(),30); }
            catch(RuntimeException cleanupFailure) { warnings=1; }
            return new Succeeded(results.size(),warnings,evidence.rowCount(),evidence.byteCount());
        } catch(SQLException | RuntimeException failure) {
            var fence=targetFence;
            LOG.warn("KM run {} failed {} target fence: {}{}",context.runUuid(),fence==null?"before":"after",failure.toString(),failure.getCause()==null?"":" <- "+failure.getCause());
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

package tr.com.innova.akis.execution;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tr.com.innova.akis.knowledge.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StagedPublishGuardTest {
    private final JsonMapper mapper=new JsonMapper();
    private final RuntimeOracleConnectionProvider connections=mock(RuntimeOracleConnectionProvider.class);
    private final PilotPublishIntentPort intents=mock(PilotPublishIntentPort.class);
    private final WorkObjectStore objects=mock(WorkObjectStore.class);
    private final JdbcPinnedSchemaSnapshotStore snapshots=mock(JdbcPinnedSchemaSnapshotStore.class);
    private final WorkAreaPolicyService policies=mock(WorkAreaPolicyService.class);
    private final KmStepJournal journal=mock(KmStepJournal.class);
    private final StagedRuntimePlan plan=mock(StagedRuntimePlan.class);
    private final PinnedExecutionContextPort.PinnedExecutionContext execution=mock(PinnedExecutionContextPort.PinnedExecutionContext.class);
    private final RunLeasePort.TargetFenceToken fence=mock(RunLeasePort.TargetFenceToken.class);
    private final UUID project=UUID.randomUUID(),run=UUID.randomUUID(),publication=UUID.randomUUID(),job=UUID.randomUUID(),target=UUID.randomUUID(),schema=UUID.randomUUID();
    private final String hash="a".repeat(64);
    private OracleWorkTableManager.Created object;
    private final JdbcStagingTransfer.Result seal=new JdbcStagingTransfer.Result(1201,24020,"b".repeat(64));
    private final AkisKmInterpreter.Plan program=AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(AkisKmLanguage.example(AkisKmLanguage.Kind.LKM),AkisKmLanguage.example(AkisKmLanguage.Kind.CKM),AkisKmLanguage.example(AkisKmLanguage.Kind.IKM)));
    private StagedPublishFacade facade() { return new StagedPublishFacade(connections,intents,mock(OracleTargetLedgerPort.class),objects,snapshots,mapper,policies,journal); }
    private void fixture() {
        when(plan.projectUuid()).thenReturn(project);when(plan.runtimePlanHash()).thenReturn(hash);when(plan.releaseHash()).thenReturn(hash);when(plan.scenarioPlanHash()).thenReturn(hash);
        when(plan.program()).thenReturn(program);
        var targetBinding=mock(PilotRuntimePlan.DatasetBinding.class);when(targetBinding.owner()).thenReturn("DATA");when(plan.target()).thenReturn(targetBinding);
        when(plan.definition()).thenReturn(new StagedMappingDefinition(UUID.randomUUID(),Map.of(),new StagedMappingDefinition.Options(500,500,2000,100000,false)));
        var stage=mapper.createObjectNode().put("owner","WORK").put("physicalSchemaUuid",schema.toString());
        stage.set("prefixes",mapper.valueToTree(WorkObjectPrefixes.DEFAULTS));stage.putObject("workAreaPolicy").put("version",1);when(plan.staging()).thenReturn(stage);
        when(execution.runUuid()).thenReturn(run);when(execution.jobRequestUuid()).thenReturn(job);when(execution.publicationUuid()).thenReturn(publication);when(execution.attemptNumber()).thenReturn(1);
        when(execution.releaseHash()).thenReturn(hash);when(execution.planHash()).thenReturn(hash);
        when(fence.runUuid()).thenReturn(run);when(fence.runGeneration()).thenReturn(1L);when(fence.targetGeneration()).thenReturn(2L);when(fence.workerReference()).thenReturn("worker");
        when(fence.targetResourceUuid()).thenReturn(target);when(fence.targetIdentityVersion()).thenReturn(1);when(fence.canonicalTargetHash()).thenReturn(hash);
        var intent=mock(PilotPublishIntentPort.PilotPublishIntent.class);
        when(intent.projectUuid()).thenReturn(project);when(intent.publicationUuid()).thenReturn(publication);when(intent.jobRequestUuid()).thenReturn(job);when(intent.runUuid()).thenReturn(run);
        when(intent.attemptNumber()).thenReturn(1);when(intent.runGeneration()).thenReturn(1L);when(intent.workerReference()).thenReturn("worker");when(intent.targetResourceUuid()).thenReturn(target);
        when(intent.targetGeneration()).thenReturn(2L);when(intent.targetIdentityVersion()).thenReturn(1);when(intent.canonicalTargetHash()).thenReturn(hash);
        when(intent.releaseHash()).thenReturn(hash);when(intent.planHash()).thenReturn(hash);when(intent.runtimePlanHash()).thenReturn(hash);
        String key=StagedPublishFacade.publishKey(plan,execution,fence);
        when(intent.publishKeyHash()).thenReturn(key);when(intent.payloadHash()).thenReturn(seal.payloadHash());when(intent.rowCount()).thenReturn(seal.rows());when(intent.byteCount()).thenReturn(seal.logicalBytes());
        when(intents.find(run)).thenReturn(Optional.of(intent));
        String name=WorkObjectPrefixes.DEFAULTS.objectName("LOADING",project,run,1,"WORK_SOURCE_1");
        object=new OracleWorkTableManager.Created(UUID.randomUUID(),hash,new JdbcStagingTransfer.Table("WORK",name),123,hash);
        when(objects.list(project,run)).thenReturn(List.of(new WorkObjectStore.ObjectRow(object.uuid(),"WORK_SOURCE_1",hash,"WORK",name,123L,hash,WorkObjectLifecycle.State.SEALED,seal.rows(),seal.logicalBytes(),seal.payloadHash())));
        var pinned=mock(PinnedSchemaSnapshotPort.PinnedSnapshots.class);when(pinned.projectUuid()).thenReturn(project);when(pinned.publicationUuid()).thenReturn(publication);when(snapshots.load(plan)).thenReturn(pinned);
        when(journal.list(project,run)).thenReturn(rows("SUCCEEDED"));
        when(policies.get(project,schema)).thenReturn(new WorkAreaPolicyService.View(new WorkAreaPolicyService.Policy(true,false,10,2000,100000,24),1));
    }
    private List<KmStepJournal.Row> rows(String priorState) {
        var rows=new ArrayList<KmStepJournal.Row>();
        for(int i=0;i<program.steps().size();i++) { var s=program.steps().get(i);rows.add(new KmStepJournal.Row(1,i+1,s.id(),s.operation().name(),s.site().name(),s.slot(),i==program.steps().size()-1?"RUNNING":priorState,1201L,null,null,null)); }
        return rows;
    }
    @Test void missingJournalPreventsOpeningTarget() {
        fixture();when(journal.list(project,run)).thenReturn(List.of());
        assertThrows(IllegalStateException.class,()->facade().publish(plan,execution,fence,object,seal,()->{}));verifyNoInteractions(connections);
    }
    @Test void failedCkmCannotBeBypassed() {
        fixture();when(journal.list(project,run)).thenReturn(rows("FAILED"));
        assertThrows(IllegalStateException.class,()->facade().publish(plan,execution,fence,object,seal,()->{}));verifyNoInteractions(connections);
    }
    @Test void revokedWorkPolicyPreventsOpeningTarget() {
        fixture();when(policies.get(project,schema)).thenReturn(new WorkAreaPolicyService.View(new WorkAreaPolicyService.Policy(false,false,10,2000,100000,24),2));
        assertThrows(IllegalStateException.class,()->facade().publish(plan,execution,fence,object,seal,()->{}));verifyNoInteractions(connections);
    }
    @Test void onlyExactEvidenceReachesTargetSession() {
        fixture();var sentinel=new IllegalStateException("test session boundary");when(connections.openTargetData(eq(plan.target()),any())).thenThrow(sentinel);
        assertSame(sentinel,assertThrows(IllegalStateException.class,()->facade().publish(plan,execution,fence,object,seal,()->{})));
        verify(connections).openTargetData(eq(plan.target()),any());
    }
}

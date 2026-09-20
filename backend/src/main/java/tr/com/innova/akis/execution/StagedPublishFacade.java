package tr.com.innova.akis.execution;

import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.*;
import static tr.com.innova.akis.execution.OracleTargetLedgerPort.*;
import static tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import static tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;

/** Owns target session and verifies committed control intent before any target business DML. */
@Component
final class StagedPublishFacade {
    static final class PublishPermit implements RuntimeOracleConnectionProvider.TargetDataPermit {
        private PublishPermit() { }
    }
    private static final PublishPermit PERMIT=new PublishPermit();
    private final RuntimeOracleConnectionProvider connections;
    private final PilotPublishIntentPort intents;
    private final OracleTargetLedgerPort ledger;
    private final WorkObjectStore objects;
    private final JdbcPinnedSchemaSnapshotStore snapshots;
    private final ObjectMapper mapper;
    private final WorkAreaPolicyService policies;
    private final KmStepJournal journal;
    StagedPublishFacade(RuntimeOracleConnectionProvider connections,PilotPublishIntentPort intents,OracleTargetLedgerPort ledger,
            WorkObjectStore objects,JdbcPinnedSchemaSnapshotStore snapshots,ObjectMapper mapper,WorkAreaPolicyService policies,KmStepJournal journal) {
        this.connections=connections;this.intents=intents;this.ledger=ledger;this.objects=objects;this.snapshots=snapshots;this.mapper=mapper;
        this.policies=policies;this.journal=journal;
    }
    static String publishKey(StagedRuntimePlan plan,PinnedExecutionContext execution,TargetFenceToken fence) {
        return KmCanonical.hash("AKIS_KM_PUBLISH/1|"+execution.jobRequestUuid()+"|"+plan.runtimePlanHash()+"|"+fence.canonicalTargetHash()+"|KM_"+writeMode(plan));
    }
    static String publishKey(MappingExecutionContract plan,UUID job,String targetHash) {
        return KmCanonical.hash("AKIS_KM_PUBLISH/1|"+job+"|"+plan.runtimePlanHash()+"|"+targetHash+"|KM_ATOMIC_REPLACE");
    }
    OracleKmRuntime.PublishResult publish(StagedRuntimePlan plan,PinnedExecutionContext execution,TargetFenceToken fence,
            OracleWorkTableManager.Created object,JdbcStagingTransfer.Result seal,Runnable leaseCheckpoint) {
        var intent=intents.find(execution.runUuid()).orElseThrow(()->new IllegalStateException("KM yayın niyeti bulunamadı."));
        String key=publishKey(plan,execution,fence);
        require(Objects.equals(intent.projectUuid(),plan.projectUuid()) && Objects.equals(intent.publicationUuid(),execution.publicationUuid())
                && Objects.equals(intent.jobRequestUuid(),execution.jobRequestUuid()) && Objects.equals(intent.runUuid(),execution.runUuid())
                && intent.attemptNumber()==execution.attemptNumber() && intent.runGeneration()==fence.runGeneration()
                && Objects.equals(intent.workerReference(),fence.workerReference()) && Objects.equals(intent.targetResourceUuid(),fence.targetResourceUuid())
                && intent.targetGeneration()==fence.targetGeneration() && intent.targetIdentityVersion()==fence.targetIdentityVersion()
                && intent.canonicalTargetHash().equals(fence.canonicalTargetHash()) && intent.releaseHash().equals(plan.releaseHash())
                && intent.releaseHash().equals(execution.releaseHash()) && intent.planHash().equals(plan.scenarioPlanHash())
                && intent.planHash().equals(execution.planHash()) && intent.runtimePlanHash().equals(plan.runtimePlanHash())
                && intent.publishKeyHash().equals(key) && intent.payloadHash().equals(seal.payloadHash())
                && intent.rowCount()==seal.rows() && intent.byteCount()==seal.logicalBytes()
                && execution.runUuid().equals(fence.runUuid()));
        var prefix=plan.staging().path("prefixes");
        String name=new WorkObjectPrefixes(prefix.path("loading").asText(),prefix.path("integration").asText(),prefix.path("error").asText())
                .objectName("LOADING",plan.projectUuid(),execution.runUuid(),fence.runGeneration(),"WORK_SOURCE_1");
        require(object.table().name().equals(name) && object.table().owner().equals(plan.staging().path("owner").asText()));
        var owned=objects.list(plan.projectUuid(),execution.runUuid()).stream().filter(row->row.uuid().equals(object.uuid())).findFirst().orElseThrow();
        require(owned.state()==WorkObjectLifecycle.State.SEALED && Objects.equals(owned.objectId(),object.objectId())
                && owned.name().equals(name) && owned.owner().equals(object.table().owner())
                && owned.databaseIdentity().equals(object.databaseIdentity()) && owned.structureHash().equals(object.structureHash())
                && Objects.equals(owned.rows(),seal.rows()) && Objects.equals(owned.bytes(),seal.logicalBytes()) && owned.payloadHash().equals(seal.payloadHash()));
        var pinned=snapshots.load(plan);
        require(pinned.projectUuid().equals(plan.projectUuid()) && pinned.publicationUuid().equals(execution.publicationUuid()));
        requireCompletedSteps(plan,execution,fence);
        verifyPolicy(plan);
        leaseCheckpoint.run();
        var session=connections.openTargetData(plan.target(),PERMIT);
        JdbcStagedAtomicRefreshWriter.Result result;
        try {
            var connection=session.connection();
            String mode=writeMode(plan);
            var evidence=new PublishEvidence("KM_"+mode,key,seal.payloadHash(),seal.rows(),seal.rows(),0,null,null);
            var writerMode=JdbcStagedAtomicRefreshWriter.WriteMode.valueOf(mode);
            String keyOption=plan.definition().stringOption("integration","KEY_COLUMNS");
            List<String> keys=keyOption.isBlank()?List.of():Arrays.stream(keyOption.split(",")).map(String::strip).peek(StagedMappingDefinition::identifier).toList();
            result=new JdbcStagedAtomicRefreshWriter(ledger).publish(connection,TargetLedgerContext.from(fence,execution),evidence,object.table(),
                    new JdbcStagingTransfer.Table(plan.target().owner(),plan.target().objectName()),plan.columnMappings().stream()
                        .map(c->new JdbcStagedAtomicRefreshWriter.Column(c.targetColumn(),c.targetColumn())).toList(),30,()->{
                            verifyPolicy(plan);
                            new JdbcOracleSchemaPreflight(mapper).verifyLockedStagedTarget(plan,connection,pinned);
                            verifyWork(connection,object);
                        },leaseCheckpoint,new JdbcTransactionBoundary() {
                            public void commit() { session.commitConfirmed(); }
                            public void rollback() { session.rollbackConfirmed(); }
                        },plan.definition().stringOption("integration","ORACLE_HINT"),writerMode,keys);
        } finally { try { session.close(); } catch(RuntimeException ignored) { } }
        return new OracleKmRuntime.PublishResult(OracleKmRuntime.PublishOutcome.valueOf(result.outcome().name()),result.inserted()==null?0:result.inserted());
    }
    private static String writeMode(StagedRuntimePlan plan) {
        String configured=plan.definition().stringOption("integration","WRITE_MODE");
        return configured.isBlank()?"ATOMIC_DELETE_INSERT":configured;
    }
    private void verifyPolicy(StagedRuntimePlan plan) {
        var staging=plan.staging();
        var policy=policies.get(plan.projectUuid(),UUID.fromString(staging.path("physicalSchemaUuid").asText()));
        require(policy.version()==staging.path("workAreaPolicy").path("version").asLong());
        WorkAreaPolicyService.requireAllowed(policy,plan.definition().options(),staging.path("owner").asText().equals(plan.target().owner()));
    }
    private void requireCompletedSteps(StagedRuntimePlan plan,PinnedExecutionContext execution,TargetFenceToken fence) {
        var recorded=journal.list(plan.projectUuid(),execution.runUuid()).stream().filter(r->r.generation()==fence.runGeneration()).toList();
        require(recorded.size()==plan.program().steps().size());
        for(int i=0;i<recorded.size();i++) {
            var actual=recorded.get(i);var expected=plan.program().steps().get(i);
            require(actual.ordinal()==i+1 && actual.stepCode().equals(expected.id()) && actual.operation().equals(expected.operation().name())
                    && actual.site().equals(expected.site().name()) && actual.slot().equals(expected.slot()));
            require(actual.state().equals(i==recorded.size()-1?"RUNNING":"SUCCEEDED"));
        }
    }
    private static void verifyWork(Connection connection,OracleWorkTableManager.Created object) {
        try {
            require(object.databaseIdentity().equals(OracleWorkTableManager.databaseIdentity(connection))
                    && object.structureHash().equals(OracleWorkStructure.read(connection,object.table(),30)));
            try(var statement=connection.prepareStatement("SELECT OBJECT_ID FROM ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE='TABLE' AND SUBOBJECT_NAME IS NULL")) {
                statement.setString(1,object.table().owner());statement.setString(2,object.table().name());statement.setQueryTimeout(30);
                try(var result=statement.executeQuery()) { require(result.next() && result.getLong(1)==object.objectId() && !result.next()); }
            }
        } catch(SQLException failure) { throw new IllegalStateException("KM çalışma nesnesi doğrulanamadı."); }
    }
    private static void require(boolean valid) { if(!valid) throw new IllegalStateException("KM yayın kanıtı uyuşmuyor."); }
}

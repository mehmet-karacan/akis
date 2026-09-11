package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Failure;
import tr.com.innova.akis.execution.OraclePublishReconciliationReadPort.Result;
import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.BarrierEvidence;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.PinnedReconciliation;
import tr.com.innova.akis.execution.PublishReconciliationService.ServiceOutcome;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;
import tr.com.innova.akis.execution.RunReconciliationPort.CompletionResult;
import tr.com.innova.akis.execution.RunReconciliationPort.HeartbeatResult;
import tr.com.innova.akis.execution.RunReconciliationPort.Conflict;
import tr.com.innova.akis.execution.RunReconciliationPort.NotPublished;
import tr.com.innova.akis.execution.RunReconciliationPort.Published;
import tr.com.innova.akis.execution.RunReconciliationPort.ReconciliationCompletion;
import tr.com.innova.akis.execution.RunReconciliationPort.ReconciliationLeaseToken;

class PublishReconciliationServiceTest {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_HASH = "c".repeat(64);
    private static final String TARGET_HASH = "d".repeat(64);
    private static final String PUBLISH_KEY_HASH = "e".repeat(64);
    private static final String PAYLOAD_HASH = "f".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishedFlowUsesExactOrderAndOriginalIntentEvidence() {
        Fixture fixture = fixture(Result.published());

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.PUBLISHED, result.outcome());
        assertNull(result.failure());
        assertEquals(List.of("CLAIM", "PINNED", "ORACLE", "HEARTBEAT", "COMPLETE"),
                fixture.events);
        Published completion = assertInstanceOf(Published.class, fixture.runs.completion);
        assertEquals(RUNTIME_HASH, completion.evidence().runtimePlanHash());
        assertEquals(PUBLISH_KEY_HASH, completion.evidence().publishKeyHash());
        assertEquals(PAYLOAD_HASH, completion.evidence().payloadHash());
        assertEquals(33, completion.evidence().rowCount());
        assertEquals(2_048, completion.evidence().byteCount());
        assertEquals(fixture.runs.refreshed, fixture.runs.completionToken);
    }

    @Test
    void outcomeUnknownNeverHeartbeatsOrCompletesPostgres() {
        Fixture fixture = fixture(Result.unknown(Failure.ORACLE_READ_UNCONFIRMED));

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.OUTCOME_UNKNOWN, result.outcome());
        assertEquals(List.of("CLAIM", "PINNED", "ORACLE"), fixture.events);
        assertNull(fixture.runs.completion);
    }

    @Test
    void staleHeartbeatNeverCompletesPostgres() {
        Fixture fixture = fixture(Result.notPublished());
        fixture.runs.heartbeat = HeartbeatResult.accepted(new ReconciliationLeaseToken(
                fixture.runUuid, fixture.worker.reference(),
                fixture.runs.claimed.runGeneration() + 1,
                fixture.runs.claimed.targetResourceUuid(),
                fixture.runs.claimed.targetGeneration(),
                fixture.runs.claimed.leaseDeadline().plusSeconds(60)));

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.FAILED_CLOSED, result.outcome());
        assertEquals(PublishReconciliationService.Failure.STALE_RECONCILIATION_LEASE,
                result.failure());
        assertEquals(List.of("CLAIM", "PINNED", "ORACLE", "HEARTBEAT"),
                fixture.events);
        assertNull(fixture.runs.completion);
    }

    @Test
    void notPublishedUsesTypedPostgresCompletion() {
        Fixture fixture = fixture(Result.notPublished());

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.NOT_PUBLISHED, result.outcome());
        assertInstanceOf(NotPublished.class, fixture.runs.completion);
        assertEquals(List.of("CLAIM", "PINNED", "ORACLE", "HEARTBEAT", "COMPLETE"),
                fixture.events);
    }

    @Test
    void conflictUsesTypedPostgresCompletion() {
        Fixture fixture = fixture(Result.conflict(Failure.LEDGER_EVIDENCE_CONFLICT));

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.CONFLICT, result.outcome());
        assertInstanceOf(Conflict.class, fixture.runs.completion);
        assertEquals(List.of("CLAIM", "PINNED", "ORACLE", "HEARTBEAT", "COMPLETE"),
                fixture.events);
    }

    @Test
    void retriesLostCompletionResponseOnceWithTheExactSameIdempotencyTuple() {
        Fixture fixture = fixture(Result.published());
        fixture.runs.failFirstCompleteAfterDurable = true;

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.PUBLISHED, result.outcome());
        assertEquals(List.of(
                "CLAIM", "PINNED", "ORACLE", "HEARTBEAT",
                "COMPLETE", "COMPLETE"), fixture.events);
        assertEquals(2, fixture.runs.completionTokens.size());
        assertSame(fixture.runs.completionTokens.get(0),
                fixture.runs.completionTokens.get(1));
        assertSame(fixture.runs.completions.get(0),
                fixture.runs.completions.get(1));
        Published first = assertInstanceOf(
                Published.class, fixture.runs.completions.get(0));
        Published retried = assertInstanceOf(
                Published.class, fixture.runs.completions.get(1));
        assertSame(first.evidence(), retried.evidence());
        assertEquals(PUBLISH_KEY_HASH, retried.evidence().publishKeyHash());
        assertEquals(PAYLOAD_HASH, retried.evidence().payloadHash());
    }

    @Test
    void rejectedCompletionRetryFailsClosedWithoutAThirdAttempt() {
        Fixture fixture = fixture(Result.published());
        fixture.runs.failFirstCompleteAfterDurable = true;
        fixture.runs.completionResult = CompletionResult.rejected();

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.FAILED_CLOSED, result.outcome());
        assertEquals(PublishReconciliationService.Failure.COMPLETION_REJECTED,
                result.failure());
        assertEquals(2, fixture.runs.completions.size());
    }

    @Test
    void secondCompletionExceptionFailsClosedWithoutAThirdAttempt() {
        Fixture fixture = fixture(Result.published());
        fixture.runs.failFirstCompleteAfterDurable = true;
        fixture.runs.failSecondComplete = true;

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.FAILED_CLOSED, result.outcome());
        assertEquals(PublishReconciliationService.Failure.CONTROL_PLANE_UNAVAILABLE,
                result.failure());
        assertEquals(2, fixture.runs.completions.size());
    }

    @Test
    void barrierMismatchStopsBeforeOracleRead() {
        Fixture fixture = fixture(Result.published());
        BarrierEvidence barrier = fixture.pinned.barrier();
        fixture.pinned = new PinnedReconciliation(
                fixture.pinned.plan(), fixture.pinned.execution(),
                fixture.pinned.originalPublish(),
                new BarrierEvidence(
                        barrier.runUuid(), barrier.reconciliationRunGeneration(),
                        "another-worker", barrier.targetResourceUuid(),
                        barrier.originalPublishTargetGeneration(),
                        barrier.barrierTargetGeneration(), barrier.canonicalTargetHash(),
                        barrier.targetIdentityVersion()));

        var result = fixture.service.reconcile(
                fixture.runUuid, fixture.worker, LEASE);

        assertEquals(ServiceOutcome.FAILED_CLOSED, result.outcome());
        assertEquals(PublishReconciliationService.Failure.BARRIER_MISMATCH,
                result.failure());
        assertEquals(List.of("CLAIM", "PINNED"), fixture.events);
        assertNull(fixture.runs.completion);
    }

    private Fixture fixture(Result oracleResult) {
        UUID runUuid = UUID.randomUUID();
        WorkerIdentity worker = new WorkerIdentity("reconcile-worker", UUID.randomUUID());
        UUID targetUuid = UUID.randomUUID();
        ReconciliationLeaseToken claimed = new ReconciliationLeaseToken(
                runUuid, worker.reference(), 11, targetUuid, 6,
                OffsetDateTime.now().plusMinutes(2));
        ReconciliationLeaseToken refreshed = new ReconciliationLeaseToken(
                runUuid, worker.reference(), 11, targetUuid, 6,
                claimed.leaseDeadline().plusSeconds(60));
        PinnedReconciliation pinned = aggregate(runUuid, worker.reference(), targetUuid);
        List<String> events = new ArrayList<>();
        FakeRuns runs = new FakeRuns(events, claimed, refreshed);
        Fixture fixture = new Fixture(runUuid, worker, events, runs, pinned, null);
        PublishReconciliationService service = new PublishReconciliationService(
                runs,
                requested -> {
                    events.add("PINNED");
                    return Optional.ofNullable(fixture.pinned);
                },
                requested -> {
                    events.add("ORACLE");
                    return oracleResult;
                });
        fixture.service = service;
        return fixture;
    }

    private PinnedReconciliation aggregate(
            UUID runUuid, String reconciliationWorker, UUID targetUuid) {
        UUID jobRequestUuid = UUID.randomUUID();
        UUID publicationUuid = UUID.randomUUID();
        PilotRuntimePlan plan = new PilotRuntimePlan(
                1, RUNTIME_HASH, RELEASE_HASH, PLAN_HASH,
                UUID.randomUUID(), UUID.randomUUID(), 100,
                binding(DatasetRole.SOURCE), binding(DatasetRole.TARGET),
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
        PinnedExecutionContext execution = new PinnedExecutionContext(
                jobRequestUuid, runUuid, publicationUuid, 1,
                RELEASE_HASH, PLAN_HASH,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
        PilotPublishIntent original = new PilotPublishIntent(
                UUID.randomUUID(), publicationUuid, jobRequestUuid, runUuid, 1,
                10, "publish-worker", targetUuid, 5, TARGET_HASH,
                OracleTargetIdentityV1.TARGET_IDENTITY_VERSION,
                RELEASE_HASH, PLAN_HASH, RUNTIME_HASH,
                PUBLISH_KEY_HASH, PAYLOAD_HASH, 33, 2_048);
        BarrierEvidence barrier = new BarrierEvidence(
                runUuid, 11, reconciliationWorker, targetUuid,
                5, 6, TARGET_HASH, OracleTargetIdentityV1.TARGET_IDENTITY_VERSION);
        return new PinnedReconciliation(plan, execution, original, barrier);
    }

    private DatasetBinding binding(DatasetRole role) {
        return new DatasetBinding(
                role.name(), role, DatabaseType.ORACLE, DataObjectType.TABLE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "1".repeat(64), "OWNER.TABLE", "OWNER", "TABLE");
    }

    private static final class Fixture {

        private final UUID runUuid;
        private final WorkerIdentity worker;
        private final List<String> events;
        private final FakeRuns runs;
        private PinnedReconciliation pinned;
        private PublishReconciliationService service;

        private Fixture(
                UUID runUuid,
                WorkerIdentity worker,
                List<String> events,
                FakeRuns runs,
                PinnedReconciliation pinned,
                PublishReconciliationService service) {
            this.runUuid = runUuid;
            this.worker = worker;
            this.events = events;
            this.runs = runs;
            this.pinned = pinned;
            this.service = service;
        }
    }

    private static final class FakeRuns implements RunReconciliationPort {

        private final List<String> events;
        private final ReconciliationLeaseToken claimed;
        private final ReconciliationLeaseToken refreshed;
        private HeartbeatResult heartbeat;
        private ReconciliationLeaseToken completionToken;
        private ReconciliationCompletion completion;
        private final List<ReconciliationLeaseToken> completionTokens = new ArrayList<>();
        private final List<ReconciliationCompletion> completions = new ArrayList<>();
        private boolean failFirstCompleteAfterDurable;
        private boolean failSecondComplete;
        private CompletionResult completionResult = CompletionResult.accepted();

        private FakeRuns(
                List<String> events,
                ReconciliationLeaseToken claimed,
                ReconciliationLeaseToken refreshed) {
            this.events = events;
            this.claimed = claimed;
            this.refreshed = refreshed;
            this.heartbeat = HeartbeatResult.accepted(refreshed);
        }

        @Override
        public Optional<ReconciliationLeaseToken> claim(
                UUID runUuid, WorkerIdentity worker, Duration lease) {
            events.add("CLAIM");
            return Optional.of(claimed);
        }

        @Override
        public HeartbeatResult heartbeat(ReconciliationLeaseToken token, Duration lease) {
            events.add("HEARTBEAT");
            return heartbeat;
        }

        @Override
        public CompletionResult complete(
                ReconciliationLeaseToken token, ReconciliationCompletion completion) {
            events.add("COMPLETE");
            this.completionToken = token;
            this.completion = completion;
            completionTokens.add(token);
            completions.add(completion);
            if (failFirstCompleteAfterDurable && completions.size() == 1) {
                throw new IllegalStateException("response lost after durable completion");
            }
            if (failSecondComplete && completions.size() == 2) {
                throw new IllegalStateException("retry response unavailable");
            }
            return completionResult;
        }
    }
}

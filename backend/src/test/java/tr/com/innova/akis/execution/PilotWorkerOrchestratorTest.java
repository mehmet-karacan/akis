package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.OracleAtomicPublishPort.PublishReceipt;
import tr.com.innova.akis.execution.OraclePilotSourceReadPort.ReadSucceeded;
import tr.com.innova.akis.execution.OracleTargetFencePort.FenceReceipt;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.IdentityReadSucceeded;
import tr.com.innova.akis.execution.OracleTargetIdentityReadPort.TargetIdentityEvidence;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.MutationResult;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.PublishIntentEvidence;
import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatResult;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

class PilotWorkerOrchestratorTest {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_HASH = "c".repeat(64);
    private static final String TARGET_HASH = "d".repeat(64);
    private static final String PAYLOAD_HASH = "e".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void executesHappyPathInExactFailClosedOrder() {
        Fixture fixture = new Fixture();

        PilotWorkerOrchestrator.RunOnceResult result = fixture.run();

        assertInstanceOf(PilotWorkerOrchestrator.Succeeded.class, result);
        assertEquals(List.of(
                "CLAIM", "GATE_OPEN", "EXECUTION", "PLAN", "SNAPSHOTS",
                "CHECKPOINT", "TARGET_IDENTITY", "CHECKPOINT",
                "GATE_EXECUTE", "TARGET_ACQUIRE", "CHECKPOINT", "SOURCE_READ",
                "CHECKPOINT", "GATE_EXECUTE", "PREFLIGHT_COMPLETE",
                "CHECKPOINT", "GATE_EXECUTE", "PUBLISH_BEGIN",
                "CHECKPOINT", "TARGET_FENCE", "CHECKPOINT",
                "CHECKPOINT", "PUBLISH", "CHECKPOINT",
                "GATE_TERMINAL", "SUCCESS", "GATE_CLOSE"), fixture.events);
    }

    @Test
    void returnsIdleWithoutOpeningAGate() {
        Fixture fixture = new Fixture();
        fixture.idle = true;

        PilotWorkerOrchestrator.RunOnceResult result = fixture.run();

        assertInstanceOf(PilotWorkerOrchestrator.Idle.class, result);
        assertEquals(List.of("CLAIM"), fixture.events);
    }

    @Test
    void retriesClaimExactlyOnceAfterLostAcknowledgement() {
        Fixture fixture = new Fixture();
        fixture.claimFailures = 1;

        assertInstanceOf(PilotWorkerOrchestrator.Succeeded.class, fixture.run());
        assertEquals(2, occurrences(fixture.events, "CLAIM"));
    }

    @Test
    void stopsWhenClaimCannotBeConfirmedAfterExactRetry() {
        Fixture fixture = new Fixture();
        fixture.claimFailures = 2;

        PilotWorkerOrchestrator.StoppedFailClosed result = assertInstanceOf(
                PilotWorkerOrchestrator.StoppedFailClosed.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.CONTROL_PLANE_UNCONFIRMED,
                result.failure());
        assertEquals(List.of("CLAIM", "CLAIM"), fixture.events);
    }

    @Test
    void retriesTargetAcquireExactlyOnceWithTheSameAuthority() {
        Fixture fixture = new Fixture();
        fixture.targetAcquireFailures = 1;

        assertInstanceOf(PilotWorkerOrchestrator.Succeeded.class, fixture.run());
        assertEquals(2, occurrences(fixture.events, "TARGET_ACQUIRE"));
        assertEquals(List.of(fixture.runToken, fixture.runToken), fixture.acquireTokens);
        assertEquals(List.of(TARGET_HASH, TARGET_HASH), fixture.acquireHashes);
    }

    @Test
    void stopsBeforeSourceOrPublicationWhenTargetClaimCannotBeConfirmed() {
        Fixture fixture = new Fixture();
        fixture.targetAcquireFailures = 2;

        assertInstanceOf(PilotWorkerOrchestrator.StoppedFailClosed.class, fixture.run());
        assertEquals(2, occurrences(fixture.events, "TARGET_ACQUIRE"));
        assertEquals(0, occurrences(fixture.events, "SOURCE_READ"));
        assertEquals(0, occurrences(fixture.events, "TARGET_FENCE"));
        assertEquals(0, occurrences(fixture.events, "PUBLISH"));
    }

    @Test
    void retriesBeginPublishExactlyOnceAndDoesNotTouchOracleBeforeAcceptance() {
        Fixture fixture = new Fixture();
        fixture.beginPublishFailures = 1;

        assertInstanceOf(PilotWorkerOrchestrator.Succeeded.class, fixture.run());
        assertEquals(2, occurrences(fixture.events, "PUBLISH_BEGIN"));
        assertEquals(1, occurrences(fixture.events, "TARGET_FENCE"));
        assertEquals(1, occurrences(fixture.events, "PUBLISH"));
        assertEquals(List.of(fixture.beginEvidence.getFirst(), fixture.beginEvidence.getFirst()),
                fixture.beginEvidence);
        assertEquals(fixture.events.lastIndexOf("PUBLISH_BEGIN") + 2,
                fixture.events.indexOf("TARGET_FENCE"));
    }

    @Test
    void rejectedPublishIntentNeverTouchesOracleWritePaths() {
        Fixture fixture = new Fixture();
        fixture.beginPublishRejected = true;

        PilotWorkerOrchestrator.StoppedFailClosed result = assertInstanceOf(
                PilotWorkerOrchestrator.StoppedFailClosed.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.PUBLISH_INTENT_REJECTED,
                result.failure());
        assertEquals(0, occurrences(fixture.events, "TARGET_FENCE"));
        assertEquals(0, occurrences(fixture.events, "PUBLISH"));
        assertEquals(0, fixture.terminalTransitionCount());
    }

    @Test
    void retriesSuccessTerminalExactlyOnceWithTheSameEvidence() {
        Fixture fixture = new Fixture();
        fixture.successFailures = 1;

        assertInstanceOf(PilotWorkerOrchestrator.Succeeded.class, fixture.run());
        assertEquals(2, occurrences(fixture.events, "SUCCESS"));
        assertEquals(List.of(fixture.successEvidence.getFirst(), fixture.successEvidence.getFirst()),
                fixture.successEvidence);
    }

    @Test
    void sourceSafeFailureTerminatesSafelyAndNeverStartsPublication() {
        Fixture fixture = new Fixture();
        fixture.sourceResult = new OraclePilotSourceReadPort.SafeFailure(
                OraclePilotSourceReadPort.Failure.SOURCE_READ_FAILED);

        PilotWorkerOrchestrator.FailedSafely result = assertInstanceOf(
                PilotWorkerOrchestrator.FailedSafely.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.SOURCE_FAILED_SAFE,
                result.failure());
        assertEquals(1, occurrences(fixture.events, "FAIL_ACTIVE"));
        assertEquals(0, occurrences(fixture.events, "PREFLIGHT_COMPLETE"));
        assertEquals(0, occurrences(fixture.events, "TARGET_FENCE"));
        assertEquals(0, occurrences(fixture.events, "PUBLISH"));
    }

    @Test
    void fenceOutcomeUnknownIsRecordedAndPublicationIsNotAttempted() {
        Fixture fixture = new Fixture();
        fixture.fenceResult = new OracleTargetFencePort.OutcomeUnknown(
                OracleTargetFencePort.FailureCode.COMMIT_OUTCOME_UNKNOWN);

        PilotWorkerOrchestrator.OutcomeUnknownRecorded result = assertInstanceOf(
                PilotWorkerOrchestrator.OutcomeUnknownRecorded.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.FENCE_OUTCOME_UNKNOWN,
                result.failure());
        assertEquals(1, occurrences(fixture.events, "MARK_UNKNOWN"));
        assertEquals(0, occurrences(fixture.events, "PUBLISH"));
    }

    @Test
    void committedFenceWithMismatchedReceiptIsRecordedAsUnknown() {
        Fixture fixture = new Fixture();
        fixture.fenceResult = new OracleTargetFencePort.CommitConfirmed(
                new FenceReceipt(fixture.targetResourceUuid, 6, TARGET_HASH));

        PilotWorkerOrchestrator.OutcomeUnknownRecorded result = assertInstanceOf(
                PilotWorkerOrchestrator.OutcomeUnknownRecorded.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.FENCE_RECEIPT_INVALID,
                result.failure());
        assertEquals(1, occurrences(fixture.events, "MARK_UNKNOWN"));
        assertEquals(0, occurrences(fixture.events, "FAIL_ACTIVE"));
        assertEquals(0, occurrences(fixture.events, "PUBLISH"));
    }

    @Test
    void publishOutcomeUnknownIsRecorded() {
        Fixture fixture = new Fixture();
        fixture.publishResult = new OracleAtomicPublishPort.OutcomeUnknown(
                OracleAtomicPublishPort.FailureCode.COMMIT_OUTCOME_UNKNOWN);

        PilotWorkerOrchestrator.OutcomeUnknownRecorded result = assertInstanceOf(
                PilotWorkerOrchestrator.OutcomeUnknownRecorded.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.PUBLISH_OUTCOME_UNKNOWN,
                result.failure());
        assertEquals(1, occurrences(fixture.events, "MARK_UNKNOWN"));
        assertEquals(0, occurrences(fixture.events, "SUCCESS"));
    }

    @Test
    void terminalAcknowledgementLossNeverAttemptsAnAlternativeTerminalState() {
        Fixture fixture = new Fixture();
        fixture.publishResult = new OracleAtomicPublishPort.OutcomeUnknown(
                OracleAtomicPublishPort.FailureCode.COMMIT_OUTCOME_UNKNOWN);
        fixture.markUnknownFailures = 2;

        PilotWorkerOrchestrator.StoppedFailClosed result = assertInstanceOf(
                PilotWorkerOrchestrator.StoppedFailClosed.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.CONTROL_PLANE_UNCONFIRMED,
                result.failure());
        assertEquals(2, occurrences(fixture.events, "MARK_UNKNOWN"));
        assertEquals(0, occurrences(fixture.events, "FAIL_ACTIVE"));
        assertEquals(0, occurrences(fixture.events, "SUCCESS"));
    }

    @Test
    void receiptMismatchIsTreatedAsUnknownRatherThanSuccess() {
        Fixture fixture = new Fixture();
        fixture.receiptPayloadHash = "f".repeat(64);

        PilotWorkerOrchestrator.OutcomeUnknownRecorded result = assertInstanceOf(
                PilotWorkerOrchestrator.OutcomeUnknownRecorded.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.PUBLISH_RECEIPT_INVALID,
                result.failure());
        assertEquals(1, occurrences(fixture.events, "MARK_UNKNOWN"));
        assertEquals(0, occurrences(fixture.events, "SUCCESS"));
    }

    @Test
    void leaseGateLossStopsImmediatelyBeforeAnyOracleCall() {
        Fixture fixture = new Fixture();
        fixture.gateFailureAtCheckpoint = 1;

        PilotWorkerOrchestrator.StoppedFailClosed result = assertInstanceOf(
                PilotWorkerOrchestrator.StoppedFailClosed.class, fixture.run());

        assertEquals(PilotWorkerOrchestrator.FailureCode.LEASE_AUTHORITY_LOST,
                result.failure());
        assertEquals(List.of(
                "CLAIM", "GATE_OPEN", "EXECUTION", "PLAN", "SNAPSHOTS",
                "CHECKPOINT", "GATE_CLOSE"), fixture.events);
    }

    @Test
    void errorCrashesAfterObservedPrefixWithoutAttemptingCompensatingTransitions() {
        Fixture fixture = new Fixture();
        fixture.sourceError = new AssertionError("simulated process-level crash");

        assertThrows(AssertionError.class, fixture::run);

        assertEquals(List.of(
                "CLAIM", "GATE_OPEN", "EXECUTION", "PLAN", "SNAPSHOTS",
                "CHECKPOINT", "TARGET_IDENTITY", "CHECKPOINT",
                "GATE_EXECUTE", "TARGET_ACQUIRE", "CHECKPOINT", "SOURCE_READ",
                "GATE_CLOSE"), fixture.events);
        assertEquals(0, fixture.terminalTransitionCount());
    }

    @Test
    void errorAfterIntentEscapesWithoutInventingATerminalOutcome() {
        Fixture fixture = new Fixture();
        fixture.publishError = new AssertionError("simulated publish process crash");

        assertThrows(AssertionError.class, fixture::run);

        assertEquals(1, occurrences(fixture.events, "PUBLISH_BEGIN"));
        assertEquals(1, occurrences(fixture.events, "TARGET_FENCE"));
        assertEquals(1, occurrences(fixture.events, "PUBLISH"));
        assertEquals("GATE_CLOSE", fixture.events.getLast());
        assertEquals(0, fixture.terminalTransitionCount());
    }

    private static long occurrences(List<String> events, String event) {
        return events.stream().filter(event::equals).count();
    }

    private static final class Fixture {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final List<String> events = new ArrayList<>();
        private final List<RunLeaseToken> acquireTokens = new ArrayList<>();
        private final List<String> acquireHashes = new ArrayList<>();
        private final List<PublishIntentEvidence> beginEvidence = new ArrayList<>();
        private final List<PublishIntentEvidence> successEvidence = new ArrayList<>();
        private final UUID runUuid = UUID.fromString("10000000-0000-0000-0000-000000000001");
        private final UUID jobUuid = UUID.fromString("10000000-0000-0000-0000-000000000002");
        private final UUID publicationUuid = UUID.fromString("10000000-0000-0000-0000-000000000003");
        private final UUID projectUuid = UUID.fromString("10000000-0000-0000-0000-000000000004");
        private final UUID sourceSnapshotUuid = UUID.fromString("10000000-0000-0000-0000-000000000005");
        private final UUID targetSnapshotUuid = UUID.fromString("10000000-0000-0000-0000-000000000006");
        private final UUID targetResourceUuid = UUID.fromString("10000000-0000-0000-0000-000000000007");
        private final RunLeaseToken runToken = new RunLeaseToken(
                runUuid, "worker-1", 3, OffsetDateTime.parse("2026-09-11T10:00:30Z"));
        private final TargetFenceToken targetToken = new TargetFenceToken(
                runUuid, "worker-1", 3, targetResourceUuid, 5, TARGET_HASH,
                OracleTargetIdentityV1.TARGET_IDENTITY_VERSION);
        private final ClaimedRun claimed = new ClaimedRun(runToken, RELEASE_HASH, PLAN_HASH);
        private final PinnedExecutionContext execution = new PinnedExecutionContext(
                jobUuid, runUuid, publicationUuid, 1, RELEASE_HASH, PLAN_HASH,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
        private final PilotRuntimePlan plan = new PilotRuntimePlan(
                1, RUNTIME_HASH, RELEASE_HASH, PLAN_HASH,
                UUID.fromString("10000000-0000-0000-0000-000000000008"),
                UUID.fromString("10000000-0000-0000-0000-000000000009"),
                1_000,
                binding(DatasetRole.SOURCE, sourceSnapshotUuid, "1".repeat(64),
                        "TTBP", "HAKEDIS_TIPI"),
                binding(DatasetRole.TARGET, targetSnapshotUuid, "2".repeat(64),
                        "INNOVA_ODI", "STG_HAKEDIS_TIPI"),
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT, objectMapper.createObjectNode());
        private final PinnedSnapshots snapshots = new PinnedSnapshots(
                projectUuid, publicationUuid,
                new PinnedSnapshot(sourceSnapshotUuid, "1".repeat(64), null),
                new PinnedSnapshot(targetSnapshotUuid, "2".repeat(64), null));
        private final TargetIdentityEvidence identity = new TargetIdentityEvidence(
                OracleTargetIdentityV1.TARGET_IDENTITY_VERSION,
                "TARGETDB", "PDB1", "INNOVA_ODI", "TABLE",
                "STG_HAKEDIS_TIPI", TARGET_HASH);
        private final OraclePilotBatch batch = new OraclePilotBatch(
                RUNTIME_HASH, List.of(), List.of(), PAYLOAD_HASH, 0);
        private final FakeTransitions transitions = new FakeTransitions();

        private boolean idle;
        private int claimFailures;
        private int targetAcquireFailures;
        private int beginPublishFailures;
        private boolean beginPublishRejected;
        private int successFailures;
        private int markUnknownFailures;
        private int gateFailureAtCheckpoint;
        private Error sourceError;
        private Error publishError;
        private OraclePilotSourceReadPort.SourceReadResult sourceResult =
                new ReadSucceeded(batch);
        private OracleTargetFencePort.OracleTargetFenceResult fenceResult =
                new OracleTargetFencePort.CommitConfirmed(
                        new FenceReceipt(targetResourceUuid, 5, TARGET_HASH));
        private OracleAtomicPublishPort.OracleAtomicPublishResult publishResult;
        private String receiptPayloadHash = PAYLOAD_HASH;

        private DatasetBinding binding(
                DatasetRole role,
                UUID snapshotUuid,
                String fingerprint,
                String owner,
                String objectName) {
            return new DatasetBinding(
                    role.name().toLowerCase(), role, DatabaseType.ORACLE,
                    DataObjectType.TABLE, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    snapshotUuid, 1, fingerprint, owner + "." + objectName,
                    owner, objectName);
        }

        private PilotWorkerOrchestrator.RunOnceResult run() {
            RunLeasePort leasePort = new RunLeasePort() {
                @Override
                public Optional<ClaimedRun> claimForPreflight(
                        WorkerIdentity worker, Duration lease) {
                    events.add("CLAIM");
                    if (claimFailures-- > 0) {
                        throw new IllegalStateException("lost claim acknowledgement");
                    }
                    return idle ? Optional.empty() : Optional.of(claimed);
                }

                @Override
                public HeartbeatResult heartbeat(RunLeaseToken token, Duration lease) {
                    return HeartbeatResult.accepted(token);
                }

                @Override
                public TargetFenceToken acquireTarget(
                        RunLeaseToken token, String canonicalTargetHash, int identityVersion) {
                    events.add("TARGET_ACQUIRE");
                    acquireTokens.add(token);
                    acquireHashes.add(canonicalTargetHash);
                    if (targetAcquireFailures-- > 0) {
                        throw new IllegalStateException("lost target acknowledgement");
                    }
                    return targetToken;
                }
            };
            WorkerLeaseService leases = new WorkerLeaseService(leasePort);
            PilotWorkerOrchestrator orchestrator = new PilotWorkerOrchestrator(
                    leases,
                    run -> {
                        events.add("EXECUTION");
                        return Optional.of(execution);
                    },
                    (run, context) -> {
                        events.add("PLAN");
                        return plan;
                    },
                    runtimePlan -> {
                        events.add("SNAPSHOTS");
                        return snapshots;
                    },
                    command -> {
                        events.add("TARGET_IDENTITY");
                        return new IdentityReadSucceeded(identity);
                    },
                    command -> {
                        events.add("SOURCE_READ");
                        if (sourceError != null) {
                            throw sourceError;
                        }
                        return sourceResult;
                    },
                    new PilotPublishKeyV1(),
                    transitions,
                    command -> {
                        events.add("TARGET_FENCE");
                        return fenceResult;
                    },
                    command -> {
                        events.add("PUBLISH");
                        if (publishError != null) {
                            throw publishError;
                        }
                        if (publishResult != null) {
                            return publishResult;
                        }
                        PublishIntentEvidence evidence = beginEvidence.getLast();
                        return new OracleAtomicPublishPort.CommitConfirmed(new PublishReceipt(
                                evidence.publishKeyHash(), receiptPayloadHash,
                                evidence.rowCount(), evidence.byteCount()));
                    },
                    (token, duration) -> {
                        events.add("GATE_OPEN");
                        return new FakeGate();
                    });
            return orchestrator.runOnce(
                    new WorkerIdentity("worker-1", UUID.randomUUID()), LEASE);
        }

        private long terminalTransitionCount() {
            return occurrences(events, "FAIL_PREFLIGHT")
                    + occurrences(events, "FAIL_ACTIVE")
                    + occurrences(events, "MARK_UNKNOWN")
                    + occurrences(events, "SUCCESS");
        }

        private final class FakeGate implements LeaseGate {

            private int checkpoints;

            @Override
            public RunLeaseToken checkpoint() {
                events.add("CHECKPOINT");
                if (++checkpoints == gateFailureAtCheckpoint) {
                    throw new LeaseGateException(
                            LeaseGateException.Failure.LEASE_AUTHORITY_LOST);
                }
                return runToken;
            }

            @Override
            public <T> T execute(AuthorizedOperation<T> operation) {
                events.add("GATE_EXECUTE");
                return operation.execute(runToken);
            }

            @Override
            public <T> T completeTerminal(
                    TerminalOperation<T> operation,
                    java.util.function.Predicate<? super T> accepted) {
                events.add("GATE_TERMINAL");
                return operation.execute(runToken);
            }

            @Override
            public void close() {
                events.add("GATE_CLOSE");
            }
        }

        private final class FakeTransitions implements RunExecutionTransitionPort {

            @Override
            public MutationResult completePreflight(ActiveExecutionToken token) {
                events.add("PREFLIGHT_COMPLETE");
                return MutationResult.accepted();
            }

            @Override
            public MutationResult beginPublish(
                    ActiveExecutionToken token, PublishIntentEvidence evidence) {
                events.add("PUBLISH_BEGIN");
                beginEvidence.add(evidence);
                if (beginPublishFailures-- > 0) {
                    throw new IllegalStateException("lost begin acknowledgement");
                }
                return beginPublishRejected
                        ? MutationResult.rejected() : MutationResult.accepted();
            }

            @Override
            public MutationResult failPreflightSafely(
                    RunLeaseToken token, String safeErrorCode) {
                events.add("FAIL_PREFLIGHT");
                return MutationResult.accepted();
            }

            @Override
            public MutationResult failSafely(
                    ActiveExecutionToken token, String safeErrorCode) {
                events.add("FAIL_ACTIVE");
                return MutationResult.accepted();
            }

            @Override
            public MutationResult markOutcomeUnknown(ActiveExecutionToken token) {
                events.add("MARK_UNKNOWN");
                if (markUnknownFailures-- > 0) {
                    throw new IllegalStateException("lost unknown acknowledgement");
                }
                return MutationResult.accepted();
            }

            @Override
            public MutationResult completeSuccessfully(
                    ActiveExecutionToken token, PublishIntentEvidence evidence) {
                events.add("SUCCESS");
                successEvidence.add(evidence);
                if (successFailures-- > 0) {
                    throw new IllegalStateException("lost success acknowledgement");
                }
                return MutationResult.accepted();
            }
        }
    }
}

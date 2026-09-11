package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatResult;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

class WorkerLeaseServiceTest {

    private static final OffsetDateTime FIRST_DEADLINE = OffsetDateTime.of(
            2026, 9, 11, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime REFRESHED_DEADLINE = FIRST_DEADLINE.plusSeconds(60);

    @Test
    void claimCarriesSeparateReleaseAndScenarioPlanHashes() {
        FakeLeasePort port = new FakeLeasePort();
        WorkerLeaseService service = new WorkerLeaseService(port);

        ClaimedRun claim = service.claimForPreflight(
                        new WorkerIdentity("worker-1", UUID.randomUUID()),
                        Duration.ofSeconds(60))
                .orElseThrow();

        assertNotEquals(claim.releaseHash(), claim.planHash());
        assertEquals(FIRST_DEADLINE, claim.token().leaseDeadline());
    }

    @Test
    void acceptedHeartbeatReturnsFreshDatabaseDeadline() {
        FakeLeasePort port = new FakeLeasePort();
        WorkerLeaseService service = new WorkerLeaseService(port);

        RunLeaseToken refreshed = service.heartbeatOrFail(port.runToken, Duration.ofSeconds(60));

        assertEquals(REFRESHED_DEADLINE, refreshed.leaseDeadline());
        assertEquals(port.runToken.generation(), refreshed.generation());
    }

    @Test
    void rejectedHeartbeatAlwaysLosesAuthorityFailClosed() {
        FakeLeasePort port = new FakeLeasePort();
        port.acceptHeartbeat = false;
        WorkerLeaseService service = new WorkerLeaseService(port);

        assertThrows(
                LeaseAuthorityLostException.class,
                () -> service.heartbeatOrFail(port.runToken, Duration.ofSeconds(60)));
    }

    @Test
    void targetFenceIsASeparateTokenWithoutAStaleLocalDeadline() {
        FakeLeasePort port = new FakeLeasePort();
        WorkerLeaseService service = new WorkerLeaseService(port);

        TargetFenceToken target = service.acquireTarget(
                port.runToken, "c".repeat(64), 1);

        assertEquals(port.runToken.runUuid(), target.runUuid());
        assertEquals(port.runToken.generation(), target.runGeneration());
        assertEquals(9, target.targetGeneration());
        assertEquals("c".repeat(64), target.canonicalTargetHash());
        assertEquals(1, target.targetIdentityVersion());
    }

    @Test
    void heartbeatTransportFailureAlsoLosesAuthority() {
        FakeLeasePort port = new FakeLeasePort();
        port.heartbeatFailure = new IllegalStateException("database unavailable");
        WorkerLeaseService service = new WorkerLeaseService(port);

        LeaseAuthorityLostException failure = assertThrows(
                LeaseAuthorityLostException.class,
                () -> service.heartbeatOrFail(port.runToken, Duration.ofSeconds(60)));

        assertEquals(port.heartbeatFailure, failure.getCause());
    }

    private static final class FakeLeasePort implements RunLeasePort {

        private final RunLeaseToken runToken = new RunLeaseToken(
                UUID.randomUUID(), "worker-1", 3, FIRST_DEADLINE);
        private boolean acceptHeartbeat = true;
        private RuntimeException heartbeatFailure;

        @Override
        public Optional<ClaimedRun> claimForPreflight(WorkerIdentity worker, Duration lease) {
            return Optional.of(new ClaimedRun(
                    runToken, "a".repeat(64), "b".repeat(64)));
        }

        @Override
        public HeartbeatResult heartbeat(RunLeaseToken token, Duration lease) {
            if (heartbeatFailure != null) {
                throw heartbeatFailure;
            }
            if (!acceptHeartbeat) {
                return HeartbeatResult.rejected();
            }
            return HeartbeatResult.accepted(new RunLeaseToken(
                    token.runUuid(), token.workerReference(), token.generation(),
                    REFRESHED_DEADLINE));
        }

        @Override
        public TargetFenceToken acquireTarget(
                RunLeaseToken token, String canonicalTargetHash, int identityVersion) {
            return new TargetFenceToken(
                    token.runUuid(), token.workerReference(), token.generation(),
                    UUID.randomUUID(), 9, canonicalTargetHash, identityVersion);
        }
    }
}

package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatOutcome;
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatResult;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

/** Domain facade only; Phase 3A deliberately has no poller or worker lifecycle bean. */
@Service
final class WorkerLeaseService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkerLeaseService.class);

    private final RunLeasePort leases;
    private final RuntimeOperationRegistry operations;

    @Autowired
    WorkerLeaseService(RunLeasePort leases, RuntimeOperationRegistry operations) {
        this.leases = leases;
        this.operations = operations;
    }

    WorkerLeaseService(RunLeasePort leases) {
        this(leases, new RuntimeOperationRegistry());
    }

    Optional<ClaimedRun> claimForPreflight(WorkerIdentity worker, Duration lease) {
        try {
            leases.recoverExpiredCancellations(10);
        }
        catch (RuntimeException ignored) {
            LOG.warn("Expired cancellation recovery unconfirmed; durable requests remain pending.");
        }
        return leases.claimForPreflight(worker, lease);
    }

    RunLeaseToken heartbeatOrFail(RunLeaseToken token, Duration lease) {
        HeartbeatResult result;
        try {
            result = leases.heartbeat(token, lease);
        }
        catch (RuntimeException exception) {
            throw new LeaseAuthorityLostException(
                    "Worker lease heartbeat could not be confirmed; no further work is authorized.",
                    exception);
        }
        if (result.outcome() != HeartbeatOutcome.ACCEPTED
                || result.refreshedToken() == null) {
            throw new LeaseAuthorityLostException(
                    "Worker lease heartbeat was rejected; no further work is authorized.");
        }
        return result.refreshedToken();
    }

    boolean cancellationRequested(RunLeaseToken token) {
        boolean requested = leases.cancellationRequested(token);
        if (requested) operations.cancel(token.runUuid(), token.generation());
        return requested;
    }

    boolean completeCancellation(RunLeaseToken token) {
        for (int attempt = 1; ; attempt++) {
            try {
                return leases.completeCancellation(token);
            }
            catch (RuntimeException failure) {
                if (attempt == 3) throw failure;
            }
        }
    }

    TargetFenceToken acquireTarget(
            RunLeaseToken token, String canonicalTargetHash, int identityVersion) {
        return leases.acquireTarget(token, canonicalTargetHash, identityVersion);
    }
}

final class LeaseAuthorityLostException extends RuntimeException {

    LeaseAuthorityLostException(String message) {
        super(message);
    }

    LeaseAuthorityLostException(String message, Throwable cause) {
        super(message, cause);
    }
}

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

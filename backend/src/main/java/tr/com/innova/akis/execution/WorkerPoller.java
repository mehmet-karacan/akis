package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

/** Single-flight poller. CI and local development keep it disabled by default. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "akis.execution.worker-enabled", havingValue = "true")
final class WorkerPoller {

    private final ProcedureWorkerOrchestrator procedures;
    private final WorkerIdentity identity;
    private final Duration lease;
    private final AtomicBoolean running = new AtomicBoolean();

    WorkerPoller(
            ProcedureWorkerOrchestrator procedures,
            @Value("${akis.execution.worker-reference}") String workerReference,
            @Value("${akis.execution.worker-profile-uuid}") UUID profileUuid,
            @Value("${akis.execution.worker-lease-seconds:60}") long leaseSeconds) {
        this.procedures = procedures;
        this.identity = new WorkerIdentity(workerReference, profileUuid);
        this.lease = Duration.ofSeconds(leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${akis.execution.worker-poll-delay-ms:2000}")
    void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            procedures.runOnce(identity, lease);
        }
        finally {
            running.set(false);
        }
    }
}

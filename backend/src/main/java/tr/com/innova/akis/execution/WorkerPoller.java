package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

/** Single-flight poller. CI and local development keep it disabled by default. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "akis.execution.worker-enabled", havingValue = "true")
final class WorkerPoller {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerPoller.class);
    private final ProcedureWorkerOrchestrator procedures;
    private final WorkerIdentity identity;
    private final Duration lease;
    private final AtomicBoolean running = new AtomicBoolean();

    private final JdbcClient jdbc;

    WorkerPoller(
            ProcedureWorkerOrchestrator procedures,
            JdbcClient jdbc,
            @Value("${akis.execution.worker-reference}") String workerReference,
            @Value("${akis.execution.worker-profile-uuid}") UUID profileUuid,
            @Value("${akis.execution.worker-lease-seconds:60}") long leaseSeconds) {
        this.procedures = procedures;
        this.jdbc = jdbc;
        this.identity = new WorkerIdentity(workerReference, profileUuid);
        this.lease = Duration.ofSeconds(leaseSeconds);
        LOG.info("Worker poller enabled: reference={} profile={} lease={}s", workerReference, profileUuid, leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${akis.execution.worker-poll-delay-ms:2000}")
    void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            reapExpiredLeases();
            var result = procedures.runOnce(identity, lease);
            if (!(result instanceof ProcedureWorkerOrchestrator.Idle)) LOG.info("Worker poll result: {}", result);
        }
        finally {
            running.set(false);
        }
    }

    /** Runs whose worker stopped heartbeating are closed and their target released so later runs can fence it. */
    private void reapExpiredLeases() {
        try {
            Integer closed = jdbc.sql("select akis.kiralama_suresi_dolan_calistirmalari_kapat()").query(Integer.class).single();
            if (closed != null && closed > 0) LOG.warn("Closed {} run(s) whose worker lease expired (LEASE_EXPIRED).", closed);
        }
        catch (RuntimeException exception) {
            LOG.warn("Lease expiry reaper failed: {}", exception.toString());
        }
    }
}

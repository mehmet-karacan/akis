package tr.com.innova.akis.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ExecutionFeatureFlags {

    // Configuration is not an implementation claim. Keep deployment-visible
    // capabilities closed until their worker handlers consume these contracts.
    private final boolean recoveryHandlerInstalled;
    private final boolean transferHandlerInstalled;

    private final boolean acceptManualRequests;
    private final boolean workerEnabled;
    private final boolean procedureRuntimeEnabled;
    private final boolean stagedRuntimeEnabled;
    private final boolean recoveryRuntimeEnabled;
    private final boolean transferRecoveryEnabled;

    ExecutionFeatureFlags(
            @Value("${akis.execution.accept-manual-requests:false}") boolean acceptManualRequests,
            @Value("${akis.execution.worker-enabled:false}") boolean workerEnabled,
            @Value("${akis.execution.procedure-runtime-enabled:false}") boolean procedureRuntimeEnabled) {
        this(acceptManualRequests, workerEnabled, procedureRuntimeEnabled, false, false, false);
    }

    ExecutionFeatureFlags(
            @Value("${akis.execution.accept-manual-requests:false}") boolean acceptManualRequests,
            @Value("${akis.execution.worker-enabled:false}") boolean workerEnabled,
            @Value("${akis.execution.procedure-runtime-enabled:false}") boolean procedureRuntimeEnabled,
            @Value("${akis.execution.staged-runtime-enabled:false}") boolean stagedRuntimeEnabled) {
        this(acceptManualRequests, workerEnabled, procedureRuntimeEnabled,
                stagedRuntimeEnabled, false, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ExecutionFeatureFlags(
            @Value("${akis.execution.accept-manual-requests:false}") boolean acceptManualRequests,
            @Value("${akis.execution.worker-enabled:false}") boolean workerEnabled,
            @Value("${akis.execution.procedure-runtime-enabled:false}") boolean procedureRuntimeEnabled,
            @Value("${akis.execution.staged-runtime-enabled:false}") boolean stagedRuntimeEnabled,
            @Value("${akis.execution.recovery-runtime-enabled:false}") boolean recoveryRuntimeEnabled,
            @Value("${akis.execution.transfer-recovery-enabled:false}") boolean transferRecoveryEnabled) {
        // RESUME is consumed by the staged worker (adopts the sealed work table); chunked transfer recovery is still not.
        this(acceptManualRequests, workerEnabled, procedureRuntimeEnabled, stagedRuntimeEnabled,
                recoveryRuntimeEnabled, transferRecoveryEnabled, workerEnabled && stagedRuntimeEnabled, false);
    }

    ExecutionFeatureFlags(
            boolean acceptManualRequests,
            boolean workerEnabled,
            boolean procedureRuntimeEnabled,
            boolean stagedRuntimeEnabled,
            boolean recoveryRuntimeEnabled,
            boolean transferRecoveryEnabled,
            boolean recoveryHandlerInstalled,
            boolean transferHandlerInstalled) {
        this.acceptManualRequests = acceptManualRequests;
        this.workerEnabled = workerEnabled;
        this.procedureRuntimeEnabled = procedureRuntimeEnabled;
        this.stagedRuntimeEnabled = stagedRuntimeEnabled;
        this.recoveryRuntimeEnabled = recoveryRuntimeEnabled;
        this.transferRecoveryEnabled = transferRecoveryEnabled;
        this.recoveryHandlerInstalled = recoveryHandlerInstalled;
        this.transferHandlerInstalled = transferHandlerInstalled;
        if (workerEnabled && !procedureRuntimeEnabled) {
            throw new IllegalStateException(
                    "Execution worker requires the controlled Procedure runtime.");
        }
    }

    boolean acceptManualRequests() {
        return acceptManualRequests;
    }

    boolean workerEnabled() {
        return workerEnabled;
    }

    boolean procedureRuntimeEnabled() { return procedureRuntimeEnabled; }
    boolean stagedRuntimeEnabled() { return stagedRuntimeEnabled; }
    boolean recoveryRuntimeEnabled() { return recoveryRuntimeEnabled; }
    boolean transferRecoveryEnabled() { return transferRecoveryEnabled; }
    boolean recoveryRuntimeReady() {
        return recoveryRuntimeEnabled && recoveryHandlerInstalled;
    }
    boolean transferRecoveryReady() {
        return transferRecoveryEnabled && transferHandlerInstalled;
    }
}

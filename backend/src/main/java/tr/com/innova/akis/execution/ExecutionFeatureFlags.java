package tr.com.innova.akis.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ExecutionFeatureFlags {

    private final boolean acceptManualRequests;
    private final boolean workerEnabled;

    ExecutionFeatureFlags(
            @Value("${akis.execution.accept-manual-requests:false}") boolean acceptManualRequests,
            @Value("${akis.execution.worker-enabled:false}") boolean workerEnabled) {
        this.acceptManualRequests = acceptManualRequests;
        this.workerEnabled = workerEnabled;
        if (workerEnabled) {
            throw new IllegalStateException(
                    "Execution worker cannot be enabled until controlled worker, crash recovery "
                            + "and reconciliation gates are complete.");
        }
    }

    boolean acceptManualRequests() {
        return acceptManualRequests;
    }

    boolean workerEnabled() {
        return workerEnabled;
    }
}

package tr.com.innova.akis.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ExecutionFeatureFlags {

    private final boolean acceptManualRequests;
    private final boolean workerEnabled;

    ExecutionFeatureFlags(
            @Value("${akis.execution.accept-manual-requests:false}") boolean acceptManualRequests,
            @Value("${akis.execution.worker-enabled:false}") boolean workerEnabled,
            @Value("${akis.execution.procedure-runtime-enabled:false}") boolean procedureRuntimeEnabled) {
        this.acceptManualRequests = acceptManualRequests;
        this.workerEnabled = workerEnabled;
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
}

package tr.com.innova.akis.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** One startup line per execution flag so an operator can see why the worker did or did not start. */
@Component
final class ExecutionFlagsStartupLog {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionFlagsStartupLog.class);
    private final Environment environment;

    ExecutionFlagsStartupLog(Environment environment) { this.environment = environment; }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        for (String key : new String[]{"akis.execution.accept-manual-requests", "akis.execution.worker-enabled",
                "akis.execution.procedure-runtime-enabled", "akis.execution.staged-runtime-enabled", "akis.execution.worker-reference"}) {
            String value = environment.getProperty(key);
            LOG.info("Execution flag {} = '{}' (length {})", key, value, value == null ? -1 : value.length());
        }
    }
}

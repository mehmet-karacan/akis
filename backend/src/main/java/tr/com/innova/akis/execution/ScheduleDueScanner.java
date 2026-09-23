package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lists due schedules (V049) and asks {@link ScheduleFireService} to re-check and fire each one in its own
 * transaction. Single-flight like {@code WorkerPoller}; disabled by default, enable per deployment.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "akis.execution.scheduler-enabled", havingValue = "true")
final class ScheduleDueScanner {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleDueScanner.class);

    private final JdbcClient jdbc;
    private final ScheduleFireService fireService;
    private final AtomicBoolean running = new AtomicBoolean();

    ScheduleDueScanner(JdbcClient jdbc, ScheduleFireService fireService) {
        this.jdbc = jdbc;
        this.fireService = fireService;
    }

    @Scheduled(fixedDelayString = "${akis.execution.schedule-poll-delay-ms:15000}")
    void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            List<UUID> due = jdbc.sql("""
                            select uuid from akis.zamanlama
                             where durum_kodu = 'AKTIF' and sonraki_tetikleme_zamani <= now()
                             order by sonraki_tetikleme_zamani limit 50
                            """).query(UUID.class).list();
            for (UUID scheduleUuid : due) {
                try {
                    fireService.tryFire(scheduleUuid);
                }
                catch (RuntimeException failure) {
                    LOG.warn("Schedule {} due-scan tick failed: {}", scheduleUuid, failure.toString());
                }
            }
        }
        finally {
            running.set(false);
        }
    }
}

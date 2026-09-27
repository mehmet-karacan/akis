package tr.com.innova.akis.execution;

import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lists due schedules (V049/V058) and asks {@link ScheduleFireService} to re-check and fire each one in its own
 * short transaction. Single-flight like {@code WorkerPoller}; disabled by default, enable per deployment.
 * The due lookup is bounded to a short fire window so a long outage cannot release a massive burst.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "akis.execution.scheduler-enabled", havingValue = "true")
final class ScheduleDueScanner {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleDueScanner.class);
    /** Maximum look-ahead for the due list: prevents firing occurrences far in the future. */
    private static final Duration FIRE_WINDOW = Duration.ofMinutes(2);

    private final JdbcClient jdbc;
    private final ScheduleFireService fireService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    ScheduleDueScanner(JdbcClient jdbc, ScheduleFireService fireService) {
        this(jdbc, fireService, Clock.systemUTC());
    }

    ScheduleDueScanner(JdbcClient jdbc, ScheduleFireService fireService, Clock clock) {
        this.jdbc = jdbc;
        this.fireService = fireService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${akis.execution.schedule-poll-delay-ms:15000}")
    void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<UUID> due = jdbc.sql("""
                            select uuid from akis.zamanlama
                             where durum_kodu = 'AKTIF'
                               and sonraki_tetikleme_zamani <= :now
                               and sonraki_tetikleme_zamani <= :fireDeadline
                               and arsivlenme_zamani is null
                             order by sonraki_tetikleme_zamani limit 50
                            """)
                    .param("now", now)
                    .param("fireDeadline", now.plus(FIRE_WINDOW))
                    .query(UUID.class).list();
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

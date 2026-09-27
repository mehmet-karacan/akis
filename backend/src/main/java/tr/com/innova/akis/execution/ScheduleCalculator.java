package tr.com.innova.akis.execution;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;

import tr.com.innova.akis.metadata.ApiException;

/**
 * Single, server-side source of truth for cron/zone calculations used by preview, save and the
 * due-scan tick. Keeping all of it in one place guarantees that what the editor shows, what the
 * database stores and what the poller fires are the same occurrence sequence.
 */
final class ScheduleCalculator {

    private ScheduleCalculator() {
    }

    static final String DEFAULT_TIME_ZONE = "Europe/Istanbul";

    /**
     * Parses and validates a cron expression. Throws {@link ApiException} with
     * {@code SCHEDULE_VALIDATION_FAILED} on invalid input.
     */
    static CronExpression parseCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw validation("Cron ifadesi gereklidir.");
        }
        try {
            return CronExpression.parse(cronExpression.trim());
        }
        catch (IllegalArgumentException invalid) {
            throw validation("Cron ifadesi geçersiz: " + invalid.getMessage());
        }
    }

    /**
     * Resolves a time-zone id, defaulting to {@link #DEFAULT_TIME_ZONE} when null/blank.
     */
    static ZoneId parseZone(String timeZone) {
        String raw = timeZone == null || timeZone.isBlank() ? DEFAULT_TIME_ZONE : timeZone.trim();
        try {
            return ZoneId.of(raw);
        }
        catch (RuntimeException invalid) {
            throw validation("Zaman dilimi geçersiz: " + raw);
        }
    }

    /**
     * Computes the next N occurrences (default 3) starting from {@code from} in the given zone.
     * Empty when the cron exhausts the look-ahead window before producing N results.
     */
    static List<OffsetDateTime> nextOccurrences(CronExpression cron, ZoneId zone, int count, Instant from) {
        List<OffsetDateTime> results = new ArrayList<>(count);
        ZonedDateTime cursor = from.atZone(zone);
        while (results.size() < count) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null) break;
            results.add(next.toOffsetDateTime());
            cursor = next;
        }
        return results;
    }

    static List<OffsetDateTime> nextOccurrences(CronExpression cron, ZoneId zone, int count) {
        return nextOccurrences(cron, zone, count, Instant.now());
    }

    /**
     * Convenience used by the tick path: the very next future occurrence after now in the stored zone.
     */
    static OffsetDateTime nextFireTime(CronExpression cron, ZoneId zone) {
        return nextFireTime(cron, zone, Instant.now());
    }

    static OffsetDateTime nextFireTime(CronExpression cron, ZoneId zone, Instant from) {
        ZonedDateTime now = from.atZone(zone);
        ZonedDateTime next = cron.next(now);
        if (next == null) {
            throw validation("Cron ifadesi için sonraki tetikleme zamanı hesaplanamadı.");
        }
        return next.toOffsetDateTime();
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SCHEDULE_VALIDATION_FAILED", message);
    }
}

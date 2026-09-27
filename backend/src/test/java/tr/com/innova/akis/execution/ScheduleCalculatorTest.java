package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;

import tr.com.innova.akis.metadata.ApiException;

class ScheduleCalculatorTest {

    @Test
    void defaultTimeZoneIsEuropeIstanbul() {
        assertEquals("Europe/Istanbul", ScheduleCalculator.DEFAULT_TIME_ZONE);
    }

    @Test
    void parseZoneDefaultsToEuropeIstanbul() {
        assertEquals(ZoneId.of("Europe/Istanbul"), ScheduleCalculator.parseZone(null));
        assertEquals(ZoneId.of("Europe/Istanbul"), ScheduleCalculator.parseZone("  "));
    }

    @Test
    void parseZoneAcceptsUtc() {
        assertEquals(ZoneId.of("UTC"), ScheduleCalculator.parseZone("UTC"));
    }

    @Test
    void parseZoneRejectsInvalidZone() {
        ApiException error = assertThrows(ApiException.class, () -> ScheduleCalculator.parseZone("Mars/Phobos"));
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertEquals("SCHEDULE_VALIDATION_FAILED", error.code());
    }

    @Test
    void nextOccurrencesRespectsDstTransition() {
        // Europe/Berlin switches from +02 to +01 on 2026-10-25.
        Instant beforeDst = LocalDateTime.of(2026, 10, 24, 23, 0)
                .atZone(ZoneId.of("Europe/Berlin")).toInstant();
        CronExpression cron = CronExpression.parse("0 0 3 * * *"); // 03:00 every day
        List<OffsetDateTime> next = ScheduleCalculator.nextOccurrences(cron, ZoneId.of("Europe/Berlin"), 3, beforeDst);

        assertEquals(3, next.size());
        // 25 Oct 03:00 is +01 after the fallback; 26 Oct 03:00 remains +01.
        assertEquals(ZoneOffset.ofHours(1), next.get(0).getOffset());
        assertEquals(ZoneOffset.ofHours(1), next.get(1).getOffset());
        assertEquals(Month.OCTOBER, next.get(0).getMonth());
        assertEquals(Month.OCTOBER, next.get(1).getMonth());
    }

    @Test
    void leapDayIsHandled() {
        // 29 Feb 2028 exists; 29 Feb 2027 does not.
        Instant before = ZonedDateTime.of(2027, 2, 28, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        CronExpression cron = CronExpression.parse("0 0 0 29 2 ?");
        List<OffsetDateTime> next = ScheduleCalculator.nextOccurrences(cron, ZoneId.of("UTC"), 2, before);

        assertEquals(2, next.size());
        assertEquals(LocalDateTime.of(2028, 2, 29, 0, 0), next.get(0).toLocalDateTime());
        assertEquals(LocalDateTime.of(2032, 2, 29, 0, 0), next.get(1).toLocalDateTime());
    }

    @Test
    void monthEndExpressionDoesNotOvershoot() {
        // Last day of every month at 22:00.
        CronExpression cron = CronExpression.parse("0 0 22 L * ?");
        Instant start = ZonedDateTime.of(2026, 1, 15, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        List<OffsetDateTime> next = ScheduleCalculator.nextOccurrences(cron, ZoneId.of("UTC"), 3, start);

        assertEquals(LocalDateTime.of(2026, 1, 31, 22, 0), next.get(0).toLocalDateTime());
        assertEquals(LocalDateTime.of(2026, 2, 28, 22, 0), next.get(1).toLocalDateTime());
        assertEquals(LocalDateTime.of(2026, 3, 31, 22, 0), next.get(2).toLocalDateTime());
    }

    @Test
    void previewReturnsNextThreeOccurrences() {
        Instant start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        CronExpression cron = CronExpression.parse("0 0 * * * *");
        List<OffsetDateTime> next = ScheduleCalculator.nextOccurrences(cron, ZoneId.of("UTC"), 3, start);

        assertEquals(3, next.size());
        assertTrue(next.get(0).isAfter(start.atZone(ZoneId.of("UTC")).toOffsetDateTime()));
    }
}

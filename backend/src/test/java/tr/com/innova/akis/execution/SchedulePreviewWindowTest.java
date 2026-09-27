package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SchedulePreviewWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-27T06:00:00Z");
    private final ScheduleService service = new ScheduleService(null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void pastStartNeverProducesPastOccurrences() {
        var preview = service.preview("0 0 * * * *", "UTC", NOW.minusSeconds(86400), null);

        assertTrue(preview.syntaxValid());
        assertEquals(3, preview.nextOccurrences().size());
        assertTrue(preview.nextOccurrences().stream().allMatch(value -> value.toInstant().isAfter(NOW)));
    }

    @Test
    void boundedWindowShowsOnlyRealOccurrences() {
        var preview = service.preview("0 0 * * * *", "UTC", NOW, NOW.plusSeconds(7200));

        assertTrue(preview.syntaxValid());
        assertTrue(preview.exhausted());
        assertEquals(2, preview.nextOccurrences().size());
        assertEquals(NOW, preview.nextOccurrences().getFirst().toInstant());
        assertEquals(Instant.parse("2026-09-27T07:00:00Z"), preview.nextOccurrences().getLast().toInstant());
    }

    @Test
    void explicitStartEqualToNowIncludesAlignedOccurrence() {
        var preview = service.preview("0 0 * * * *", "UTC", NOW, null);

        assertTrue(preview.syntaxValid());
        assertEquals(NOW, preview.nextOccurrences().getFirst().toInstant());
        assertEquals(3, preview.nextOccurrences().size());
    }

    @Test
    void alignedFutureStartIsIncluded() {
        var start = Instant.parse("2026-09-27T08:00:00Z");
        var preview = service.preview("0 0 * * * *", "UTC", start, start.plusSeconds(3600));

        assertTrue(preview.syntaxValid());
        assertEquals(1, preview.nextOccurrences().size());
        assertEquals(start, preview.nextOccurrences().getFirst().toInstant());
    }

    @Test
    void expiredWindowIsRejected() {
        var preview = service.preview("0 0 * * * *", "UTC", NOW.minusSeconds(86400), NOW.minusSeconds(3600));

        assertFalse(preview.syntaxValid());
        assertTrue(preview.nextOccurrences().isEmpty());
        assertFalse(preview.errors().isEmpty());
    }

    @Test
    void impossibleButSyntacticallyValidCronHasNoInventedOccurrences() {
        var preview = service.preview("0 0 0 31 2 ?", "UTC", null, null);

        assertTrue(preview.syntaxValid());
        assertTrue(preview.exhausted());
        assertTrue(preview.nextOccurrences().isEmpty());
        assertTrue(preview.errors().isEmpty());
    }
}

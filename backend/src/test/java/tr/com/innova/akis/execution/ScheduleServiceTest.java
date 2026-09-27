package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.security.ApplicationUserPrincipal;
import tr.com.innova.akis.security.AuthorizationService;

class ScheduleServiceTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID PUBLICATION_UUID = UUID.randomUUID();
    private static final Actor ACTOR = new Actor(7, UUID.randomUUID(), "Runner");

    private final FakeAuthorization authorization = new FakeAuthorization();
    private final FakeActorResolver actorResolver = new FakeActorResolver();
    private ScheduleService service;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new ApplicationUserPrincipal(
                                ACTOR.id(), ACTOR.uuid(), "runner", "Runner",
                                "{noop}test", true),
                        null, List.of()));
        service = new ScheduleService(JdbcClient.create(new SimpleDriverDataSource()), actorResolver, authorization);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewUsesServerSideCalculationAndReturnsThreeOccurrences() {
        ScheduleService.Preview preview = service.preview("0 0 * * * *", "Europe/Istanbul");

        assertEquals("Europe/Istanbul", preview.timeZone());
        assertEquals(3, preview.nextOccurrences().size());
        assertTrue(preview.nextOccurrences().get(0).isAfter(OffsetDateTime.now(ZoneId.of("Europe/Istanbul")).minusMinutes(1)));
    }

    @Test
    void previewDefaultsZoneToEuropeIstanbul() {
        ScheduleService.Preview preview = service.preview("0 0 * * * *", null);

        assertEquals("Europe/Istanbul", preview.timeZone());
        assertEquals(3, preview.nextOccurrences().size());
    }

    @Test
    void previewUsesInjectedServerClock() {
        Instant fixed = Instant.parse("2026-09-26T10:15:00Z");
        service = new ScheduleService(
                JdbcClient.create(new SimpleDriverDataSource()), actorResolver, authorization,
                Clock.fixed(fixed, ZoneId.of("UTC")));

        ScheduleService.Preview preview = service.preview("0 0 * * * *", "UTC");

        assertEquals(Instant.parse("2026-09-26T11:00:00Z"), preview.nextOccurrences().get(0).toInstant());
        assertEquals(Instant.parse("2026-09-26T10:15:00Z"), preview.serverTime().toInstant());
        assertTrue(preview.syntaxValid());
        assertTrue(!preview.exhausted());
    }

    @Test
    void previewRespectsDateWindowWithoutInventingOccurrences() {
        Instant fixed = Instant.parse("2026-09-26T10:15:00Z");
        service = new ScheduleService(
                JdbcClient.create(new SimpleDriverDataSource()), actorResolver, authorization,
                Clock.fixed(fixed, ZoneId.of("UTC")));

        ScheduleService.Preview preview = service.preview(
                "0 0 * * * *", "UTC", fixed, Instant.parse("2026-09-26T12:30:00Z"));

        assertEquals(2, preview.nextOccurrences().size());
        assertTrue(preview.exhausted());
        assertTrue(preview.errors().isEmpty());
    }

    @Test
    void previewReturnsStructuredValidationForInvalidCron() {
        ScheduleService.Preview preview = service.preview("not-a-cron", "UTC");

        assertFalse(preview.syntaxValid());
        assertTrue(preview.nextOccurrences().isEmpty());
        assertTrue(preview.errors().stream().anyMatch(error -> error.contains("Cron ifadesi geçersiz")));
    }

    @Test
    void previewReturnsStructuredValidationForInvalidZone() {
        ScheduleService.Preview preview = service.preview("0 0 * * * *", "Mars/Phobos");

        assertFalse(preview.syntaxValid());
        assertTrue(preview.nextOccurrences().isEmpty());
        assertTrue(preview.errors().stream().anyMatch(error -> error.contains("Zaman dilimi geçersiz")));
    }

    private static final class FakeAuthorization extends AuthorizationService {
        private FakeAuthorization() {
            super(null);
        }

        @Override
        public void requireProjectPermission(UUID projectUuid, String permissionCode) {
        }
    }

    private static final class FakeActorResolver extends RunActorResolver {
        private FakeActorResolver() {
            super(null);
        }

        @Override
        Actor currentActor() {
            return ACTOR;
        }
    }
}

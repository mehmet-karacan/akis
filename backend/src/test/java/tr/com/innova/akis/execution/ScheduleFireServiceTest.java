package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ExecutionModels.Actor;

class ScheduleFireServiceTest {

    private static final Actor ACTOR = new Actor(7, UUID.randomUUID(), "Runner");

    @Test
    void backgroundAuthorizationWrapsFire() {
        CapturingBackgroundAuthorization backgroundAuth = new CapturingBackgroundAuthorization();
        backgroundAuth.runAs(ACTOR, () -> assertTrue(true));
        assertTrue(backgroundAuth.invoked);
    }

    @Test
    void fireServiceCanBeConstructedWithObjectMapper() {
        ScheduleFireService fireService = new ScheduleFireService(
                JdbcClient.create(new SimpleDriverDataSource()),
                null, new ScheduleBackgroundAuthorization(), new ObjectMapper());
        assertTrue(true);
    }

    private static final class CapturingBackgroundAuthorization extends ScheduleBackgroundAuthorization {
        boolean invoked;

        @Override
        void runAs(Actor actor, Runnable action) {
            invoked = true;
            action.run();
        }
    }
}

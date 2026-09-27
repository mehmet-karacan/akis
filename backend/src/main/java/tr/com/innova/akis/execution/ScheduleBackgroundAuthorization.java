package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.security.ApplicationUserPrincipal;

/**
 * Explicit background authorization for the schedule poller. When the due-scan tick fires a schedule,
 * it does not run inside an HTTP request and has no session; this component installs a short-lived,
 * explicit security context for the schedule owner so the run can be attributed to them and the
 * execution store's actor check passes. The context is always cleared in a finally block.
 */
@Component
class ScheduleBackgroundAuthorization {

    void runAs(Actor actor, Runnable action) {
        SecurityContext original = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new ApplicationUserPrincipal(
                            actor.id(), actor.uuid(), "schedule-" + actor.id(), actor.name(),
                            "{noop}schedule", true),
                    null, List.of()));
            SecurityContextHolder.setContext(context);
            action.run();
        }
        finally {
            SecurityContextHolder.setContext(original);
        }
    }

    ApplicationUserPrincipal principalFor(Actor actor) {
        return new ApplicationUserPrincipal(
                actor.id(), actor.uuid(), "schedule-" + actor.id(), actor.name(),
                "{noop}schedule", true);
    }

    UUID actorUuidFor(Actor actor) {
        return actor.uuid();
    }
}

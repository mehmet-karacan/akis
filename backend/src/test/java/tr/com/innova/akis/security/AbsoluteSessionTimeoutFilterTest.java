package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AbsoluteSessionTimeoutFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void expiresSessionAtAbsoluteDeadline() throws Exception {
        var filter = new AbsoluteSessionTimeoutFilter(
                Duration.ofHours(8), Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new MockHttpServletRequest();
        var session = (MockHttpSession) request.getSession(true);
        session.setAttribute(
                AbsoluteSessionTimeoutFilter.AUTHENTICATED_AT,
                NOW.minus(Duration.ofHours(8)).toEpochMilli());
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        assertNull(chain.getRequest());
        assertTrue(session.isInvalid());
    }

    @Test
    void letsUnexpiredSessionContinue() throws Exception {
        var filter = new AbsoluteSessionTimeoutFilter(
                Duration.ofHours(8), Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(
                AbsoluteSessionTimeoutFilter.AUTHENTICATED_AT,
                NOW.minus(Duration.ofHours(7)).toEpochMilli());
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }
}

package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ActiveUserSessionFilterTest {

    private static final String HASH = "{argon2}hash";

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void continuesWhenPrincipalMatchesActiveStoredHash() throws Exception {
        LoginAttemptRepository users = mock(LoginAttemptRepository.class);
        when(users.sessionUser(7L)).thenReturn(Optional.of(
                new LoginAttemptRepository.SessionUserState(7L, "AKTIF", HASH)));
        var filter = new ActiveUserSessionFilter(users);
        var request = authenticatedRequest(principal(7L, HASH));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void invalidatesSessionWhenUserIsPassive() throws Exception {
        LoginAttemptRepository users = mock(LoginAttemptRepository.class);
        when(users.sessionUser(7L)).thenReturn(Optional.of(
                new LoginAttemptRepository.SessionUserState(7L, "PASIF", HASH)));
        var filter = new ActiveUserSessionFilter(users);
        var request = authenticatedRequest(principal(7L, HASH));
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
    }

    @Test
    void invalidatesSessionWhenPasswordHashChanged() throws Exception {
        LoginAttemptRepository users = mock(LoginAttemptRepository.class);
        when(users.sessionUser(7L)).thenReturn(Optional.of(
                new LoginAttemptRepository.SessionUserState(7L, "AKTIF", "{argon2}new-hash")));
        var filter = new ActiveUserSessionFilter(users);
        var request = authenticatedRequest(principal(7L, HASH));
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
    }

    @Test
    void invalidatesSessionWhenUserNotFound() throws Exception {
        LoginAttemptRepository users = mock(LoginAttemptRepository.class);
        when(users.sessionUser(7L)).thenReturn(Optional.empty());
        var filter = new ActiveUserSessionFilter(users);
        var request = authenticatedRequest(principal(7L, HASH));
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
    }

    @Test
    void continuesWhenNoAuthentication() throws Exception {
        var filter = new ActiveUserSessionFilter(mock(LoginAttemptRepository.class));
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
    }

    private static ApplicationUserPrincipal principal(long userId, String hash) {
        return new ApplicationUserPrincipal(
                userId, UUID.randomUUID(), "user" + userId, "Test User", hash, true);
    }

    private static MockHttpServletRequest authenticatedRequest(ApplicationUserPrincipal principal) {
        var request = new MockHttpServletRequest();
        request.getSession(true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        return request;
    }
}

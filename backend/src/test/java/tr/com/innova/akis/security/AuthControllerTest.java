package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import tr.com.innova.akis.metadata.ApiException;

class AuthControllerTest {

    private static final UUID USER_UUID = UUID.randomUUID();

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginCreatesSessionAndReturnsProfile() throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        LocalAuthenticationService local = mock(LocalAuthenticationService.class);
        SessionRevocationService sessions = mock(SessionRevocationService.class);
        PasswordSetupService setup = mock(PasswordSetupService.class);
        var controller = new AuthController(manager, contexts, local, sessions, setup);

        var principal = principal(7L);
        when(manager.authenticate(any(Authentication.class)))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));

        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var response = new MockHttpServletResponse();

        var result = controller.login(
                new AuthController.LoginRequest("mehmet", "parola"), request, response);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(7L, result.getBody().id());
        assertEquals("mehmet", result.getBody().kullaniciKodu());
        assertNotNull(request.getSession(false).getAttribute(
                AbsoluteSessionTimeoutFilter.AUTHENTICATED_AT));
        assertTrue(request.getSession(false).getId() != null);
        verify(contexts).saveContext(any(), any(), any());
    }

    @Test
    void loginReturnsGenericUnauthorizedOnBadCredentials() {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        LocalAuthenticationService local = mock(LocalAuthenticationService.class);
        SessionRevocationService sessions = mock(SessionRevocationService.class);
        PasswordSetupService setup = mock(PasswordSetupService.class);
        var controller = new AuthController(manager, contexts, local, sessions, setup);

        when(manager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("geçersiz"));

        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        ApiException error = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                () -> controller.login(
                        new AuthController.LoginRequest("mehmet", "parola"), request, response));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("INVALID_CREDENTIALS", error.code());
    }

    @Test
    void logoutClearsContextAndReturnsNoContent() {
        var controller = controller();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal(7L), null, List.of()));

        var result = controller.logout(
                SecurityContextHolder.getContext().getAuthentication(), request, response);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void changePasswordUpdatesPrincipalAndRevokesOtherSessions() throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        LocalAuthenticationService local = mock(LocalAuthenticationService.class);
        SessionRevocationService sessions = mock(SessionRevocationService.class);
        PasswordSetupService setup = mock(PasswordSetupService.class);
        var controller = new AuthController(manager, contexts, local, sessions, setup);

        var current = principal(7L);
        var changed = new ApplicationUserPrincipal(
                7L, USER_UUID, "mehmet", "Mehmet", "{argon2}new", true);
        when(local.changePassword(current, "old", "new")).thenReturn(changed);

        var request = new MockHttpServletRequest();
        request.getSession(true);
        var response = new MockHttpServletResponse();

        var result = controller.changePassword(
                new AuthController.ChangePasswordRequest("old", "new"),
                UsernamePasswordAuthenticationToken.authenticated(current, null, List.of()),
                request, response);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("mehmet", result.getBody().kullaniciKodu());
        verify(sessions).revokeOtherSessions("mehmet", request.getSession(false).getId());
        verify(contexts).saveContext(any(), any(), any());
    }

    @Test
    void setupPasswordConsumesTokenAndReturnsNoContent() {
        var controller = controller();

        var result = controller.setupPassword(
                new AuthController.SetupPasswordRequest("token", "yeniParola"));

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
    }

    private static AuthController controller() {
        return new AuthController(
                mock(AuthenticationManager.class),
                mock(SecurityContextRepository.class),
                mock(LocalAuthenticationService.class),
                mock(SessionRevocationService.class),
                mock(PasswordSetupService.class));
    }

    private static ApplicationUserPrincipal principal(long userId) {
        return new ApplicationUserPrincipal(
                userId, USER_UUID, "mehmet", "Mehmet", "{argon2}hash", true);
    }
}

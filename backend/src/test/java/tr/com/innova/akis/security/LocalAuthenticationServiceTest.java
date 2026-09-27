package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final InetAddress ADDRESS = InetAddress.getLoopbackAddress();
    private final PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 8_192, 1);

    @Test
    void authenticatesActiveUserAndRecordsSuccess() {
        LoginAttemptRepository attempts = mock(LoginAttemptRepository.class);
        when(attempts.registerIpAttempt(any(), any(), any())).thenReturn(1);
        when(attempts.lockUser("mehmet")).thenReturn(Optional.of(new LoginAttemptRepository.LoginUser(
                7, UUID.randomUUID(), "mehmet", "Mehmet Karacan",
                passwords.encode("uzun ve benzersiz parola"), "AKTIF", null)));

        var principal = service(attempts).authenticate(
                "MEHMET", "uzun ve benzersiz parola", ADDRESS);

        assertEquals(7, principal.userId());
        assertEquals("mehmet", principal.userCode());
        verify(attempts).recordSuccess(anyLong(), any(), any());
        verify(attempts).lockUser("mehmet");
        verify(attempts, never()).recordFailure(anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    void changesPasswordAndReturnsPrincipalWithNewHash() {
        LoginAttemptRepository attempts = mock(LoginAttemptRepository.class);
        UUID uuid = UUID.randomUUID();
        when(attempts.lockUser(7L)).thenReturn(Optional.of(new LoginAttemptRepository.LoginUser(
                7, uuid, "mehmet", "Mehmet Karacan",
                passwords.encode("mevcut ve guvenli parola"), "AKTIF", null)));
        var principal = new ApplicationUserPrincipal(
                7, uuid, "mehmet", "Mehmet Karacan", "onceki-hash", true);

        var changed = service(attempts).changePassword(
                principal, "mevcut ve guvenli parola", "yeni ve benzersiz parola");

        org.junit.jupiter.api.Assertions.assertTrue(
                passwords.matches("yeni ve benzersiz parola", changed.passwordHash()));
        verify(attempts).changePassword(anyLong(), any(), any());
    }

    @Test
    void unknownAndWrongPasswordReturnSamePublicFailure() {
        LoginAttemptRepository unknown = mock(LoginAttemptRepository.class);
        when(unknown.registerIpAttempt(any(), any(), any())).thenReturn(1);
        when(unknown.lockUser("yok")).thenReturn(Optional.empty());

        LoginAttemptRepository wrong = mock(LoginAttemptRepository.class);
        when(wrong.registerIpAttempt(any(), any(), any())).thenReturn(1);
        when(wrong.lockUser("mehmet")).thenReturn(Optional.of(new LoginAttemptRepository.LoginUser(
                7, UUID.randomUUID(), "mehmet", "Mehmet",
                passwords.encode("dogru ve uzun bir parola"), "AKTIF", null)));

        BadCredentialsException first = assertThrows(BadCredentialsException.class,
                () -> service(unknown).authenticate("yok", "yanlis", ADDRESS));
        BadCredentialsException second = assertThrows(BadCredentialsException.class,
                () -> service(wrong).authenticate("mehmet", "yanlis", ADDRESS));

        assertEquals(first.getMessage(), second.getMessage());
        verify(wrong).recordFailure(anyLong(), any(), any(), any(), anyInt());
    }

    private LocalAuthenticationService service(LoginAttemptRepository attempts) {
        return new LocalAuthenticationService(
                attempts, passwords, new PasswordPolicy(), Clock.fixed(NOW, ZoneOffset.UTC),
                5, Duration.ofMinutes(15), Duration.ofMinutes(15),
                60, Duration.ofMinutes(1));
    }
}

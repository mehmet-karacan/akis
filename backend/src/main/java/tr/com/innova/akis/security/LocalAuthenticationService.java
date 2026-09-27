package tr.com.innova.akis.security;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.metadata.ApiException;

@Service
public class LocalAuthenticationService {

    private static final String GENERIC_ERROR = "Kullanıcı kodu veya parola geçersiz.";

    private final LoginAttemptRepository attempts;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final int accountLimit;
    private final Duration accountWindow;
    private final Duration accountLock;
    private final int ipLimit;
    private final Duration ipWindow;
    private final String dummyHash;
    private final PasswordPolicy passwordPolicy;

    @Autowired
    public LocalAuthenticationService(
            LoginAttemptRepository attempts,
            PasswordEncoder passwords,
            PasswordPolicy passwordPolicy,
            @Value("${akis.security.login.account-attempt-limit:5}") int accountLimit,
            @Value("${akis.security.login.account-window:15m}") Duration accountWindow,
            @Value("${akis.security.login.account-lock:15m}") Duration accountLock,
            @Value("${akis.security.login.ip-attempt-limit:60}") int ipLimit,
            @Value("${akis.security.login.ip-window:1m}") Duration ipWindow) {
        this(attempts, passwords, passwordPolicy, Clock.systemUTC(), accountLimit, accountWindow,
                accountLock, ipLimit, ipWindow);
    }

    LocalAuthenticationService(
            LoginAttemptRepository attempts,
            PasswordEncoder passwords,
            PasswordPolicy passwordPolicy,
            Clock clock,
            int accountLimit,
            Duration accountWindow,
            Duration accountLock,
            int ipLimit,
            Duration ipWindow) {
        this.attempts = attempts;
        this.passwords = passwords;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
        this.accountLimit = accountLimit;
        this.accountWindow = accountWindow;
        this.accountLock = accountLock;
        this.ipLimit = ipLimit;
        this.ipWindow = ipWindow;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public ApplicationUserPrincipal authenticate(String userCode, String password, InetAddress address) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (attempts.registerIpAttempt(address, now, ipWindow) > ipLimit) {
            passwords.matches(password == null ? "" : password, dummyHash);
            throw invalid();
        }

        String normalizedCode;
        try {
            normalizedCode = UserCodeNormalizer.normalize(userCode);
        }
        catch (IllegalArgumentException exception) {
            passwords.matches(password == null ? "" : password, dummyHash);
            throw invalid();
        }
        var user = attempts.lockUser(normalizedCode);
        if (user.isEmpty()) {
            passwords.matches(password == null ? "" : password, dummyHash);
            throw invalid();
        }

        var row = user.get();
        boolean locked = row.lockedUntil() != null && row.lockedUntil().isAfter(now);
        boolean active = "AKTIF".equals(row.status()) && row.passwordHash() != null;
        boolean matches = passwords.matches(password == null ? "" : password,
                row.passwordHash() == null ? dummyHash : row.passwordHash());
        if (locked || !active || !matches) {
            if (!locked) {
                attempts.recordFailure(row.id(), now, accountWindow, accountLock, accountLimit);
            }
            throw invalid();
        }

        attempts.recordSuccess(row.id(), address, now);
        return new ApplicationUserPrincipal(
                row.id(), row.uuid(), row.userCode(), row.displayName(), row.passwordHash(), true);
    }

    @Transactional
    public ApplicationUserPrincipal changePassword(
            ApplicationUserPrincipal principal,
            String currentPassword,
            String newPassword) {
        if (principal == null || !principal.enabled()) {
            throw invalid();
        }
        passwordPolicy.validate(newPassword);
        var row = attempts.lockUser(principal.userId()).orElseThrow(this::invalid);
        boolean active = "AKTIF".equals(row.status()) && row.passwordHash() != null;
        boolean currentMatches = passwords.matches(
                currentPassword == null ? "" : currentPassword,
                row.passwordHash() == null ? dummyHash : row.passwordHash());
        if (!active || !currentMatches || !row.uuid().equals(principal.userUuid())) {
            throw invalid();
        }
        if (passwords.matches(newPassword, row.passwordHash())) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "PASSWORD_UNCHANGED",
                    "Yeni parola mevcut paroladan farklı olmalıdır.");
        }
        String passwordHash = passwords.encode(newPassword);
        attempts.changePassword(row.id(), passwordHash, OffsetDateTime.now(clock));
        return new ApplicationUserPrincipal(
                row.id(), row.uuid(), row.userCode(), row.displayName(), passwordHash, true);
    }

    private BadCredentialsException invalid() {
        return new BadCredentialsException(GENERIC_ERROR);
    }
}

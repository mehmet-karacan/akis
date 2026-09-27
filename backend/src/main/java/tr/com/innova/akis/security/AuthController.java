package tr.com.innova.akis.security;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.metadata.ApiException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final LocalAuthenticationService localAuthentication;
    private final SessionRevocationService sessions;
    private final PasswordSetupService passwordSetup;
    private final SecurityContextHolderStrategy strategy =
            SecurityContextHolder.getContextHolderStrategy();
    private final WebAuthenticationDetailsSource details = new WebAuthenticationDetailsSource();

    AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository contexts,
            LocalAuthenticationService localAuthentication,
            SessionRevocationService sessions,
            PasswordSetupService passwordSetup) {
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.localAuthentication = localAuthentication;
        this.sessions = sessions;
        this.passwordSetup = passwordSetup;
    }

    @GetMapping("/csrf")
    ResponseEntity<CsrfView> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfView(token.getToken(), token.getHeaderName(), token.getParameterName()));
    }

    @PostMapping("/login")
    ResponseEntity<UserProfile> login(
            @Valid @RequestBody LoginRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        var candidate = UsernamePasswordAuthenticationToken.unauthenticated(
                input.kullaniciKodu(), input.parola());
        candidate.setDetails(details.buildDetails(request));
        try {
            Authentication authenticated = authenticationManager.authenticate(candidate);
            var context = strategy.createEmptyContext();
            context.setAuthentication(authenticated);
            strategy.setContext(context);
            request.getSession(true).setAttribute(
                    AbsoluteSessionTimeoutFilter.AUTHENTICATED_AT,
                    System.currentTimeMillis());
            request.changeSessionId();
            contexts.saveContext(context, request, response);
            return ResponseEntity.created(URI.create("/api/v1/auth/me"))
                    .cacheControl(CacheControl.noStore())
                    .body(UserProfile.from(authenticated));
        }
        catch (BadCredentialsException exception) {
            throw new ApiException(
                    UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "Kullanıcı kodu veya parola geçersiz.");
        }
    }

    @GetMapping("/me")
    ResponseEntity<UserProfile> me(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(UserProfile.from(authentication));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/password/change")
    ResponseEntity<UserProfile> changePassword(
            @Valid @RequestBody ChangePasswordRequest input,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        ApplicationUserPrincipal current = principal(authentication);
        ApplicationUserPrincipal changed = localAuthentication.changePassword(
                current, input.mevcutParola(), input.yeniParola());
        var updated = UsernamePasswordAuthenticationToken.authenticated(
                changed, null, changed.getAuthorities());
        updated.setDetails(authentication.getDetails());
        var context = strategy.createEmptyContext();
        context.setAuthentication(updated);
        strategy.setContext(context);
        var session = request.getSession(false);
        if (session == null) {
            throw new ApiException(UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Kimlik doğrulaması gereklidir.");
        }
        contexts.saveContext(context, request, response);
        sessions.revokeOtherSessions(changed.userCode(), session.getId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(UserProfile.from(updated));
    }

    @PostMapping("/password/setup")
    ResponseEntity<Void> setupPassword(@Valid @RequestBody SetupPasswordRequest input) {
        passwordSetup.consume(input.token(), input.yeniParola());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record LoginRequest(@NotBlank String kullaniciKodu, @NotBlank String parola) {
    }

    record ChangePasswordRequest(
            @NotBlank String mevcutParola,
            @NotBlank @Size(max = 128) String yeniParola) {
    }

    record SetupPasswordRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(max = 128) String yeniParola) {
    }

    record CsrfView(String token, String headerName, String parameterName) {
    }

    record UserProfile(long id, java.util.UUID uuid, String kullaniciKodu, String gorunenAd) {
        static UserProfile from(Authentication authentication) {
            ApplicationUserPrincipal principal = principal(authentication);
            return new UserProfile(
                    principal.userId(), principal.userUuid(), principal.userCode(), principal.displayName());
        }
    }

    private static ApplicationUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof ApplicationUserPrincipal principal)) {
            throw new ApiException(UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Kimlik doğrulaması gereklidir.");
        }
        return principal;
    }
}

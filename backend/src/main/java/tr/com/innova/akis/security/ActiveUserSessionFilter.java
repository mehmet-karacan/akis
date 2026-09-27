package tr.com.innova.akis.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class ActiveUserSessionFilter extends OncePerRequestFilter {

    private final LoginAttemptRepository users;

    ActiveUserSessionFilter(LoginAttemptRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof ApplicationUserPrincipal principal
                && !isCurrent(principal)) {
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"about:blank","title":"Oturum geçersiz",\
                    "status":401,"detail":"Oturum artık geçerli değil.",\
                    "code":"SESSION_INVALIDATED"}
                    """);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isCurrent(ApplicationUserPrincipal principal) {
        return users.sessionUser(principal.userId())
                .filter(user -> "AKTIF".equals(user.status()))
                .filter(user -> user.passwordHash() != null)
                .filter(user -> MessageDigest.isEqual(
                        user.passwordHash().getBytes(StandardCharsets.UTF_8),
                        principal.passwordHash().getBytes(StandardCharsets.UTF_8)))
                .isPresent();
    }
}

package tr.com.innova.akis.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    static final String AUTHENTICATED_AT = "AKIS_AUTHENTICATED_AT";

    private final Duration timeout;
    private final Clock clock;

    @Autowired
    AbsoluteSessionTimeoutFilter(
            @Value("${akis.security.session.absolute-timeout:8h}") Duration timeout) {
        this(timeout, Clock.systemUTC());
    }

    AbsoluteSessionTimeoutFilter(Duration timeout, Clock clock) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Mutlak oturum süresi pozitif olmalıdır.");
        }
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object authenticatedAt = session == null ? null : session.getAttribute(AUTHENTICATED_AT);
        if (authenticatedAt instanceof Long startedAt
                && clock.millis() - startedAt >= timeout.toMillis()) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"code\":\"SESSION_EXPIRED\",\"message\":\"Oturum süresi doldu.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

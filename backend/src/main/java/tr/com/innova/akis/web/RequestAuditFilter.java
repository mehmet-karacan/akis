package tr.com.innova.akis.web;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestAuditFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestAuditFilter.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern PROJECT_PATH = Pattern.compile(
            "^/api/v[12]/projects/([0-9a-fA-F-]{36})(?:/.*)?$");
    private static final Pattern UUID_PATH_SEGMENT = Pattern.compile(
            "(?:^|/)([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/|$)");

    private final AuditRepository repository;
    private final ObjectMapper objectMapper;

    public RequestAuditFilter(AuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/v1/") || request.getRequestURI().startsWith("/api/v2/"))
                || !MUTATING_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            appendAudit(request, response);
        }
    }

    private void appendAudit(HttpServletRequest request, HttpServletResponse response) {
        try {
            String path = request.getRequestURI();
            UUID projectUuid = projectUuid(path);
            Long projectId = projectUuid == null
                    ? null
                    : repository.findProjectId(projectUuid).orElse(null);
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("method", request.getMethod());
            detail.put("path", path);
            detail.put("status", response.getStatus());
            String createdLocation = response.getStatus() == 201 ? response.getHeader("Location") : null;
            if (createdLocation != null) detail.put("createdLocation", createdLocation);
            Actor actor = actor(request);
            if (actor.name() != null) {
                detail.put("principal", actor.name());
            }
            repository.append(
                    projectId,
                    createdLocation == null ? lastUuid(path) : lastUuid(createdLocation),
                    correlationId(response),
                    actor.type(),
                    "HTTP_" + request.getMethod(),
                    result(response.getStatus()),
                    detail);
        }
        catch (RuntimeException exception) {
            LOGGER.error("Audit event could not be appended", exception);
        }
    }

    private Actor actor(HttpServletRequest request) {
        Object principal = request.getAttribute(AuditActorInterceptor.PRINCIPAL_ATTRIBUTE);
        return principal instanceof String name && !name.isBlank()
                ? new Actor("KULLANICI", name)
                : new Actor("SISTEM", null);
    }

    private String correlationId(HttpServletResponse response) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId == null ? response.getHeader(CorrelationIdFilter.HEADER) : correlationId;
    }

    private UUID projectUuid(String path) {
        Matcher matcher = PROJECT_PATH.matcher(path);
        return matcher.matches() ? UUID.fromString(matcher.group(1)) : null;
    }

    private UUID lastUuid(String path) {
        Matcher matcher = UUID_PATH_SEGMENT.matcher(path);
        UUID last = null;
        while (matcher.find()) {
            last = UUID.fromString(matcher.group(1));
        }
        return last;
    }

    private String result(int status) {
        if (status < 400) {
            return "BASARILI";
        }
        return status == 401 || status == 403 ? "RED" : "BASARISIZ";
    }

    private record Actor(String type, String name) {
    }
}

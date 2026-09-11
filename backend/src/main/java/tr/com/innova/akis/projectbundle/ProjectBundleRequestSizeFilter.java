package tr.com.innova.akis.projectbundle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.web.CorrelationIdFilter;

/** Rejects oversized bundle bodies while they are streaming, before JSON materialization. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class ProjectBundleRequestSizeFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 10L * 1024L * 1024L;
    private static final String VALIDATE_PATH = "/api/v1/project-bundles/validate";
    private static final String IMPORT_PATH = "/api/v1/project-bundles/import";

    private final ObjectMapper objectMapper;

    public ProjectBundleRequestSizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"POST".equals(request.getMethod())
                || (!VALIDATE_PATH.equals(path) && !IMPORT_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            reject(response);
            return;
        }
        try {
            filterChain.doFilter(new LimitedRequest(request), response);
        }
        catch (IOException exception) {
            if (causedByLimit(exception)) {
                reject(response);
                return;
            }
            throw exception;
        }
        catch (ServletException | RuntimeException exception) {
            if (causedByLimit(exception)) {
                reject(response);
                return;
            }
            throw exception;
        }
    }

    private boolean causedByLimit(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        Map<String, Object> problem = correlationId == null
                ? Map.of(
                        "type", "urn:akis:problem:bundle-too-large",
                        "title", "Payload Too Large",
                        "status", HttpStatus.PAYLOAD_TOO_LARGE.value(),
                        "detail", "Project bundle cannot exceed 10 MB.",
                        "code", "BUNDLE_TOO_LARGE")
                : Map.of(
                        "type", "urn:akis:problem:bundle-too-large",
                        "title", "Payload Too Large",
                        "status", HttpStatus.PAYLOAD_TOO_LARGE.value(),
                        "detail", "Project bundle cannot exceed 10 MB.",
                        "code", "BUNDLE_TOO_LARGE",
                        "correlationId", correlationId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream());
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long count;

        private LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        private void account(int bytes) throws PayloadTooLargeException {
            count += bytes;
            if (count > MAX_BYTES) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }

    private static final class PayloadTooLargeException extends IOException {
    }
}

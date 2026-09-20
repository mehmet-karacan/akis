package tr.com.innova.akis.web;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RequestAuditFilterTest {
    @Test void exceptionCannotBeRecordedAsSuccessWhenResponseStillHasDefaultStatus() throws Exception {
        var request = new MockHttpServletRequest("PATCH", "/api/v1/projects/" + UUID.randomUUID() + "/models/" + UUID.randomUUID());
        request.setAttribute(AuditActorInterceptor.PRINCIPAL_ATTRIBUTE, "editor");
        var repository = new AuditRepository(null) {
            @Override Optional<Long> findProjectId(UUID id) { return Optional.of(1L); }
            @Override void append(Long p, UUID id, String correlation, String actor, String action, String result, JsonNode detail) {
                assertEquals("BASARISIZ", result);
                assertEquals(500, detail.path("status").asInt());
            }
        };
        var filter = new RequestAuditFilter(repository, new ObjectMapper());
        var failure = new jakarta.servlet.ServletException("failed before response rendering");
        assertSame(failure, assertThrows(jakarta.servlet.ServletException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { throw failure; })));
    }

    @Test void nestedCreationUsesCreatedObjectAndNeverCapturesCredentialsOrPayload() throws Exception {
        UUID project = UUID.randomUUID(), model = UUID.randomUUID(), object = UUID.randomUUID();
        var request = new MockHttpServletRequest("POST", "/api/v1/projects/" + project + "/models/" + model + "/data-objects");
        request.addHeader("Authorization", "Bearer synthetic-secret");
        request.addHeader("X-Principal", "untrusted-name");
        request.setContent("{\"password\":\"synthetic-secret\"}".getBytes());
        request.setAttribute(AuditActorInterceptor.PRINCIPAL_ATTRIBUTE, "trusted-editor");
        var repository = new AuditRepository(null) {
            @Override Optional<Long> findProjectId(UUID id) { assertEquals(project, id); return Optional.of(1L); }
            @Override void append(Long p, UUID id, String correlation, String actor, String action, String result, JsonNode detail) {
                assertEquals(object, id); assertEquals("trusted-editor", detail.path("principal").asText());
                assertFalse(detail.toString().contains("synthetic-secret"));
                assertFalse(detail.toString().contains("untrusted-name"));
                assertFalse(detail.has("body"));
            }
        };
        var response = new MockHttpServletResponse();
        new RequestAuditFilter(repository, new ObjectMapper()).doFilter(request, response, (req, res) -> {
            response.setStatus(201); response.setHeader("Location", request.getRequestURI() + "/" + object);
        });
    }

    @Test void failuresAndRedirectsCannotBecomeSuccessfulRecordEdits() throws Exception {
        for (int status : new int[]{302, 400, 401, 403, 409, 500}) {
            var repository = new AuditRepository(null) {
                @Override Optional<Long> findProjectId(UUID id) { return Optional.of(1L); }
                @Override void append(Long p, UUID id, String correlation, String actor, String action, String result, JsonNode detail) {
                    assertEquals(status == 401 || status == 403 ? "RED" : "BASARISIZ", result);
                }
            };
            var request = new MockHttpServletRequest("POST", "/api/v2/projects/" + UUID.randomUUID() + "/connections");
            var response = new MockHttpServletResponse();
            new RequestAuditFilter(repository, new ObjectMapper()).doFilter(request, response, (req, res) -> response.setStatus(status));
        }
    }
    @Test void attributesCreatedRecordUsingLocationAndTrustedActor() throws Exception {
        UUID project = UUID.randomUUID(), record = UUID.randomUUID();
        var repository = new AuditRepository(null) {
            @Override Optional<Long> findProjectId(UUID id) { assertEquals(project, id); return Optional.of(1L); }
            @Override void append(Long projectId, UUID objectId, String correlation, String actor, String action, String result, JsonNode detail) {
                assertEquals(record, objectId);
                assertEquals("editor", detail.get("principal").stringValue());
                assertEquals("BASARILI", result);
            }
        };
        var request = new MockHttpServletRequest("POST", "/api/v1/projects/" + project + "/connections");
        request.setAttribute(AuditActorInterceptor.PRINCIPAL_ATTRIBUTE, "editor");
        var response = new MockHttpServletResponse();
        new RequestAuditFilter(repository, new ObjectMapper()).doFilter(request, response, (req, res) -> {
            response.setStatus(201);
            response.setHeader("Location", request.getRequestURI() + "/" + record);
        });
    }
}

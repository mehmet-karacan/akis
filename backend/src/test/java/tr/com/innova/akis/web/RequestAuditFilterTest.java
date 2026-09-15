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

package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ProjectBundleRequestSizeFilterTest {

    @Test
    void rejectsDeclaredOversizedBundleBeforeController() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/project-bundles/import");
        request.setContent(new byte[(int) ProjectBundleRequestSizeFilter.MAX_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();

        new ProjectBundleRequestSizeFilter(new ObjectMapper()).doFilter(
                request, response, (nextRequest, nextResponse) -> called.set(true));

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("BUNDLE_TOO_LARGE"));
        assertTrue(!called.get());
    }
}

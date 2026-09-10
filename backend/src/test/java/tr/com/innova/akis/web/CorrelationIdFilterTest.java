package tr.com.innova.akis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

    @Test
    void preservesSafeCallerCorrelationId() {
        assertEquals("client-request_42", CorrelationIdFilter.normalize("client-request_42"));
    }

    @Test
    void replacesUnsafeOrOversizedCorrelationId() {
        String unsafe = "line-break\nvalue";
        assertNotEquals(unsafe, CorrelationIdFilter.normalize(unsafe));
        assertEquals(36, CorrelationIdFilter.normalize("x".repeat(101)).length());
    }
}

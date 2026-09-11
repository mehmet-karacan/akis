package tr.com.innova.akis.projectbundle;

import java.net.URI;

import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tr.com.innova.akis.web.CorrelationIdFilter;

@RestControllerAdvice
final class ProjectBundleExceptionHandler {

    @ExceptionHandler(ProjectBundleException.class)
    ProblemDetail handle(ProjectBundleException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                exception.status(), exception.getMessage());
        detail.setTitle(exception.status().getReasonPhrase());
        detail.setType(URI.create("urn:akis:problem:"
                + exception.code().toLowerCase().replace('_', '-')));
        detail.setProperty("code", exception.code());
        if (exception.report() != null) {
            detail.setProperty("validation", exception.report());
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            detail.setProperty("correlationId", correlationId);
        }
        return detail;
    }
}

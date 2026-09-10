package tr.com.innova.akis.metadata;

import java.net.URI;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.MDC;

import tr.com.innova.akis.web.CorrelationIdFilter;

@RestControllerAdvice
final class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail detail = problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "REQUEST_VALIDATION_FAILED",
                "İstek alanları doğrulanamadı.");
        detail.setProperty("violations", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "geçersiz" : error.getDefaultMessage()))
                .toList());
        return detail;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "METADATA_CONFLICT",
                "Aynı kodlu kayıt zaten var veya metadata ilişkisi geçersiz.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleUnreadableRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "İstek gövdesi veya parametre değeri okunamadı.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(URI.create("urn:akis:problem:" + code.toLowerCase().replace('_', '-')));
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            detail.setProperty("correlationId", correlationId);
        }
        return detail;
    }
}

package tr.com.innova.akis.export;

import org.springframework.http.HttpStatus;

import tr.com.innova.akis.metadata.ApiException;

/**
 * Export-specific errors mapped to RFC 9457 problem details.
 */
public final class ExportException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ExportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ExportException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    ApiException toApiException() {
        return new ApiException(status, code, getMessage());
    }
}

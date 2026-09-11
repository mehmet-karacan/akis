package tr.com.innova.akis.projectbundle;

import org.springframework.http.HttpStatus;

import tr.com.innova.akis.projectbundle.ProjectBundleModels.ValidationReport;

final class ProjectBundleException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final ValidationReport report;

    ProjectBundleException(
            HttpStatus status, String code, String message, ValidationReport report) {
        super(message);
        this.status = status;
        this.code = code;
        this.report = report;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    ValidationReport report() {
        return report;
    }
}

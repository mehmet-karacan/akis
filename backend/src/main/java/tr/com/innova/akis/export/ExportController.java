package tr.com.innova.akis.export;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.export.ExportModels.ExportJobView;
import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportService.ExportDownload;
import tr.com.innova.akis.security.AuthorizationService;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/exports")
public class ExportController {

    private final ExportService service;
    private final AuthorizationService authorization;

    public ExportController(ExportService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ResponseEntity<ExportJobView> create(
        @PathVariable UUID projectUuid,
            @RequestBody ExportRequest request) {
        authorization.requireProjectPermission(projectUuid,
                ExportProviderRegistry.requiredProjectPermission(request.providerId(), request.resourceId()));
        ExportJobView view = service.createJob(projectUuid, request);
        return ResponseEntity
                .created(URI.create("/api/v1/projects/" + projectUuid + "/exports/" + view.uuid()))
                .body(view);
    }

    @GetMapping("/{exportUuid}")
    ResponseEntity<ExportJobView> status(
            @PathVariable UUID projectUuid,
            @PathVariable UUID exportUuid) {
        return ResponseEntity.ok(service.status(projectUuid, exportUuid));
    }

    @GetMapping("/{exportUuid}/download")
    ResponseEntity<InputStreamResource> download(
            @PathVariable UUID projectUuid,
            @PathVariable UUID exportUuid) throws IOException {
        ExportDownload download = service.download(projectUuid, exportUuid);
        InputStream inputStream = Files.newInputStream(download.file());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .lastModified(Instant.now().toEpochMilli())
                .body(new InputStreamResource(inputStream));
    }

    @DeleteMapping("/{exportUuid}")
    ResponseEntity<Void> cancel(
            @PathVariable UUID projectUuid,
            @PathVariable UUID exportUuid) {
        service.cancel(projectUuid, exportUuid);
        return ResponseEntity.noContent().build();
    }
}

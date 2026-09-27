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

import static tr.com.innova.akis.security.PermissionCodes.IDENTITY_USER_PROVISION;
import static tr.com.innova.akis.security.PermissionCodes.SCHEMA_METADATA_READ;

import tr.com.innova.akis.export.ExportModels.ExportJobView;
import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportService.ExportDownload;
import tr.com.innova.akis.security.AuthorizationService;


/** System-scoped export adapter; it never assigns a fake project owner. */
@RestController
@RequestMapping("/api/v1/exports")
class GlobalExportController {

    private final ExportService service;
    private final AuthorizationService authorization;

    GlobalExportController(ExportService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ResponseEntity<ExportJobView> create(@RequestBody ExportRequest request) {
        requirePermission(request);
        ExportJobView view = service.createGlobalJob(request);
        return ResponseEntity.created(URI.create("/api/v1/exports/" + view.uuid())).body(view);
    }

    @GetMapping("/{exportUuid}")
    ResponseEntity<ExportJobView> status(@PathVariable UUID exportUuid) {
        service.requireGlobalPermission(exportUuid);
        return ResponseEntity.ok(service.globalStatus(exportUuid));
    }

    @GetMapping("/{exportUuid}/download")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID exportUuid) throws IOException {
        service.requireGlobalPermission(exportUuid);
        ExportDownload download = service.globalDownload(exportUuid);
        InputStream inputStream = Files.newInputStream(download.file());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .lastModified(Instant.now().toEpochMilli())
                .body(new InputStreamResource(inputStream));
    }

    @DeleteMapping("/{exportUuid}")
    ResponseEntity<Void> cancel(@PathVariable UUID exportUuid) {
        service.requireGlobalPermission(exportUuid);
        service.globalCancel(exportUuid);
        return ResponseEntity.noContent().build();
    }

    private void requirePermission(ExportRequest request) {
        if (ExportProviderRegistry.DATASET_IDENTITY.equals(request.providerId())
                && ExportProviderRegistry.RESOURCE_USERS.equals(request.resourceId())) {
            authorization.requireSystemPermission(IDENTITY_USER_PROVISION);
            return;
        }
        if (ExportProviderRegistry.DATASET_SCHEMA_METADATA.equals(request.providerId())
                && ExportProviderRegistry.RESOURCE_SCHEMA_METADATA.equals(request.resourceId())) {
            authorization.requireSystemPermission(SCHEMA_METADATA_READ);
            return;
        }
        throw new ExportException(HttpStatus.UNPROCESSABLE_ENTITY,
                "EXPORT_GLOBAL_PROVIDER_NOT_ALLOWED", "Global export kaynağı desteklenmiyor.");
    }
}

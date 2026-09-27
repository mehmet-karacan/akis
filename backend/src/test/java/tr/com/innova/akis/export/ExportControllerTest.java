package tr.com.innova.akis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tr.com.innova.akis.security.PermissionCodes.RUN_READ;
import static tr.com.innova.akis.security.PermissionCodes.CATALOG_READ;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tr.com.innova.akis.export.ExportModels.ExportJobView;
import tr.com.innova.akis.export.ExportModels.ExportRequest;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.ExportService.ExportDownload;
import tr.com.innova.akis.security.ApplicationUserPrincipal;
import tr.com.innova.akis.security.AuthorizationService;

class ExportControllerTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID EXPORT_UUID = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new ApplicationUserPrincipal(
                                1, UUID.randomUUID(), "exporter", "Exporter",
                                "{argon2}test", true),
                        null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createUsesRunReadPermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        ExportController controller = controller(authorization);

        ResponseEntity<ExportJobView> response = controller.create(PROJECT_UUID,
                new ExportRequest("runs", "run-history", ExportScope.ALL,
                        List.of(), List.of(), false, "tr-TR", "UTC"));

        assertEquals(RUN_READ, authorization.permission);
        assertEquals(PROJECT_UUID, authorization.projectUuid);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(EXPORT_UUID, response.getBody().uuid());
    }

    @Test
    void statusAndDownloadDelegatePermissionCheckToService() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        ExportController controller = controller(authorization);

        controller.status(PROJECT_UUID, EXPORT_UUID);
        assertEquals(null, authorization.permission);

        try {
            controller.download(PROJECT_UUID, EXPORT_UUID);
        } catch (Exception ignored) {
            // stub service download would fail on missing file; permission already checked
        }
        assertEquals(null, authorization.permission);
    }

    @Test
    void createUsesSourceScreenPermissionForCatalogExport() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        ExportController controller = controller(authorization);

        controller.create(PROJECT_UUID,
                new ExportRequest("models", "data-objects", ExportScope.ALL,
                        List.of(), List.of(), false, "tr-TR", "UTC"));

        assertEquals(CATALOG_READ, authorization.permission);
    }

    @Test
    void cancelDelegatesPermissionCheckToService() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        ExportController controller = controller(authorization);

        ResponseEntity<Void> response = controller.cancel(PROJECT_UUID, EXPORT_UUID);

        assertEquals(null, authorization.permission);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    private ExportController controller(CapturingAuthorization authorization) {
        return new ExportController(
                new StubExportService(), authorization);
    }

    private static final class StubExportService extends ExportService {

        StubExportService() {
            super(null, null, null, null, null);
        }

        @Override
        public ExportJobView createJob(UUID projectUuid, ExportRequest request) {
            return view();
        }

        @Override
        public ExportJobView status(UUID projectUuid, UUID exportUuid) {
            return view();
        }

        @Override
        public ExportDownload download(UUID projectUuid, UUID exportUuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(UUID projectUuid, UUID exportUuid) {
        }

        private ExportJobView view() {
            return new ExportJobView(
                    EXPORT_UUID, "runs", "run-history", ExportScope.ALL,
                    ExportModels.ExportStatus.QUEUED, 0, 0, 0,
                    null, null,
                    OffsetDateTime.now(), OffsetDateTime.now(),
                    null, null);
        }
    }

    private static final class CapturingAuthorization extends AuthorizationService {

        UUID projectUuid;
        String permission;

        CapturingAuthorization() {
            super(new tr.com.innova.akis.security.AuthorizationRepository(null));
        }

        @Override
        public void requireProjectPermission(UUID projectUuid, String permissionCode) {
            this.projectUuid = projectUuid;
            this.permission = permissionCode;
        }
    }
}

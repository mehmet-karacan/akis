package tr.com.innova.akis.projectbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_CREATE;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import tr.com.innova.akis.projectbundle.ProjectBundleModels.BundleCounts;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ConflictPolicy;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ImportResult;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.TopologyEntry;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ValidationReport;
import tr.com.innova.akis.security.AuthorizationService;

class ProjectBundleControllerTest {

    @Test
    void exposesTheV1EndpointContract() throws Exception {
        assertEquals("/api/v1", ProjectBundleController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        Method export = ProjectBundleController.class.getDeclaredMethod("export", UUID.class);
        Method validate = ProjectBundleController.class.getDeclaredMethod(
                "validate", ProjectBundle.class);
        Method importBundle = ProjectBundleController.class.getDeclaredMethod(
                "importBundle", ProjectBundle.class, ConflictPolicy.class, boolean.class);

        assertEquals("/projects/{projectUuid}/bundle/export",
                export.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/project-bundles/validate",
                validate.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/project-bundles/import",
                importBundle.getAnnotation(PostMapping.class).value()[0]);
    }

    @Test
    void exportRequiresProjectReadAndUsesAnAttachmentFilename() {
        UUID projectUuid = UUID.randomUUID();
        StubAuthorization authorization = new StubAuthorization();
        StubService service = new StubService();
        ProjectBundleController controller = new ProjectBundleController(service, authorization);

        var response = controller.export(projectUuid);

        assertEquals(projectUuid, authorization.projectUuid);
        assertEquals(PROJECT_READ, authorization.permission);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getHeaders().getContentDisposition().getFilename().startsWith("DEMO-bundle-v1"));
    }

    @Test
    void validateAndImportRequireProjectCreateSystemPermission() {
        StubAuthorization authorization = new StubAuthorization();
        StubService service = new StubService();
        ProjectBundleController controller = new ProjectBundleController(service, authorization);
        ProjectBundle bundle = service.bundle();

        assertTrue(controller.validate(bundle).valid());
        assertEquals(PROJECT_CREATE, authorization.permission);

        var response = controller.importBundle(bundle, ConflictPolicy.RENAME, false);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(PROJECT_CREATE, authorization.permission);
        assertEquals("/api/v1/projects/" + service.importedProjectUuid,
                response.getHeaders().getLocation().toString());
    }

    private static final class StubAuthorization extends AuthorizationService {

        private UUID projectUuid;
        private String permission;

        private StubAuthorization() {
            super(null, "fail-closed");
        }

        @Override
        public void requireProjectPermission(UUID projectUuid, String permissionCode) {
            this.projectUuid = projectUuid;
            this.permission = permissionCode;
        }

        @Override
        public void requireSystemPermission(String permissionCode) {
            this.permission = permissionCode;
        }
    }

    private static final class StubService extends ProjectBundleService {

        private final UUID importedProjectUuid = UUID.randomUUID();

        private StubService() {
            super(null, null, null, null);
        }

        @Override
        public ProjectBundle exportBundle(UUID projectUuid) {
            return bundle();
        }

        @Override
        public ValidationReport validate(ProjectBundle bundle) {
            return new ValidationReport(true, new BundleCounts(0, 0, 0, 0), List.of());
        }

        @Override
        public ImportResult importBundle(
                ProjectBundle bundle, ConflictPolicy conflictPolicy, boolean dryRun) {
            return new ImportResult(
                    true, false, "DEMO_IMPORT_1", importedProjectUuid,
                    new BundleCounts(0, 0, 0, 0));
        }

        private ProjectBundle bundle() {
            return new ProjectBundle(
                    ProjectBundleModels.FORMAT, 1, 1, "0".repeat(64),
                    OffsetDateTime.now(), new ProjectEntry("DEMO", "AKTIF", "Demo", null),
                    List.of(), List.of(), new TopologyEntry(true));
        }
    }
}

package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_CREATE;
import static tr.com.innova.akis.security.PermissionCodes.DEFINITION_WRITE;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.MetadataModels.DefinitionRow;
import tr.com.innova.akis.metadata.MetadataModels.FolderRow;
import tr.com.innova.akis.metadata.MetadataModels.ProjectRow;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;

class MetadataControllerTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID FOLDER_UUID = UUID.randomUUID();
    private static final UUID DEFINITION_UUID = UUID.randomUUID();

    @Test
    void createsProjectForStableAuthenticatedIdentity() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        StubMetadataService service = new StubMetadataService();
        MetadataController controller = new MetadataController(service, authorization);

        controller.createProject(new MetadataController.CreateProjectRequest(
                "AKIS", "Akış", "Metadata project"));

        assertEquals(PROJECT_CREATE, authorization.permission);
        assertEquals("https://identity.example/realms/akis", service.actorProvider);
        assertEquals("creator-42", service.actorSubject);
    }

    @Test
    void protectsFolderMoveWithDefinitionWritePermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        MetadataController controller = new MetadataController(new StubMetadataService(), authorization);

        controller.moveFolder(
                PROJECT_UUID, FOLDER_UUID,
                new MetadataController.MoveFolderRequest(null, 1L));

        assertEquals(PROJECT_UUID, authorization.projectUuid);
        assertEquals(DEFINITION_WRITE, authorization.permission);
    }

    @Test
    void protectsDefinitionMoveWithDefinitionWritePermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        MetadataController controller = new MetadataController(new StubMetadataService(), authorization);

        controller.moveDefinition(
                PROJECT_UUID, DEFINITION_UUID,
                new MetadataController.MoveDefinitionRequest(FOLDER_UUID, 1L));

        assertEquals(PROJECT_UUID, authorization.projectUuid);
        assertEquals(DEFINITION_WRITE, authorization.permission);
    }

    private static final class CapturingAuthorization extends AuthorizationService {

        private UUID projectUuid;
        private String permission;

        private CapturingAuthorization() {
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

        @Override
        public PrincipalIdentity currentPrincipalIdentity() {
            return new PrincipalIdentity(
                    "https://identity.example/realms/akis", "creator-42", "creator");
        }
    }

    private static final class StubMetadataService extends MetadataService {

        private String actorProvider;
        private String actorSubject;

        private StubMetadataService() {
            super(null, new ObjectMapper(), new DefinitionContentValidator(), new SecretValueSanitizer());
        }

        @Override
        ProjectRow createProject(
                String code,
                String name,
                String description,
                String actorProvider,
                String actorSubject) {
            this.actorProvider = actorProvider;
            this.actorSubject = actorSubject;
            return new ProjectRow(
                    1, PROJECT_UUID, code, "AKTIF", name, description, 1,
                    OffsetDateTime.parse("2026-09-12T00:00:00Z"));
        }

        @Override
        FolderRow moveFolder(UUID projectUuid, UUID folderUuid, UUID parentUuid, Long expectedVersion) {
            return new FolderRow(
                    1, 1, folderUuid, parentUuid, "FOLDER",
                    "AKTIF", "Folder", null, expectedVersion + 1);
        }

        @Override
        DefinitionRow moveDefinition(
                UUID projectUuid,
                UUID definitionUuid,
                UUID folderUuid,
                Long expectedVersion) {
            return new DefinitionRow(
                    1, 1L, definitionUuid, folderUuid, DefinitionType.PROCEDURE,
                    "PROCEDURE", "TASLAK", "Procedure", null, expectedVersion + 1);
        }
    }
}

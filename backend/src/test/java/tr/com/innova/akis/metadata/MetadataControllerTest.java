package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_WRITE;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.MetadataModels.DefinitionRow;
import tr.com.innova.akis.metadata.MetadataModels.FolderRow;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.security.AuthorizationService;

class MetadataControllerTest {

    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID FOLDER_UUID = UUID.randomUUID();
    private static final UUID DEFINITION_UUID = UUID.randomUUID();

    @Test
    void protectsFolderMoveWithProjectWritePermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        MetadataController controller = new MetadataController(new StubMetadataService(), authorization);

        controller.moveFolder(
                PROJECT_UUID, FOLDER_UUID,
                new MetadataController.MoveFolderRequest(null, 1L));

        assertEquals(PROJECT_UUID, authorization.projectUuid);
        assertEquals(PROJECT_WRITE, authorization.permission);
    }

    @Test
    void protectsDefinitionMoveWithProjectWritePermission() {
        CapturingAuthorization authorization = new CapturingAuthorization();
        MetadataController controller = new MetadataController(new StubMetadataService(), authorization);

        controller.moveDefinition(
                PROJECT_UUID, DEFINITION_UUID,
                new MetadataController.MoveDefinitionRequest(FOLDER_UUID, 1L));

        assertEquals(PROJECT_UUID, authorization.projectUuid);
        assertEquals(PROJECT_WRITE, authorization.permission);
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
    }

    private static final class StubMetadataService extends MetadataService {

        private StubMetadataService() {
            super(null, new ObjectMapper(), new DefinitionContentValidator(), new SecretValueSanitizer());
        }

        @Override
        FolderRow moveFolder(UUID projectUuid, UUID folderUuid, UUID parentUuid, Long expectedVersion) {
            return new FolderRow(
                    1, 1, folderUuid, parentUuid, "FOLDER", "GELISTIRME",
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

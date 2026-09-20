package tr.com.innova.akis.catalog;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.catalog.CatalogModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogFolderMoveTest {
    final CatalogRepository repository = mock(CatalogRepository.class);
    final CatalogService service = new CatalogService(repository);
    final UUID project = UUID.randomUUID(), model = UUID.randomUUID(), object = UUID.randomUUID(), folder = UUID.randomUUID();
    ModelRow modelRow(String status) {
        return new ModelRow(2, model, 3, UUID.randomUUID(), "ORACLE", null, "STANDARD", null, null,
                "MODEL", status, "Model", null, 1, null, 1);
    }
    DataObjectRow objectRow(long modelId, String status, Long folderId, UUID folderUuid, long version) {
        return new DataObjectRow(4, object, modelId, model, folderId, folderUuid, "ITEMS", "APP.ITEMS", "VIEW", status, null, null, "Items", version);
    }
    @BeforeEach void setup() {
        when(repository.findProject(project)).thenReturn(Optional.of(new ProjectRef(1)));
        when(repository.findModel(1, model)).thenReturn(Optional.of(modelRow("AKTIF")));
        when(repository.findDataObject(1, object)).thenReturn(Optional.of(objectRow(2, "AKTIF", null, null, 7)));
        when(repository.findSubmodel(1, folder)).thenReturn(Optional.of(new SubmodelRow(5, folder, 2, model, null, "FOLDER", "Folder", 1)));
    }
    @Test void movePreservesObjectIdentityAndPassesExactVersion() {
        var moved = objectRow(2, "AKTIF", 5L, folder, 8);
        when(repository.moveDataObject(1, 2, object, 5L, 7)).thenReturn(Optional.of(moved));
        assertEquals(moved, service.moveDataObject(project, model, object, folder, 7));
        verify(repository).moveDataObject(1, 2, object, 5L, 7);
        verify(repository, never()).createDataObject(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }
    @Test void movesToRootWithoutLookingUpNullFolder() {
        when(repository.moveDataObject(1, 2, object, null, 7)).thenReturn(Optional.of(objectRow(2, "AKTIF", null, null, 8)));
        assertNull(service.moveDataObject(project, model, object, null, 7).submodelUuid());
        verify(repository, never()).findSubmodel(anyLong(), any());
    }
    @Test void rejectsForeignModelObjectBeforeWriting() {
        when(repository.findDataObject(1, object)).thenReturn(Optional.of(objectRow(99, "AKTIF", null, null, 7)));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.moveDataObject(project, model, object, folder, 7)).status());
        verify(repository, never()).moveDataObject(anyLong(), anyLong(), any(), any(), anyLong());
    }
    @Test void rejectsForeignOrMissingFolderBeforeWriting() {
        when(repository.findSubmodel(1, folder)).thenReturn(Optional.of(new SubmodelRow(5, folder, 99, UUID.randomUUID(), null, "F", "F", 1)));
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, assertThrows(ApiException.class, () -> service.moveDataObject(project, model, object, folder, 7)).status());
        when(repository.findSubmodel(1, folder)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.moveDataObject(project, model, object, folder, 7)).status());
        verify(repository, never()).moveDataObject(anyLong(), anyLong(), any(), any(), anyLong());
    }
    @Test void rejectsArchivedDataAndBadVersion() {
        when(repository.findDataObject(1, object)).thenReturn(Optional.of(objectRow(2, "PASIF", null, null, 7)));
        assertThrows(ApiException.class, () -> service.moveDataObject(project, model, object, folder, 7));
        when(repository.findDataObject(1, object)).thenReturn(Optional.of(objectRow(2, "AKTIF", null, null, 7)));
        when(repository.findModel(1, model)).thenReturn(Optional.of(modelRow("ARSIV")));
        assertThrows(ApiException.class, () -> service.moveDataObject(project, model, object, folder, 7));
        assertThrows(ApiException.class, () -> service.moveDataObject(project, model, object, folder, 0));
        verify(repository, never()).moveDataObject(anyLong(), anyLong(), any(), any(), anyLong());
    }
    @Test void concurrentUpdateBecomesConflictWithoutRetry() {
        when(repository.moveDataObject(1, 2, object, 5L, 7)).thenReturn(Optional.empty());
        var error = assertThrows(ApiException.class, () -> service.moveDataObject(project, model, object, folder, 7));
        assertEquals("VERSION_CONFLICT", error.code());
        assertEquals(HttpStatus.CONFLICT, error.status());
        verify(repository, times(1)).moveDataObject(1, 2, object, 5L, 7);
    }
    @Test void controllerRequiresCatalogWriteBeforeService() {
        var access = mock(AuthorizationService.class);
        var guarded = mock(CatalogService.class);
        var controller = new CatalogController(guarded, access);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Denied"))
                .when(access).requireProjectPermission(project, "KATALOG_KESFET");
        assertThrows(ApiException.class, () -> controller.moveDataObject(project, model, object,
                new CatalogController.MoveDataObjectRequest(folder, 7)));
        verifyNoInteractions(guarded);
    }

    @Test void creationResponsesIdentifyTheNewRecordForAuditAttribution() {
        var guarded = mock(CatalogService.class);
        var controller = new CatalogController(guarded, mock(AuthorizationService.class));
        var created = objectRow(2, "AKTIF", null, null, 1);
        when(guarded.createDataObject(project, model, null, "ITEMS", "APP.ITEMS", "VIEW", null, null, "Items")).thenReturn(created);
        var response = controller.createDataObject(project, model,
                new CatalogController.CreateDataObjectRequest(null, "ITEMS", "APP.ITEMS", "VIEW", null, null, "Items"));
        assertEquals("/api/v1/projects/" + project + "/models/" + model + "/data-objects/" + object, response.getHeaders().getLocation().toString());
        var row = new SubmodelRow(5, folder, 2, model, null, "FOLDER", "Folder", 1);
        when(guarded.createSubmodel(project, model, null, "FOLDER", "Folder")).thenReturn(row);
        var folderResponse = controller.createSubmodel(project, model, new CatalogController.CreateSubmodelRequest(null, "FOLDER", "Folder"));
        assertEquals("/api/v1/projects/" + project + "/models/" + model + "/submodels/" + folder, folderResponse.getHeaders().getLocation().toString());
    }
}

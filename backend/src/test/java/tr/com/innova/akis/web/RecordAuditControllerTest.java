package tr.com.innova.akis.web;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordAuditControllerTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String queryFor(String kind) {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        var query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        var auth = mock(AuthorizationService.class);
        UUID project = UUID.randomUUID();
        new RecordAuditController(jdbc, auth).list(project, kind);
        var order = inOrder(auth, jdbc);
        order.verify(auth).requireProjectPermission(eq(project), anyString());
        order.verify(jdbc).sql(anyString());
        verify(statement).param("project", project);
        verify(statement).param("kind", kind);
        var sql = ArgumentCaptor.forClass(String.class); verify(jdbc).sql(sql.capture());
        return sql.getValue();
    }
    @Test void nestedDataStorePathsUseTheirOwningModelAndRecognizeFolderMoves() {
        String sql = queryFor("data-objects");
        assertTrue(sql.contains("join akis.model m on m.id = r.model_id and m.proje_id = r.proje_id"));
        assertTrue(sql.contains("'/models/' || m.uuid || '/data-objects'"));
        assertTrue(sql.contains("r.uuid || '/folder'"));
        assertTrue(sql.contains("a.dis_nesne_uuid = r.uuid"));
        assertTrue(sql.contains("a.sonuc = 'BASARILI'"));
    }
    @Test void topLevelRecordsAcceptVersionTwoEventsWithoutInventingModelColumns() {
        for (String kind : List.of("connections", "logical-schemas", "physical-schemas", "environments", "models", "definitions", "folders")) {
            String sql = queryFor(kind);
            assertFalse(sql.contains("r.model_id"), kind);
            assertTrue(sql.contains("regexp_replace(a.ayrinti->>'path', '^/api/v2/', '/api/v1/')"), kind);
            assertTrue(sql.contains("when updated.event_id is not null then updated.principal else editor.gorunen_ad"), kind);
            assertFalse(sql.contains("coalesce(updated.principal, editor.gorunen_ad)"), kind);
        }
    }
    @Test void rejectsUnknownKindsAndUnauthorizedReadsBeforeDatabaseAccess() {
        var jdbc = mock(JdbcClient.class); var auth = mock(AuthorizationService.class);
        var controller = new RecordAuditController(jdbc, auth); UUID project = UUID.randomUUID();
        assertThrows(ApiException.class, () -> controller.list(project, "model;delete"));
        verifyNoInteractions(jdbc, auth);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "DENIED", "Denied"))
                .when(auth).requireProjectPermission(project, "KATALOG_GORUNTULE");
        assertThrows(ApiException.class, () -> controller.list(project, "data-objects"));
        verifyNoInteractions(jdbc);
    }
}

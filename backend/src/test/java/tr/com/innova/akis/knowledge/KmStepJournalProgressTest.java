package tr.com.innova.akis.knowledge;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KmStepJournalProgressTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    private final ObjectMapper mapper = new ObjectMapper();
    private final KmStepJournal journal = new KmStepJournal(jdbc, mapper);
    private final WorkObjectStore.Owner owner = new WorkObjectStore.Owner(UUID.randomUUID(), UUID.randomUUID(), 7, "worker-7");

    @Test void persistsMetricsWithTheCurrentRunningTransferAndLeaseFenceOnly() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        journal.progress(owner, new TransferProgress(500, 8192, 6, 2, 3, 1));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        String query = sql.getValue().replaceAll("\\s+", " ");
        String set = query.substring(query.indexOf(" set "), query.indexOf(" from "));
        assertTrue(set.contains("transfer_progress=cast(:progress as jsonb)"));
        assertTrue(set.contains("affected_rows=:rows"));
        for (String protectedColumn : List.of("calistirilan_sql", "started_at", "completed_at", "state="))
            assertFalse(set.contains(protectedColumn), protectedColumn);
        for (String fence : List.of("j.state='RUNNING'", "j.operation='TRANSFER_JDBC'", "p.uuid=:project", "c.uuid=:run",
                "j.generation=:generation", "j.worker_reference=:worker", "d.nesil_no=:generation",
                "d.isleyici_referansi=:worker", "d.kiralama_bitis_zamani>clock_timestamp()", "d.durum='CALISIYOR'"))
            assertTrue(query.contains(fence), fence);
        verify(statement).param("project", owner.projectUuid());
        verify(statement).param("run", owner.runUuid());
        verify(statement).param("generation", 7L);
        verify(statement).param("worker", "worker-7");
        verify(statement).param("rows", 500L);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statement).param(eq("progress"), json.capture());
        assertEquals(new TransferProgress(500, 8192, 6, 2, 3, 1), mapper.readValue(json.getValue(), TransferProgress.class));
    }

    @Test void rejectsAProgressWriteWhenTheOwnerOrRunningStepIsNoLongerCurrent() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.update()).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> journal.progress(owner, new TransferProgress(500, 8192, 6, 2, 3, 1)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void readsProgressInAllJournalViewsAndExposesItInTheRunApiJson() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.getLong("generation")).thenReturn(7L);
        when(rs.getInt("ordinal")).thenReturn(2);
        when(rs.getString("operation")).thenReturn("TRANSFER_JDBC");
        when(rs.getString("state")).thenReturn("RUNNING");
        when(rs.getObject("affected_rows", Long.class)).thenReturn(500L);
        when(rs.getObject("run_uuid", UUID.class)).thenReturn(owner.runUuid());
        when(rs.getString("calistirilan_sql")).thenReturn("[]");
        when(rs.getString("transfer_progress")).thenReturn("{\"rows\":500,\"bytes\":8192,\"elapsedSeconds\":6,\"readSeconds\":2,\"writeSeconds\":3,\"commitSeconds\":1}");
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenAnswer(call -> {
            Object row = ((RowMapper<?>) call.getArgument(0)).mapRow(rs, 0);
            var query = mock(JdbcClient.MappedQuerySpec.class);
            when(query.list()).thenReturn(List.of(row));
            when(query.stream()).thenAnswer(ignored -> List.of(row).stream());
            return query;
        });
        var rows = new ArrayList<KmStepJournal.Row>(journal.list(owner.projectUuid(), owner.runUuid()));
        journal.forEachRow(owner.projectUuid(), owner.runUuid(), rows::add);
        journal.forEachRowBatch(owner.projectUuid(), List.of(owner.runUuid()), (run, row) -> {
            assertEquals(owner.runUuid(), run);
            rows.add(row);
        });
        assertEquals(3, rows.size());
        for (var row : rows) {
            var json = mapper.valueToTree(row);
            assertEquals(500, json.path("transferProgress").path("rows").asLong());
            assertEquals(8192, json.path("transferProgress").path("bytes").asLong());
            assertEquals(6, json.path("transferProgress").path("elapsedSeconds").asDouble());
        }
        when(rs.getString("transfer_progress")).thenReturn(null);
        assertNull(journal.list(owner.projectUuid(), owner.runUuid()).getFirst().transferProgress());
    }

    @Test void legacyConstructorsKeepProgressOptional() {
        assertNull(new KmStepJournal.Row(1, 1, "LOAD", "TRANSFER_JDBC", "STAGING", "WORK_SOURCE_1", "RUNNING",
                null, null, (OffsetDateTime) null, null).transferProgress());
        assertNull(new KmStepJournal.Row(1, 1, "LOAD", "TRANSFER_JDBC", "STAGING", "WORK_SOURCE_1", "RUNNING",
                null, null, null, null, mapper.createArrayNode()).transferProgress());
    }
}

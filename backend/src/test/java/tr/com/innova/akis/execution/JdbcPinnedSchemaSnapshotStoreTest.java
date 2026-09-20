package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.UUID;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdbcPinnedSchemaSnapshotStoreTest {

    @Test
    void rejectsInvalidPlanBeforeTouchingJdbcAndDoesNotLeakCause() {
        JdbcPinnedSchemaSnapshotStore store = new JdbcPinnedSchemaSnapshotStore(
                null, new ObjectMapper());
        PilotRuntimePlan invalid = new PilotRuntimePlan(
                1, "f".repeat(64), null, "b".repeat(64),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 100,
                null, null, List.of(),
                PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                new ObjectMapper().createObjectNode());

        PinnedSchemaSnapshotException exception = assertThrows(
                PinnedSchemaSnapshotException.class, () -> store.load(invalid));

        assertEquals(
                PinnedSchemaSnapshotException.Failure.INVALID_CONTRACT,
                exception.failure());
        assertEquals(
                "Pinned schema snapshot could not be loaded safely.",
                exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void loadsEverySourceByItsExactPublicationBindingAndReturnsImmutableIndex() throws Exception {
        var fixture=new MultiSourceFixture(false);
        var snapshots=fixture.store.load(fixture.plan);
        assertEquals(List.of("S1","T","S2"),fixture.requestedBindings);
        assertEquals(java.util.Set.of("S1","S2"),snapshots.sources().keySet());
        assertEquals(fixture.plan.sources().get(1).schemaSnapshotUuid(),snapshots.sources().get("S2").schemaSnapshotUuid());
        assertEquals(snapshots.source(),snapshots.sources().get("S1"));
        assertThrows(UnsupportedOperationException.class,()->snapshots.sources().clear());
    }

    @Test
    void rejectsAdditionalSourceFromDifferentPublication() throws Exception {
        var fixture=new MultiSourceFixture(true);
        var error=assertThrows(PinnedSchemaSnapshotException.class,()->fixture.store.load(fixture.plan));
        assertEquals(PinnedSchemaSnapshotException.Failure.CROSS_PUBLICATION_BINDING,error.failure());
        assertNull(error.getCause());
    }

    private static final class MultiSourceFixture {
        private final ObjectMapper mapper=new ObjectMapper();
        private final UUID project=UUID.randomUUID(),publication=UUID.randomUUID(),connection=UUID.randomUUID();
        private final Column column=new Column("ID","NUMBER(10,0)","INTEGER",1,10,0,null,null,false,null,"ID");
        private final SchemaFingerprintInput body=new SchemaFingerprintInput("19.0.0.0.0",1,mapper.createObjectNode(),List.of(column),List.of());
        private final String hash=new SchemaFingerprint(mapper).calculate(body);
        final List<String> requestedBindings=new ArrayList<>();
        final StagedRuntimePlan plan;
        final JdbcPinnedSchemaSnapshotStore store;

        @SuppressWarnings({"unchecked","rawtypes"})
        MultiSourceFixture(boolean crossPublication) throws Exception {
            var first=binding("S1",PilotRuntimePlan.DatasetRole.SOURCE);
            var second=binding("S2",PilotRuntimePlan.DatasetRole.SOURCE);
            var target=binding("T",PilotRuntimePlan.DatasetRole.TARGET);
            plan=new StagedRuntimePlan(project,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"b".repeat(64),"c".repeat(64),
                    first,List.of(first,second),target,List.of(new PilotRuntimePlan.DirectColumnMapping("ID","ID")),List.of("S1"),null,null,null,mapper.createObjectNode());
            JdbcClient jdbc=mock(JdbcClient.class);
            when(jdbc.sql(anyString())).thenAnswer(call->{
                String sql=call.getArgument(0);
                var statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
                Map<String,Object> params=new HashMap<>();
                when(statement.param(anyString(),any())).thenAnswer(param->{params.put(param.getArgument(0),param.getArgument(1));return statement;});
                when(statement.query(any(RowMapper.class))).thenAnswer(query->{
                    List<?> rows;
                    if(sql.contains("from akis.yayin_veri_bagi")) {
                        String id=(String)params.get("datasetId"); requestedBindings.add(id);
                        ResultSet rs=mock(ResultSet.class);
                        when(rs.getObject("project_uuid",UUID.class)).thenReturn(project);
                        when(rs.getObject("publication_uuid",UUID.class)).thenReturn(crossPublication && "S2".equals(id)?UUID.randomUUID():publication);
                        when(rs.getObject("snapshot_uuid",UUID.class)).thenReturn((UUID)params.get("schemaSnapshotUuid"));
                        when(rs.getLong("snapshot_id")).thenReturn(1L);
                        when(rs.getString("parmak_izi")).thenReturn(hash);
                        when(rs.getString("motor_surumu")).thenReturn(body.engineVersion());
                        when(rs.getInt("ozellik_surumu")).thenReturn(1);
                        when(rs.getString("ozellik")).thenReturn("{}");
                        rows=List.of(((RowMapper<?>)query.getArgument(0)).mapRow(rs,0));
                    } else if(sql.contains("from akis.kolon_goruntusu")) rows=List.of(column);
                    else rows=List.of();
                    var result=mock(JdbcClient.MappedQuerySpec.class);
                    when(result.list()).thenReturn(rows);
                    return result;
                });
                return statement;
            });
            store=new JdbcPinnedSchemaSnapshotStore(jdbc,mapper);
        }
        private PilotRuntimePlan.DatasetBinding binding(String id,PilotRuntimePlan.DatasetRole role) {
            return new PilotRuntimePlan.DatasetBinding(id,role,PilotRuntimePlan.DatabaseType.ORACLE,PilotRuntimePlan.DataObjectType.TABLE,
                    UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),connection,UUID.randomUUID(),1,hash,"DATA."+id,"DATA",id);
        }
    }
}

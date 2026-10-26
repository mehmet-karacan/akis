package tr.com.innova.akis.publication;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tr.com.innova.akis.knowledge.StagedMappingDefinition;
import tr.com.innova.akis.knowledge.MappingSql;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StagedSqlPreviewTypesTest {
    @Test void predicatePreviewUsesPinnedSourceColumnCatalog() throws Exception {
        var mapper=new JsonMapper();var jdbc=mock(JdbcClient.class);var statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement);
        UUID snapshot=UUID.randomUUID(),resolvedSnapshot=UUID.randomUUID();var selectedSnapshot=new AtomicReference<UUID>();
        doAnswer(call->{selectedSnapshot.set(call.getArgument(1,UUID.class));return statement;}).when(statement).param(eq("snapshot"),any(UUID.class));
        doAnswer(call->{
            var row=mock(ResultSet.class);when(row.getString("kolon_referansi")).thenReturn(snapshot.equals(selectedSnapshot.get())?"OLD_COLUMN":"ID");when(row.getString("uretici_tipi")).thenReturn("NUMERIC(12,0)");
            when(row.getObject("hassasiyet",Integer.class)).thenReturn(12);when(row.getObject("olcek",Integer.class)).thenReturn(0);
            call.getArgument(0,RowCallbackHandler.class).processRow(row);return null;
        }).when(statement).query(any(RowCallbackHandler.class));
        var source=new StagedMappingDefinition.ObjectRef("S","SRC",UUID.randomUUID(),snapshot);
        var predicate=MappingSql.parse("SRC.ID BETWEEN 1 AND 100000",List.of(new MappingSql.Source("S","SRC",Set.of("ID"))),true);
        var definition=new StagedMappingDefinition(null,Map.of(),Map.of(),new StagedMappingDefinition.Options(1000,1000,100000,1073741824,false),List.of(source),null,List.of(),List.of(new StagedMappingDefinition.Filter("SOURCE","S",null,null,null,predicate)));
        var plan=mapper.createObjectNode();plan.putObject("staging").put("owner","WORK");
        var bindings=plan.putArray("bindings");bindings.addObject().put("nodeCode","S").put("role","KAYNAK").put("owner","TTBP").put("objectName","ITEMS").put("schemaSnapshotUuid",resolvedSnapshot.toString());
        bindings.addObject().put("nodeCode","T").put("role","HEDEF").put("owner","DEST").put("objectName","ITEMS").put("schemaSnapshotUuid",resolvedSnapshot.toString());
        var column=plan.putArray("columns").addObject();column.putObject("source").put("object","S").put("column","ID");column.putObject("target").put("object","T").put("column","ID");
        var result=StagedSqlPreview.render(mapper,jdbc,definition,plan);
        String sql="";for(var entry:result)if("TRANSFER_JDBC".equals(entry.path("step").asText()) && "SOURCE".equals(entry.path("site").asText()))sql=entry.path("sql").asText();
        assertTrue(sql.contains("BETWEEN ? AND ?"),sql);
        assertFalse(result.toString().contains("Kaynak kolon bulunamadı"));
    }
    @Test void previewIncludesAllBenchmarkTargetTypesInsteadOfDroppingColumns() throws Exception {
        var mapper=new JsonMapper();var jdbc=mock(JdbcClient.class);var statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement);
        String[] types={"NUMERIC(38,0)","NUMERIC(14,0)","REAL","DOUBLE PRECISION","CHAR(8)","VARCHAR(120)","TEXT","BYTEA","TIMESTAMP(0)","TIMESTAMP(6)","TIMESTAMPTZ(6)"};
        Integer[] precision={38,14,null,null,null,null,null,null,null,null,null};
        Integer[] scale={0,0,null,null,null,null,null,null,null,null,null};
        Long[] length={null,null,null,null,8L,120L,null,null,null,null,null};
        Integer[] time={null,null,null,null,null,null,null,null,0,6,6};
        doAnswer(call->{var callback=call.getArgument(0,RowCallbackHandler.class);
            for(int i=0;i<types.length;i++) {
                var row=mock(ResultSet.class);when(row.getString("kolon_referansi")).thenReturn("C"+i);when(row.getString("uretici_tipi")).thenReturn(types[i]);
                when(row.getObject("hassasiyet",Integer.class)).thenReturn(precision[i]);when(row.getObject("olcek",Integer.class)).thenReturn(scale[i]);
                when(row.getObject("uzunluk",Long.class)).thenReturn(length[i]);when(row.getObject("zaman_hassasiyeti",Integer.class)).thenReturn(time[i]);callback.processRow(row);
            }return null;
        }).when(statement).query(any(RowCallbackHandler.class));
        UUID snapshot=UUID.randomUUID();var source=new StagedMappingDefinition.ObjectRef("S","SRC",UUID.randomUUID(),snapshot);
        var definition=new StagedMappingDefinition(null,Map.of(),Map.of(),new StagedMappingDefinition.Options(10,10,100,1_000_000,false),List.of(source),null,List.of(),List.of());
        var plan=mapper.createObjectNode();plan.putObject("staging").put("owner","WORK");
        var bindings=plan.putArray("bindings");bindings.addObject().put("nodeCode","S").put("role","KAYNAK").put("owner","SRC").put("objectName","ITEMS").put("schemaSnapshotUuid",snapshot.toString());
        bindings.addObject().put("nodeCode","T").put("role","HEDEF").put("owner","SKY_DIT_PERF").put("objectName","ITEMS").put("schemaSnapshotUuid",snapshot.toString());
        var columns=plan.putArray("columns");for(int i=0;i<types.length;i++){var c=columns.addObject();c.putObject("source").put("object","S").put("column","C"+i);c.putObject("target").put("object","T").put("column","C"+i);}
        var result=StagedSqlPreview.render(mapper,jdbc,definition,plan);
        String ddl=result.get(0).path("sql").asText();
        for(int i=0;i<types.length;i++)assertTrue(ddl.contains("C"+i+" "+types[i]),"Missing preview type "+types[i]+" in "+ddl);
        assertFalse(result.toString().contains("Desteklenmeyen hedef kolon tipi"));
    }
}

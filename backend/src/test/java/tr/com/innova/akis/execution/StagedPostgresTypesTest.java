package tr.com.innova.akis.execution;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import tr.com.innova.akis.knowledge.AkisKmInterpreter;
import tr.com.innova.akis.knowledge.AkisKmLanguage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Lossless source/target boundaries of scripts/performance/dit_schema.py, without remote connections. */
class StagedPostgresTypesTest {
    private record Pair(Column source, Column target, String readType, String ddl) { }
    private static Column column(String producer, String canonical, Integer precision, Integer scale, Long length, Integer time) {
        return new Column("VALUE",producer,canonical,1,precision,scale,length,time,true,null,"VALUE");
    }
    private static Pair numeric(int p,int s,int targetP,int targetS) {
        return new Pair(column("NUMBER("+p+","+s+")",s==0&&p<=19?"INTEGER":"DECIMAL",p,s,null,null),
                column("NUMERIC("+targetP+","+targetS+")",targetS==0&&targetP<=19?"INTEGER":"DECIMAL",targetP,targetS,null,null),"NUMBER","NUMERIC("+targetP+","+targetS+")");
    }
    private static List<Pair> supported() {
        return List.of(numeric(38,0,38,0),numeric(38,10,38,10),numeric(12,-2,14,0),numeric(18,6,20,8),
                new Pair(column("BINARY_FLOAT","FLOAT32",null,null,null,null),column("REAL","FLOAT32",null,null,null,null),"BINARY_FLOAT","REAL"),
                new Pair(column("BINARY_DOUBLE","FLOAT64",null,null,null,null),column("DOUBLE PRECISION","FLOAT64",null,null,null,null),"BINARY_DOUBLE","DOUBLE PRECISION"),
                new Pair(column("CHAR(8)","STRING",null,null,8L,null),column("CHAR(8)","STRING",null,null,8L,null),"CHAR","CHAR(8)"),
                new Pair(column("NCHAR(8)","STRING",null,null,8L,null),column("CHAR(8)","STRING",null,null,8L,null),"NCHAR","CHAR(8)"),
                new Pair(column("NVARCHAR2(120)","STRING",null,null,120L,null),column("VARCHAR(120)","STRING",null,null,120L,null),"NVARCHAR2","VARCHAR(120)"),
                new Pair(column("NCLOB","STRING",null,null,null,null),column("TEXT","STRING",null,null,null,null),"NCLOB","TEXT"),
                new Pair(column("RAW(32)","BINARY",null,null,32L,null),column("BYTEA","BINARY",null,null,null,null),"RAW","BYTEA"),
                new Pair(column("BLOB","BINARY",null,null,null,null),column("BYTEA","BINARY",null,null,null,null),"BLOB","BYTEA"),
                new Pair(column("DATE","TIMESTAMP",null,null,null,0),column("TIMESTAMP(0)","TIMESTAMP",null,null,null,0),"DATE","TIMESTAMP(0)"),
                new Pair(column("TIMESTAMP(6)","TIMESTAMP",null,null,null,6),column("TIMESTAMP(6)","TIMESTAMP",null,null,null,6),"TIMESTAMP","TIMESTAMP(6)"),
                new Pair(column("TIMESTAMP(6) WITH TIME ZONE","OFFSET_TIMESTAMP",null,null,null,6),column("TIMESTAMPTZ(6)","OFFSET_TIMESTAMP",null,null,null,6),"TIMESTAMP_TZ","TIMESTAMPTZ(6)"));
    }
    @Test void acceptsEveryLosslessBenchmarkTypeIncludingNegativeScaleAndOffsetTimestamps() {
        for(var pair:supported()) assertDoesNotThrow(()->JdbcPostgresSchemaPreflight.requireCompatible(pair.source(),pair.target()),pair.source().producerType()+" -> "+pair.ddl());
    }
    @Test void rejectsRoundingNarrowingAndDroppingTimeOrOffsets() {
        var number=column("NUMBER(12,-2)","DECIMAL",12,-2,null,null);
        for(var target:List.of(column("NUMERIC(12,0)","INTEGER",12,0,null,null),column("NUMERIC(14,1)","DECIMAL",14,1,null,null)))
            assertThrows(OracleSchemaPreflightException.class,()->JdbcPostgresSchemaPreflight.requireCompatible(number,target));
        assertThrows(OracleSchemaPreflightException.class,()->JdbcPostgresSchemaPreflight.requireCompatible(
                column("NUMBER(38,10)","DECIMAL",38,10,null,null),column("NUMERIC(38,6)","DECIMAL",38,6,null,null)));
        assertThrows(OracleSchemaPreflightException.class,()->JdbcPostgresSchemaPreflight.requireCompatible(
                column("BINARY_DOUBLE","FLOAT64",null,null,null,null),column("REAL","FLOAT32",null,null,null,null)));
        assertThrows(OracleSchemaPreflightException.class,()->JdbcPostgresSchemaPreflight.requireCompatible(
                column("DATE","TIMESTAMP",null,null,null,0),column("DATE","DATE",null,null,null,null)));
        assertThrows(OracleSchemaPreflightException.class,()->JdbcPostgresSchemaPreflight.requireCompatible(
                column("TIMESTAMP(6) WITH TIME ZONE","OFFSET_TIMESTAMP",null,null,null,6),column("TIMESTAMP(6)","TIMESTAMP",null,null,null,6)));
        assertThrows(OracleSchemaPreflightException.class,()->JdbcPostgresSchemaPreflight.requireCompatible(
                column("TIMESTAMP(6) WITH TIME ZONE","OFFSET_TIMESTAMP",null,null,null,6),column("TIMESTAMPTZ(3)","OFFSET_TIMESTAMP",null,null,null,3)));
        assertThrows(OracleSchemaPreflightException.class,()->JdbcPostgresSchemaPreflight.requireCompatible(
                column("VARCHAR2(8)","STRING",null,null,8L,null),column("CHAR(8)","STRING",null,null,8L,null)));
    }
    @Test void layoutKeepsTargetDdlAndSourceReadTypeSeparate() {
        var mapper=new JsonMapper();
        for(var pair:supported()) {
            var plan=mock(StagedRuntimePlan.class);
            doReturn(List.of(new PilotRuntimePlan.DirectColumnMapping("VALUE","VALUE"))).when(plan).columnMappings();
            when(plan.columnSourceObjects()).thenReturn(List.of("SOURCE"));
            when(plan.program()).thenReturn(AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(
                    AkisKmLanguage.example(AkisKmLanguage.Kind.LKM),null,AkisKmLanguage.example(AkisKmLanguage.Kind.IKM))));
            var target=new SchemaFingerprintInput("POSTGRESQL",1,mapper.createObjectNode(),List.of(pair.target()),List.of());
            var source=new SchemaFingerprintInput("19",1,mapper.createObjectNode(),List.of(pair.source()),List.of());
            var layout=StagedColumnLayout.create(plan,target,PilotRuntimePlan.DatabaseType.POSTGRESQL,Map.of("SOURCE",source));
            assertEquals(pair.ddl(),layout.work().getFirst().ddlType());
            assertEquals(pair.readType(),layout.transfer().getFirst().type().name());
        }
    }
}

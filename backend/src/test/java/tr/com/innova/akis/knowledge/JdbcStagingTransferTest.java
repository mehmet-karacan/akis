package tr.com.innova.akis.knowledge;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcStagingTransferTest {
    private static List<JdbcStagingTransfer.QuerySource> sources() {
        return List.of(new JdbcStagingTransfer.QuerySource("S1","O",new JdbcStagingTransfer.Table("SRC","ORDERS")),
                new JdbcStagingTransfer.QuerySource("S2","C",new JdbcStagingTransfer.Table("SRC","CUSTOMERS")));
    }
    private static StagedMappingDefinition.Join join(String type,String left,String right) {
        return new StagedMappingDefinition.Join(type,new StagedMappingDefinition.ColumnRef("S1",left),new StagedMappingDefinition.ColumnRef("S2",right));
    }
    private static void runQuery(Fixture fixture,List<JdbcStagingTransfer.QuerySource> sources,List<StagedMappingDefinition.Join> joins,List<StagedMappingDefinition.Filter> filters) {
        new JdbcStagingTransfer().transfer(fixture.source,fixture.stage,new JdbcStagingTransfer.Table("SRC","ORDERS"),
                new JdbcStagingTransfer.Table("WORK","AKIS_C_TEST"),List.of(new JdbcStagingTransfer.Column("S1","ID","ID",JdbcStagingTransfer.Type.NUMBER)),
                new StagedMappingDefinition.Options(500,500,10,1_000_000,false),30,()->{},JdbcTransactionBoundary.direct(fixture.stage),
                new JdbcStagingTransfer.QueryOptions(false,"",sources,joins,filters));
    }
    private static final class Fixture {
        final Connection source=mock(Connection.class),stage=mock(Connection.class);
        final PreparedStatement read=mock(PreparedStatement.class),write=mock(PreparedStatement.class);
        final ResultSet cursor=mock(ResultSet.class);
        final AtomicInteger position=new AtomicInteger(),pending=new AtomicInteger();
        Fixture(int rows) throws Exception {
            when(source.prepareStatement(anyString())).thenReturn(read); when(stage.prepareStatement(anyString())).thenReturn(write);
            when(read.executeQuery()).thenReturn(cursor);
            var metadata=mock(ResultSetMetaData.class); when(cursor.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(1); when(metadata.getColumnTypeName(1)).thenReturn("NUMBER");
            when(cursor.next()).thenAnswer(call->position.incrementAndGet()<=rows);
            when(cursor.getBigDecimal(1)).thenAnswer(call->BigDecimal.valueOf(position.get()));
            doAnswer(call->{pending.incrementAndGet();return null;}).when(write).addBatch();
            when(write.executeBatch()).thenAnswer(call->{ int[] counts=new int[pending.getAndSet(0)]; Arrays.fill(counts,1); return counts; });
        }
        JdbcStagingTransfer.Result run(long maxRows) {
            return new JdbcStagingTransfer().transfer(source,stage,new JdbcStagingTransfer.Table("SRC","ITEMS"),
                new JdbcStagingTransfer.Table("WORK","AKIS_C_TEST"),List.of(new JdbcStagingTransfer.Column("ID","ID",JdbcStagingTransfer.Type.NUMBER)),
                new StagedMappingDefinition.Options(500,500,maxRows,1_000_000,false),30,()->{});
        }
    }
    @Test void streamsBeyondLegacyThousandRowLimitWithBoundedBatches() throws Exception {
        var fixture=new Fixture(1201);
        var result=fixture.run(2000);
        assertEquals(1201,result.rows()); assertTrue(result.payloadHash().matches("[0-9a-f]{64}"));
        verify(fixture.stage,times(3)).commit(); verify(fixture.write,times(3)).executeBatch();
        verify(fixture.write,times(1201)).setBigDecimal(eq(1),any());
        verify(fixture.source,never()).commit();
    }
    @Test void quotaFailureDoesNotReturnSealableResult() throws Exception {
        var fixture=new Fixture(1201);
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->fixture.run(1000));
        verify(fixture.stage).rollback(); verify(fixture.stage,times(2)).commit();
    }
    @Test void commitAcknowledgementLossIsNotRetried() throws Exception {
        var fixture=new Fixture(1);
        doThrow(new SQLException("sensitive connection detail")).when(fixture.stage).commit();
        var error=assertThrows(JdbcStagingTransfer.TransferFailure.class,()->fixture.run(10));
        assertTrue(error.commitUncertain()); assertFalse(error.toString().contains("sensitive"));
        verify(fixture.stage,times(1)).commit();
    }
    @Test void emptySourceIsRejectedBeforeAnyCommit() throws Exception {
        var fixture=new Fixture(0);
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->fixture.run(10));
        verify(fixture.stage,never()).commit();
    }
    @Test void identifiersCannotInjectSql() {
        assertThrows(IllegalArgumentException.class,()->new JdbcStagingTransfer.Table("WORK","A;DROP TABLE TARGET"));
    }
    @Test void appliesDistinctAndValidatedOracleHintToSourceQuery() throws Exception {
        var fixture=new Fixture(1);
        new JdbcStagingTransfer().transfer(fixture.source,fixture.stage,new JdbcStagingTransfer.Table("SRC","ITEMS"),
                new JdbcStagingTransfer.Table("WORK","AKIS_C_TEST"),List.of(new JdbcStagingTransfer.Column("ID","ID",JdbcStagingTransfer.Type.NUMBER)),
                new StagedMappingDefinition.Options(500,500,10,1_000_000,false),30,()->{},JdbcTransactionBoundary.direct(fixture.stage),
                new JdbcStagingTransfer.QueryOptions(true,"PARALLEL(4)"));
        verify(fixture.source).prepareStatement("SELECT /*+ PARALLEL(4) */ DISTINCT \"ID\" FROM \"SRC\".\"ITEMS\"");
        assertThrows(IllegalArgumentException.class,()->new JdbcStagingTransfer.QueryOptions(false,"FULL(T) */ DELETE"));
    }
    @Test void buildsMultiSourceJoinAndBindsStructuredFilters() throws Exception {
        var fixture=new Fixture(1);
        var sources=List.of(
                new JdbcStagingTransfer.QuerySource("S1","ORDERS",new JdbcStagingTransfer.Table("SRC","ORDERS")),
                new JdbcStagingTransfer.QuerySource("S2","CUSTOMERS",new JdbcStagingTransfer.Table("SRC","CUSTOMERS")));
        var joins=List.of(new StagedMappingDefinition.Join("INNER",new StagedMappingDefinition.ColumnRef("S1","CUSTOMER_ID"),new StagedMappingDefinition.ColumnRef("S2","ID")));
        var filters=List.of(new StagedMappingDefinition.Filter("GLOBAL","S1","STATUS","EQUALS","ACTIVE"));
        new JdbcStagingTransfer().transfer(fixture.source,fixture.stage,new JdbcStagingTransfer.Table("SRC","ORDERS"),
                new JdbcStagingTransfer.Table("WORK","AKIS_C_TEST"),List.of(new JdbcStagingTransfer.Column("S1","ID","ID",JdbcStagingTransfer.Type.NUMBER)),
                new StagedMappingDefinition.Options(500,500,10,1_000_000,false),30,()->{},JdbcTransactionBoundary.direct(fixture.stage),
                new JdbcStagingTransfer.QueryOptions(false,"",sources,joins,filters));
        verify(fixture.source).prepareStatement("SELECT \"ORDERS\".\"ID\" FROM \"SRC\".\"ORDERS\" \"ORDERS\" JOIN \"SRC\".\"CUSTOMERS\" \"CUSTOMERS\" ON \"ORDERS\".\"CUSTOMER_ID\" = \"CUSTOMERS\".\"ID\" WHERE \"ORDERS\".\"STATUS\" = ?");
        verify(fixture.read).setString(1,"ACTIVE");
    }
    @Test void appliesSourceFiltersBeforeOuterJoinAndGlobalFiltersAfterWithSqlOrderBindings() throws Exception {
        var fixture=new Fixture(1);
        runQuery(fixture,sources(),List.of(join("LEFT","CUSTOMER_ID","ID")),List.of(
                new StagedMappingDefinition.Filter("GLOBAL","S1","STATE","EQUALS","COMPLETE"),
                new StagedMappingDefinition.Filter("SOURCE","S2","STATE","EQUALS","ACTIVE"),
                new StagedMappingDefinition.Filter("SOURCE","S1","TENANT","EQUALS","tenant' OR 1=1 --")));
        verify(fixture.source).prepareStatement("SELECT \"O\".\"ID\" FROM (SELECT * FROM \"SRC\".\"ORDERS\" \"O\" WHERE \"O\".\"TENANT\" = ?) \"O\" LEFT JOIN (SELECT * FROM \"SRC\".\"CUSTOMERS\" \"C\" WHERE \"C\".\"STATE\" = ?) \"C\" ON \"O\".\"CUSTOMER_ID\" = \"C\".\"ID\" WHERE \"O\".\"STATE\" = ?");
        verify(fixture.read).setString(1,"tenant' OR 1=1 --");
        verify(fixture.read).setString(2,"ACTIVE");
        verify(fixture.read).setString(3,"COMPLETE");
    }
    @Test void keepsCompositeOuterJoinConditionsInOnNotWhere() throws Exception {
        var fixture=new Fixture(1);
        runQuery(fixture,sources(),List.of(join("LEFT","CUSTOMER_ID","ID"),join("LEFT","TENANT","TENANT")),List.of());
        verify(fixture.source).prepareStatement("SELECT \"O\".\"ID\" FROM \"SRC\".\"ORDERS\" \"O\" LEFT JOIN \"SRC\".\"CUSTOMERS\" \"C\" ON \"O\".\"CUSTOMER_ID\" = \"C\".\"ID\" AND \"O\".\"TENANT\" = \"C\".\"TENANT\"");
    }
    @Test void bindsLikePatternWithOneCharacterOracleEscape() throws Exception {
        var fixture=new Fixture(1);
        runQuery(fixture,sources().subList(0,1),List.of(),List.of(new StagedMappingDefinition.Filter("SOURCE","S1","NAME","LIKE","A\\_%")));
        verify(fixture.source).prepareStatement("SELECT \"O\".\"ID\" FROM (SELECT * FROM \"SRC\".\"ORDERS\" \"O\" WHERE \"O\".\"NAME\" LIKE ? ESCAPE '\\') \"O\"");
        verify(fixture.read).setString(1,"A\\_%");
    }
    @Test void rejectsUnknownScopeAndConflictingRelationsBeforeAnyRemoteStatement() throws Exception {
        var fixture=new Fixture(1);
        assertThrows(IllegalArgumentException.class,()->runQuery(fixture,sources(),List.of(join("LEFT","ID","ID")),List.of(new StagedMappingDefinition.Filter("WRONG","S1","ID","IS_NULL",null))));
        assertThrows(IllegalArgumentException.class,()->runQuery(fixture,sources(),List.of(join("LEFT","ID","ID"),join("INNER","TENANT","TENANT")),List.of()));
        verify(fixture.source,never()).prepareStatement(anyString());
        verify(fixture.stage,never()).prepareStatement(anyString());
    }
    @Test void preservesReversedOuterJoinAndAllInnerCycleConditions() throws Exception {
        var reversed=new Fixture(1);
        runQuery(reversed,List.of(sources().get(1),sources().get(0)),List.of(join("LEFT","CUSTOMER_ID","ID")),List.of());
        verify(reversed.source).prepareStatement("SELECT \"O\".\"ID\" FROM \"SRC\".\"CUSTOMERS\" \"C\" RIGHT JOIN \"SRC\".\"ORDERS\" \"O\" ON \"O\".\"CUSTOMER_ID\" = \"C\".\"ID\"");
        var cycle=new Fixture(1);
        var allSources=new ArrayList<>(sources()); allSources.add(new JdbcStagingTransfer.QuerySource("S3","I",new JdbcStagingTransfer.Table("SRC","ITEMS")));
        var second=new StagedMappingDefinition.Join("INNER",new StagedMappingDefinition.ColumnRef("S2","ID"),new StagedMappingDefinition.ColumnRef("S3","CUSTOMER_ID"));
        var third=new StagedMappingDefinition.Join("INNER",new StagedMappingDefinition.ColumnRef("S3","ORDER_ID"),new StagedMappingDefinition.ColumnRef("S1","ID"));
        runQuery(cycle,allSources,List.of(join("INNER","CUSTOMER_ID","ID"),second,third),List.of());
        verify(cycle.source).prepareStatement("SELECT \"O\".\"ID\" FROM \"SRC\".\"ORDERS\" \"O\" JOIN \"SRC\".\"CUSTOMERS\" \"C\" ON \"O\".\"CUSTOMER_ID\" = \"C\".\"ID\" JOIN \"SRC\".\"ITEMS\" \"I\" ON \"C\".\"ID\" = \"I\".\"CUSTOMER_ID\" WHERE \"I\".\"ORDER_ID\" = \"O\".\"ID\"");
    }

    @Test void rejectsDanglingOrSelfEdgesInsteadOfExecutingPartialGraph() throws Exception {
        var fixture=new Fixture(1);
        for(String right:List.of("S1","UNKNOWN")) {
            var invalid=new StagedMappingDefinition.Join("INNER",new StagedMappingDefinition.ColumnRef("S1","ID"),new StagedMappingDefinition.ColumnRef(right,"ID"));
            assertThrows(IllegalArgumentException.class,()->runQuery(fixture,sources(),List.of(join("INNER","ID","ID"),invalid),List.of()));
        }
        verifyNoInteractions(fixture.source,fixture.stage);
    }
    @Test void executesExpressionProjectionWithSelectBindsBeforeSourceAndGlobalFilterBinds() throws Exception {
        var fixture=new Fixture(1);
        var expression=MappingSql.parse("SRC.ID * 2 + 1",List.of(new MappingSql.Source("S1","SRC",Set.of("ID"))),false);
        var source=new JdbcStagingTransfer.QuerySource("S1","SRC",new JdbcStagingTransfer.Table("SRC","ITEMS"),Set.of("ID","STATE"));
        new JdbcStagingTransfer().transfer(fixture.source,fixture.stage,source.table(),new JdbcStagingTransfer.Table("WORK","AKIS_C_TEST"),
                List.of(new JdbcStagingTransfer.Column(null,null,"ID",JdbcStagingTransfer.Type.NUMBER,expression)),
                new StagedMappingDefinition.Options(500,500,10,1_000_000,false),30,()->{},JdbcTransactionBoundary.direct(fixture.stage),
                new JdbcStagingTransfer.QueryOptions(false,"",List.of(source),List.of(),List.of(
                        new StagedMappingDefinition.Filter("GLOBAL","S1","STATE","EQUALS","ACTIVE"),
                        new StagedMappingDefinition.Filter("SOURCE","S1","ID","GREATER_THAN","10"))));
        verify(fixture.source).prepareStatement("SELECT ((\"SRC\".\"ID\" * ?) + ?) FROM (SELECT * FROM \"SRC\".\"ITEMS\" \"SRC\" WHERE \"SRC\".\"ID\" > ?) \"SRC\" WHERE \"SRC\".\"STATE\" = ?");
        verify(fixture.read).setBigDecimal(1,new BigDecimal("2"));verify(fixture.read).setBigDecimal(2,new BigDecimal("1"));
        verify(fixture.read).setString(3,"10");verify(fixture.read).setString(4,"ACTIVE");
        verify(fixture.write).setBigDecimal(1,BigDecimal.ONE);
    }
    @Test void freePredicatesKeepSourceScopeAndGlobalScopeWithBoundValues() throws Exception {
        var fixture=new Fixture(1);
        var catalog=List.of(new MappingSql.Source("S1","O",Set.of("ID","CUSTOMER_ID","STATE")),new MappingSql.Source("S2","C",Set.of("ID","STATE")));
        var sources=List.of(new JdbcStagingTransfer.QuerySource("S1","O",new JdbcStagingTransfer.Table("SRC","ORDERS"),catalog.get(0).columns()),
                new JdbcStagingTransfer.QuerySource("S2","C",new JdbcStagingTransfer.Table("SRC","CUSTOMERS"),catalog.get(1).columns()));
        var sourcePredicate=MappingSql.parse("C.STATE LIKE 'A\\_%' ESCAPE '\\'",catalog,true);
        var globalPredicate=MappingSql.parse("O.ID > 10 OR C.ID IS NULL",catalog,true);
        runQuery(fixture,sources,List.of(join("LEFT","CUSTOMER_ID","ID")),List.of(
                new StagedMappingDefinition.Filter("GLOBAL","S1",null,null,null,globalPredicate),
                new StagedMappingDefinition.Filter("SOURCE","S2",null,null,null,sourcePredicate)));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.source).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("LEFT JOIN (SELECT * FROM \"SRC\".\"CUSTOMERS\" \"C\" WHERE ((\"C\".\"STATE\" LIKE ? ESCAPE ?))) \"C\" ON"));
        assertTrue(sql.getValue().endsWith("WHERE (((\"O\".\"ID\" > ?) OR (\"C\".\"ID\" IS NULL)))"));
        verify(fixture.read).setString(1,"A\\_%"); verify(fixture.read).setString(2,"\\"); verify(fixture.read).setBigDecimal(3,new BigDecimal("10"));
        assertFalse(sql.getValue().contains("A\\_%"));
    }
    @Test void rejectsCrossSourceAndMissingColumnPredicatesBeforeRemoteSql() throws Exception {
        var fixture=new Fixture(1);
        var all=List.of(new MappingSql.Source("S1","O",Set.of("ID")),new MappingSql.Source("S2","C",Set.of("ID")));
        var predicate=MappingSql.parse("O.ID = C.ID",all,true);
        var sources=List.of(new JdbcStagingTransfer.QuerySource("S1","O",new JdbcStagingTransfer.Table("SRC","ORDERS"),Set.of("ID")),
                new JdbcStagingTransfer.QuerySource("S2","C",new JdbcStagingTransfer.Table("SRC","CUSTOMERS"),Set.of("ID")));
        assertThrows(IllegalArgumentException.class,()->runQuery(fixture,sources,List.of(join("LEFT","ID","ID")),List.of(new StagedMappingDefinition.Filter("SOURCE","S1",null,null,null,predicate))));
        var missing=MappingSql.parse("O.UNKNOWN > 10",List.of(new MappingSql.Source("S1","O",Set.of("UNKNOWN"))),true);
        assertThrows(IllegalArgumentException.class,()->runQuery(fixture,sources,List.of(join("LEFT","ID","ID")),List.of(new StagedMappingDefinition.Filter("GLOBAL","S1",null,null,null,missing))));
        verifyNoInteractions(fixture.source,fixture.stage);
    }
    @Test void expressionResultTypeMismatchCannotWriteToStage() throws Exception {
        var fixture=new Fixture(1);
        var source=new JdbcStagingTransfer.QuerySource("S1","SRC",new JdbcStagingTransfer.Table("SRC","ITEMS"),Set.of("ID"));
        var expression=MappingSql.parse("TO_CHAR(SRC.ID)",List.of(new MappingSql.Source("S1","SRC",Set.of("ID"))),false);
        // The fixture supplies NUMBER metadata, not the required VARCHAR2 result.
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->new JdbcStagingTransfer().transfer(fixture.source,fixture.stage,source.table(),new JdbcStagingTransfer.Table("WORK","AKIS_C_TEST"),
                List.of(new JdbcStagingTransfer.Column(null,null,"ID",JdbcStagingTransfer.Type.VARCHAR2,expression)),
                new StagedMappingDefinition.Options(500,500,10,1_000_000,false),30,()->{},JdbcTransactionBoundary.direct(fixture.stage),
                new JdbcStagingTransfer.QueryOptions(false,"",List.of(source),List.of(),List.of())));
        verify(fixture.write,never()).addBatch();verify(fixture.stage,never()).commit();verify(fixture.stage).rollback();
    }
}

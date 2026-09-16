package tr.com.innova.akis.knowledge;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcStagingTransferTest {
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
}

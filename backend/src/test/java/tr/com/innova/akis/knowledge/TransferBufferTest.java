package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferBufferTest {
    @Test void moreBudgetExpandsSafeReadCapacityWithoutRemovingHeapOrWriteGuards() throws Exception {
        var metadata=mock(ResultSetMetaData.class);var columns=new ArrayList<JdbcStagingTransfer.Column>();
        for(int i=0;i<256;i++){columns.add(new JdbcStagingTransfer.Column("C"+i,"C"+i,JdbcStagingTransfer.Type.VARCHAR2));when(metadata.getColumnDisplaySize(i+1)).thenReturn(4000);}
        var small=new TransferBuffer(new StagedMappingDefinition.Options(1000,5000,1000,1_000_000_000L,false,"AUTO",1073741824L),()->{});
        var large=new TransferBuffer(new StagedMappingDefinition.Options(1000,5000,1000,1_000_000_000L,false,"AUTO",5368709120L),()->{});
        small.project(metadata,columns);large.project(metadata,columns);
        assertTrue(large.fetchRows()>small.fetchRows());
        assertTrue(large.readBudget()<=large.effectiveBudget()/2);
        assertTrue(large.effectiveBudget()<=Runtime.getRuntime().maxMemory()/4);
        assertTrue(large.memoryBudget()<=32L*1024*1024,"write path stays bounded while only source reading is tuned");
    }
    @Test void smallBudgetStopsBinaryLobBeforeMaterializingItsEncodingCopies() throws Exception {
        var options=new StagedMappingDefinition.Options(1000,256,1000,1_000_000_000L,false,"AUTO",64L*1024*1024);
        var buffer=new TransferBuffer(options,()->{});
        var cursor=mock(ResultSet.class);var lob=mock(Blob.class);
        var consumed=new java.util.concurrent.atomic.AtomicInteger();
        when(cursor.getBlob(1)).thenReturn(lob);
        when(lob.getBinaryStream()).thenReturn(new java.io.InputStream(){
            private int left=3*1024*1024;
            public int read(){if(left==0)return -1;left--;consumed.incrementAndGet();return 65;}
            public int read(byte[] bytes,int offset,int length){
                if(left==0)return -1;int n=Math.min(left,length);left-=n;
                Arrays.fill(bytes,offset,offset+n,(byte)65);consumed.addAndGet(n);return n;
            }
        });
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->buffer.read(cursor,
                List.of(new JdbcStagingTransfer.Column("A","A",JdbcStagingTransfer.Type.BLOB)),0));
        assertTrue(consumed.get()<=buffer.memoryBudget()/16+1);
    }
    @Test void smallBudgetRejectsLargeLobBeforeMaterializingTheWholeRow() throws Exception {
        var options=new StagedMappingDefinition.Options(1000,256,1000,1_000_000_000L,false,"AUTO",64L*1024*1024);
        var buffer=new TransferBuffer(options,()->{});
        var columns=List.of(new JdbcStagingTransfer.Column("A","A",JdbcStagingTransfer.Type.CLOB),
                new JdbcStagingTransfer.Column("B","B",JdbcStagingTransfer.Type.CLOB));
        var cursor=mock(ResultSet.class);var lob=mock(Clob.class);
        var consumed=new java.util.concurrent.atomic.AtomicInteger();
        when(cursor.getClob(anyInt())).thenReturn(lob);
        when(lob.getCharacterStream()).thenAnswer(invocation->new java.io.Reader(){
            private int left=7*1024*1024;
            public int read(char[] chars,int offset,int length){
                if(left==0)return -1;int n=Math.min(left,length);left-=n;
                Arrays.fill(chars,offset,offset+n,'x');consumed.addAndGet(n);return n;
            }
            public void close(){}
        });
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->buffer.read(cursor,columns,0));
        assertTrue(consumed.get()<=buffer.memoryBudget()/8+1,"Stop reading at the row budget, not after full materialization");
        verify(cursor,never()).getClob(2);
    }
    @Test void inheritedOneGiBAllowsBoundedLobReadWindowInsteadOfTinyLegacyWindow() throws Exception {
        var mapper=new tools.jackson.databind.ObjectMapper();
        var options=StagedMappingDefinition.parseOptions(mapper.readTree("""
            {"resourceMode":"AUTO","allowEmptySource":false,"memoryBudgetBytes":1073741824}
            """));
        var columns=new ArrayList<JdbcStagingTransfer.Column>();
        var metadata=mock(ResultSetMetaData.class);
        for(int i=0;i<50;i++) {
            columns.add(new JdbcStagingTransfer.Column("C"+i,"C"+i,i%5==0?JdbcStagingTransfer.Type.CLOB:JdbcStagingTransfer.Type.VARCHAR2));
            when(metadata.getColumnDisplaySize(i+1)).thenReturn(4000);
        }
        var buffer=new TransferBuffer(options,()->{});buffer.project(metadata,columns);
        assertTrue(buffer.fetchRows()>1,"Configured memory should enable multi-row reads for wide projections");
        assertTrue(buffer.fetchRows()<=256);
        assertTrue(buffer.memoryBudget()<=1073741824L/4);
    }
    @Test void declaredWideProjectionCannotExpandTheWindowAfterATinyFirstRow() throws Exception {
        var columns=new ArrayList<JdbcStagingTransfer.Column>();
        var metadata=mock(ResultSetMetaData.class);
        for(int i=0;i<256;i++) {columns.add(new JdbcStagingTransfer.Column("C"+i,"C"+i,JdbcStagingTransfer.Type.VARCHAR2));when(metadata.getColumnDisplaySize(i+1)).thenReturn(4000);}
        var buffer=new TransferBuffer(new StagedMappingDefinition.Options(1000,256,1000,1_000_000_000L,false,"AUTO"),()->{});
        buffer.project(metadata,columns);
        var cursor=mock(ResultSet.class);when(cursor.getString(anyInt())).thenReturn("x");
        buffer.read(cursor,columns,0);
        assertEquals(1,buffer.fetchRows());
    }
    @Test void unknownStringWidthStaysConservativeWhileSmallDeclaredColumnsCanBatch() throws Exception {
        var columns=List.of(new JdbcStagingTransfer.Column("TEXT","TEXT",JdbcStagingTransfer.Type.VARCHAR2));
        var metadata=mock(ResultSetMetaData.class);
        var buffer=new TransferBuffer(new StagedMappingDefinition.Options(1000,256,1000,1_000_000_000L,false,"AUTO"),()->{});
        buffer.project(metadata,columns);assertEquals(1,buffer.fetchRows());
        when(metadata.getColumnDisplaySize(1)).thenReturn(80);
        buffer.project(metadata,columns);assertTrue(buffer.fetchRows()>1);assertTrue(buffer.fetchRows()<=256);
    }
}

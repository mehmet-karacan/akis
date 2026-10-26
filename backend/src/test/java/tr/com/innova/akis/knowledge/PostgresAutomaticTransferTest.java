package tr.com.innova.akis.knowledge;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresAutomaticTransferTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=JdbcStagingTransfer.Type.class,names={"CLOB","BLOB"})
    void manualOversizedLobIsStreamBoundedAndRollsBackBeforeCopyOrSeal(JdbcStagingTransfer.Type type) throws Exception {
        var source=mock(Connection.class);var stage=mock(Connection.class);var read=mock(PreparedStatement.class);var cursor=mock(ResultSet.class);
        var pg=mock(PGConnection.class);var copy=mock(CopyManager.class);var consumed=new AtomicInteger();
        when(source.prepareStatement(anyString())).thenReturn(read);when(read.executeQuery()).thenReturn(cursor);when(cursor.next()).thenReturn(true,false);
        when(stage.unwrap(PGConnection.class)).thenReturn(pg);when(pg.getCopyAPI()).thenReturn(copy);
        when(cursor.getString(1)).thenReturn("x".repeat(10000));when(cursor.getBytes(1)).thenReturn(new byte[10000]);
        var lob=mock(java.sql.Clob.class);when(cursor.getClob(1)).thenReturn(lob);
        when(lob.getCharacterStream()).thenReturn(new java.io.Reader(){
            public int read(char[] chars,int offset,int length){int n=Math.min(length,10000-consumed.get());if(n==0)return -1;Arrays.fill(chars,offset,offset+n,'x');consumed.addAndGet(n);return n;}
            public void close(){}
        });
        var blob=mock(java.sql.Blob.class);when(cursor.getBlob(1)).thenReturn(blob);
        when(blob.getBinaryStream()).thenReturn(new java.io.InputStream(){
            public int read(){return consumed.incrementAndGet()<=10000?65:-1;}
            public int read(byte[] bytes,int offset,int length){int n=Math.min(length,10000-consumed.get());if(n==0)return -1;Arrays.fill(bytes,offset,offset+n,(byte)65);consumed.addAndGet(n);return n;}
        });
        var progress=new ArrayList<TransferProgress>();TransferProgress.Listener monitor=new TransferProgress.Listener(){public void run(){}public void report(TransferProgress p){progress.add(p);}};
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->new PostgresCopyStagingTransfer(false).transfer(
            source,stage,new JdbcStagingTransfer.Table("SRC","ITEMS"),new JdbcStagingTransfer.Table("WORK","ITEMS"),
            List.of(new JdbcStagingTransfer.Column("DATA","DATA",type)),new StagedMappingDefinition.Options(1,1,1,1024,false),
            30,monitor,JdbcTransactionBoundary.direct(stage),JdbcStagingTransfer.QueryOptions.defaults()));
        assertTrue(consumed.get()>0,"Read through the bounded stream, never an eager whole-LOB getter");
        assertTrue(consumed.get()<=1021,"Stop at the logical quota plus one detection unit");
        assertTrue(progress.isEmpty());verify(stage).rollback();verify(stage,never()).commit();verify(copy,never()).copyIn(anyString(),any(InputStream.class));
    }
    @Test void copyFlushesWideRowsInBoundedChunksAndReportsOnlyConfirmedRows() throws Exception {
        var source=mock(Connection.class);var stage=mock(Connection.class);var read=mock(PreparedStatement.class);
        var cursor=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);var pg=mock(PGConnection.class);var copy=mock(CopyManager.class);
        when(source.prepareStatement(anyString())).thenReturn(read);when(read.executeQuery()).thenReturn(cursor);
        when(cursor.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnTypeName(1)).thenReturn("VARCHAR2");
        var position=new AtomicInteger();when(cursor.next()).thenAnswer(call->position.incrementAndGet()<=100);
        when(cursor.getString(1)).thenReturn("İ".repeat(32*1024));
        when(stage.unwrap(PGConnection.class)).thenReturn(pg);when(pg.getCopyAPI()).thenReturn(copy);
        var flushes=new AtomicInteger();var largest=new AtomicLong();
        when(copy.copyIn(anyString(),any(InputStream.class))).thenAnswer(call->{
            var input=new InputStreamReader(call.getArgument(1,InputStream.class),StandardCharsets.UTF_8);char[] chunk=new char[8192];long count=0,characters=0;int size;
            while((size=input.read(chunk))!=-1) { characters+=size;for(int i=0;i<size;i++) if(chunk[i]=='\n') count++; }
            flushes.incrementAndGet();largest.accumulateAndGet(characters,Math::max);return count;
        });
        var progress=new ArrayList<TransferProgress>();
        TransferProgress.Listener monitor=new TransferProgress.Listener() { public void run() {}public void report(TransferProgress p) {progress.add(p);} };
        var columns=List.of(new JdbcStagingTransfer.Column("TEXT","TEXT",JdbcStagingTransfer.Type.VARCHAR2));
        var result=new PostgresCopyStagingTransfer().transfer(source,stage,new JdbcStagingTransfer.Table("SRC","ITEMS"),
                new JdbcStagingTransfer.Table("WORK","ITEMS"),columns,new StagedMappingDefinition.Options(1000,256,100,20_000_000,false,"AUTO"),
                30,monitor,JdbcTransactionBoundary.direct(stage),JdbcStagingTransfer.QueryOptions.defaults());
        assertEquals(100,result.rows());assertTrue(flushes.get()>1);
        assertTrue(largest.get()<1_000_000,"COPY never builds the complete 100-row payload");
        assertFalse(progress.isEmpty());assertEquals(100,progress.getLast().rows());assertEquals(result.logicalBytes(),progress.getLast().bytes());
        var diagnostics=progress.getLast().diagnostics();
        assertEquals(100,diagnostics.rowsDecoded());assertEquals(101,diagnostics.fetchCalls());assertEquals(0,diagnostics.lobCells());
        assertEquals(flushes.get(),diagnostics.batchCount());
        assertTrue(diagnostics.fetchSeconds()>0);assertTrue(diagnostics.decodeSeconds()>0);assertTrue(diagnostics.encodeSeconds()>0);
        assertTrue(diagnostics.querySeconds()+diagnostics.fetchSeconds()+diagnostics.lobSeconds()+diagnostics.decodeSeconds()<=progress.getLast().readSeconds()+.01);
        verify(stage,times(flushes.get())).commit();
    }
    @Test void lostCopyCommitIsNotRetriedOrReportedAsConfirmed() throws Exception {
        var source=mock(Connection.class);var stage=mock(Connection.class);var read=mock(PreparedStatement.class);var cursor=mock(ResultSet.class);
        var pg=mock(PGConnection.class);var copy=mock(CopyManager.class);
        when(source.prepareStatement(anyString())).thenReturn(read);when(read.executeQuery()).thenReturn(cursor);
        when(cursor.next()).thenReturn(true,false);when(cursor.getString(1)).thenReturn("row");
        var metadata=mock(ResultSetMetaData.class);when(cursor.getMetaData()).thenReturn(metadata);when(metadata.getColumnDisplaySize(1)).thenReturn(80);
        when(stage.unwrap(PGConnection.class)).thenReturn(pg);when(pg.getCopyAPI()).thenReturn(copy);
        when(copy.copyIn(anyString(),any(InputStream.class))).thenReturn(1L);doThrow(new SQLException("connection lost")).when(stage).commit();
        var progress=new ArrayList<TransferProgress>();
        TransferProgress.Listener monitor=new TransferProgress.Listener() {public void run() {}public void report(TransferProgress p) {progress.add(p);} };
        var error=assertThrows(JdbcStagingTransfer.TransferFailure.class,()->new PostgresCopyStagingTransfer(false).transfer(
                source,stage,new JdbcStagingTransfer.Table("SRC","ITEMS"),new JdbcStagingTransfer.Table("WORK","ITEMS"),
                List.of(new JdbcStagingTransfer.Column("TEXT","TEXT",JdbcStagingTransfer.Type.VARCHAR2)),new StagedMappingDefinition.Options(1,1,1,1000,false,"AUTO"),
                30,monitor,JdbcTransactionBoundary.direct(stage),JdbcStagingTransfer.QueryOptions.defaults()));
        assertTrue(error.commitUncertain());assertTrue(progress.isEmpty());verify(stage,times(1)).commit();
    }
}

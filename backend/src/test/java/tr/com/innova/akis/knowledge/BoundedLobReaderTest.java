package tr.com.innova.akis.knowledge;

import java.io.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundedLobReaderTest {
    @Test void locatorStreamsPreserveUnicodeEmptyAndNullWithoutDataInterfaceGetters() throws Exception {
        var row=mock(ResultSet.class);var text=mock(Clob.class);var binary=mock(Blob.class);
        when(row.getClob(1)).thenReturn(text);when(text.getCharacterStream()).thenReturn(new StringReader("İ😀\n"));
        when(row.getBlob(2)).thenReturn(binary);when(binary.getBinaryStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        assertEquals("İ😀\n",BoundedLobReader.read(row,1,JdbcStagingTransfer.Type.CLOB,()->{}));
        assertArrayEquals(new byte[0],(byte[])BoundedLobReader.read(row,2,JdbcStagingTransfer.Type.BLOB,()->{}));
        assertNull(BoundedLobReader.read(row,3,JdbcStagingTransfer.Type.CLOB,()->{}));
        verify(row,never()).getString(anyInt());verify(row,never()).getBytes(anyInt());
        verify(text,never()).length();verify(text,never()).free();
    }
    @Test void rejectsAnOversizedStreamBeforeMaterializingAllItsContent() {
        var consumed=new AtomicInteger();
        Reader endless=new Reader() {
            public int read(char[] buffer,int offset,int length) { int n=Math.min(length,32);java.util.Arrays.fill(buffer,offset,offset+n,'x');consumed.addAndGet(n);return n; }
            public void close() { }
        };
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->BoundedLobReader.text(endless,8,()->{}));
        assertTrue(consumed.get()<=9);
    }
    @Test void chunkReadHonorsCancellation() {
        assertThrows(IllegalStateException.class,()->BoundedLobReader.text(new StringReader("data"),100,()->{throw new IllegalStateException("cancel");}));
    }
}

package tr.com.innova.akis.knowledge;

import java.io.*;
import java.sql.*;

/** Locator reads keep Oracle row/LOB prefetch active. Allocations are capped while reading, not afterwards. */
final class BoundedLobReader {
    private static final int CHUNK=8192;
    static Object read(ResultSet row,int index,JdbcStagingTransfer.Type type,Runnable checkpoint) throws SQLException {
        return read(row,index,type,checkpoint,Long.MAX_VALUE);
    }
    static Object read(ResultSet row,int index,JdbcStagingTransfer.Type type,Runnable checkpoint,long residentAllowance) throws SQLException {
        // Include canonical text and the UTF-16/encoding copies counted by TransferBuffer.
        long payload=Math.max(0,residentAllowance-128);
        long textLimit=Math.min(JdbcStagingTransfer.BUFFER_BYTES/2,payload/8);
        long binaryLimit=Math.min(JdbcStagingTransfer.BUFFER_BYTES,payload/16);
        return read(row,index,type,checkpoint,textLimit,binaryLimit);
    }
    /** Manual mode retains the logical row/transfer quota but never eagerly materializes an unlimited locator. */
    static Object readManual(ResultSet row,int index,JdbcStagingTransfer.Type type,Runnable checkpoint,long logicalAllowance) throws SQLException {
        long characters=Math.max(0,Math.min(JdbcStagingTransfer.BUFFER_BYTES,logicalAllowance-4));
        long binary=Math.max(0,(characters/4)*3); // Base64 framing must fit the same logical allowance.
        return read(row,index,type,checkpoint,characters,binary);
    }
    private static Object read(ResultSet row,int index,JdbcStagingTransfer.Type type,Runnable checkpoint,long textLimit,long binaryLimit) throws SQLException {
        try {
            if(type==JdbcStagingTransfer.Type.BLOB) {
                Blob lob=row.getBlob(index);
                if(lob==null) return null;
                try(InputStream stream=lob.getBinaryStream()) { return binary(stream,binaryLimit,checkpoint); }
            }
            Clob lob=type==JdbcStagingTransfer.Type.NCLOB?row.getNClob(index):row.getClob(index);
            if(lob==null) return null;
            // Locators live only for this cursor. Calling length()/free() per cell introduces extra Oracle RPCs.
            try(Reader reader=lob.getCharacterStream()) { return text(reader,textLimit,checkpoint); }
        } catch(IOException failure) { throw new SQLException("LOB akışı okunamadı.",failure); }
    }
    static String text(Reader reader,long maximum,Runnable checkpoint) throws IOException {
        if(reader==null) return null;
        StringBuilder result=new StringBuilder();char[] chunk=new char[CHUNK];
        for(;;) {
            checkpoint.run();
            int count=reader.read(chunk,0,(int)Math.min(chunk.length,maximum-result.length()+1));
            if(count<0) return result.toString();
            if(count==0) continue;
            if(result.length()+count>maximum) throw limit();
            result.append(chunk,0,count);
        }
    }
    private static byte[] binary(InputStream stream,long maximum,Runnable checkpoint) throws IOException {
        if(stream==null) return null;
        ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] chunk=new byte[CHUNK];
        for(;;) {
            checkpoint.run();
            int count=stream.read(chunk,0,(int)Math.min(chunk.length,maximum-result.size()+1));
            if(count<0) return result.toByteArray();
            if(count==0) continue;
            if(result.size()+count>maximum) throw limit();
            result.write(chunk,0,count);
        }
    }
    private static JdbcStagingTransfer.TransferFailure limit() {
        return new JdbcStagingTransfer.TransferFailure("LOB hücresi güvenli bellek sınırını aşıyor; stage mühürlenmedi.",false);
    }
}

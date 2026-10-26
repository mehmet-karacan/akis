package tr.com.innova.akis.knowledge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StagingTransferTypesTest {
    private static JdbcStagingTransfer.Type type(String name) { return JdbcStagingTransfer.Type.valueOf(name); }
    @Test void binaryWidthsAndNullsAreReadWithoutDecimalOrDoubleCoercion() throws Exception {
        var row=mock(ResultSet.class);
        when(row.getFloat(1)).thenReturn(1.2345678f);when(row.getDouble(2)).thenReturn(1e-100);
        assertEquals(Float.valueOf(1.2345678f),JdbcStagingTransfer.readCell(row,1,type("BINARY_FLOAT")));
        assertEquals(Double.valueOf(1e-100),JdbcStagingTransfer.readCell(row,2,type("BINARY_DOUBLE")));
        when(row.wasNull()).thenReturn(true);
        assertNull(JdbcStagingTransfer.readCell(row,1,type("BINARY_FLOAT")));
        assertNull(JdbcStagingTransfer.readCell(row,2,type("BINARY_DOUBLE")));
    }
    @Test void keepsNumbersUnicodeEmptyBinaryAndTimestampInstants() throws Exception {
        var row=mock(ResultSet.class);
        var decimal=new BigDecimal("99999999999999999999999999999999999999");
        when(row.getBigDecimal(1)).thenReturn(decimal);
        assertEquals(decimal,JdbcStagingTransfer.readCell(row,1,type("NUMBER")));
        assertEquals("1234500",JdbcStagingTransfer.canonicalText(new BigDecimal("1.2345E+6")));
        when(row.getNString(2)).thenReturn("İşığöşü😀");
        for(String name:List.of("NCHAR","NVARCHAR2","NCLOB"))assertEquals("İşığöşü😀",JdbcStagingTransfer.readCell(row,2,type(name)));
        when(row.getBytes(3)).thenReturn(new byte[0]);
        for(String name:List.of("RAW","BLOB"))assertArrayEquals(new byte[0],(byte[])JdbcStagingTransfer.readCell(row,3,type(name)));
        var offset=OffsetDateTime.parse("2020-01-01T12:34:56.123456+03:00");
        when(row.getObject(4,OffsetDateTime.class)).thenReturn(offset);
        assertEquals(offset,JdbcStagingTransfer.readCell(row,4,type("TIMESTAMP_TZ")));
        assertEquals("2020-01-01T09:34:56.123456Z",JdbcStagingTransfer.canonicalText(offset));
        assertEquals(JdbcStagingTransfer.canonicalText(offset),JdbcStagingTransfer.canonicalText(offset.withOffsetSameInstant(java.time.ZoneOffset.ofHours(-5))));
        var date=Timestamp.valueOf("1999-12-31 23:59:59");when(row.getTimestamp(5)).thenReturn(date);
        assertEquals(date,JdbcStagingTransfer.readCell(row,5,type("DATE")));
    }
    @Test void sourceMetadataDoesNotConfuseBinaryWidthsOrTimezoneKinds() throws Exception {
        var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnCount()).thenReturn(1);
        for(var pair:List.of(new String[]{"BINARY_FLOAT","BINARY_FLOAT"},new String[]{"BINARY_DOUBLE","BINARY_DOUBLE"},new String[]{"TIMESTAMP_TZ","TIMESTAMP(6) WITH TIME ZONE"},new String[]{"NCHAR","NCHAR"},new String[]{"NCLOB","NCLOB"},new String[]{"CHAR","CHAR"},new String[]{"RAW","RAW"})) {
            var columns=List.of(new JdbcStagingTransfer.Column("VALUE","VALUE",type(pair[0])));
            when(metadata.getColumnTypeName(1)).thenReturn(pair[1]);
            assertDoesNotThrow(()->JdbcStagingTransfer.verifySourceMetadata(metadata,columns));
            when(metadata.getColumnTypeName(1)).thenReturn("NUMBER");
            assertThrows(JdbcStagingTransfer.TransferFailure.class,()->JdbcStagingTransfer.verifySourceMetadata(metadata,columns));
        }
        when(metadata.getColumnTypeName(1)).thenReturn("TIMESTAMP(6) WITH TIME ZONE");
        assertThrows(JdbcStagingTransfer.TransferFailure.class,()->JdbcStagingTransfer.verifySourceMetadata(metadata,
                List.of(new JdbcStagingTransfer.Column("VALUE","VALUE",type("TIMESTAMP")))));
    }
    @Test void copyEncodesByteaAsHexKeepsEmptyAndNullDistinctAndMatchesJdbcSeal() throws Exception {
        var source=mock(Connection.class);var stage=mock(Connection.class);var read=mock(PreparedStatement.class);var write=mock(PreparedStatement.class);
        var cursor=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);var pg=mock(PGConnection.class);var copy=mock(CopyManager.class);
        var names=List.of("NUMBER","BINARY_FLOAT","BINARY_DOUBLE","NCHAR","NCLOB","RAW","BLOB","BLOB","DATE","TIMESTAMP","TIMESTAMP_TZ");
        var producers=List.of("NUMBER","BINARY_FLOAT","BINARY_DOUBLE","NCHAR","NCLOB","RAW","BLOB","BLOB","DATE","TIMESTAMP(6)","TIMESTAMP(6) WITH TIME ZONE");
        var columns=new ArrayList<JdbcStagingTransfer.Column>();
        for(int i=0;i<names.size();i++)columns.add(new JdbcStagingTransfer.Column("C"+i,"C"+i,type(names.get(i))));
        when(source.prepareStatement(anyString())).thenReturn(read);when(stage.prepareStatement(anyString())).thenReturn(write);
        when(stage.unwrap(PGConnection.class)).thenReturn(pg);when(pg.getCopyAPI()).thenReturn(copy);
        when(read.executeQuery()).thenReturn(cursor);when(cursor.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(names.size());for(int i=0;i<producers.size();i++)when(metadata.getColumnTypeName(i+1)).thenReturn(producers.get(i));
        when(cursor.next()).thenReturn(true,false,true,false);
        var exact=new BigDecimal("99999999999999999999999999999999999999");
        when(cursor.getBigDecimal(1)).thenReturn(exact);when(cursor.getFloat(2)).thenReturn(1.2345678f);when(cursor.getDouble(3)).thenReturn(1e-100);
        when(cursor.getNString(4)).thenReturn("İ😀     ");
        var nclob=mock(NClob.class);when(cursor.getNClob(5)).thenReturn(nclob);
        when(nclob.getCharacterStream()).thenAnswer(call->new java.io.StringReader("Türkçe, \"satır\"\n\\N"));
        byte[] raw={0,127,(byte)128,(byte)255};when(cursor.getBytes(6)).thenReturn(raw);
        when(cursor.getBlob(7)).thenReturn(new javax.sql.rowset.serial.SerialBlob(new byte[0]));
        when(cursor.getTimestamp(9)).thenReturn(Timestamp.valueOf("1999-12-31 23:59:59"));
        when(cursor.getTimestamp(10)).thenReturn(Timestamp.valueOf("2020-02-29 23:59:59.123456"));
        var offset=OffsetDateTime.parse("2020-01-01T12:34:56.123456+03:00");when(cursor.getObject(11,OffsetDateTime.class)).thenReturn(offset);
        var csv=new StringBuilder();when(copy.copyIn(anyString(),any(InputStream.class))).thenAnswer(call->{var input=call.getArgument(1,InputStream.class);csv.append(new String(input.readAllBytes(),StandardCharsets.UTF_8));return 1L;});
        when(write.executeBatch()).thenReturn(new int[]{1});
        var options=new StagedMappingDefinition.Options(10,10,10,1_000_000,false);var from=new JdbcStagingTransfer.Table("SRC","ITEMS");var to=new JdbcStagingTransfer.Table("WORK","ITEMS");
        var copied=new PostgresCopyStagingTransfer().transfer(source,stage,from,to,columns,options,30,()->{},JdbcTransactionBoundary.direct(stage),JdbcStagingTransfer.QueryOptions.defaults());
        assertEquals("99999999999999999999999999999999999999,\"1.2345678\",\"1.0E-100\",\"İ😀     \",\"Türkçe, \"\"satır\"\"\n\\N\",\"\\x007f80ff\",\"\\x\",\\N,1999-12-31T23:59:59,2020-02-29T23:59:59.123456,\"2020-01-01T09:34:56.123456Z\"\n",csv.toString());
        var jdbc=new JdbcStagingTransfer().transfer(source,stage,from,to,columns,options,30,()->{});
        assertEquals(copied.payloadHash(),jdbc.payloadHash());assertEquals(copied.logicalBytes(),jdbc.logicalBytes());
        verify(write).setBigDecimal(1,exact);verify(write).setFloat(2,1.2345678f);verify(write).setDouble(3,1e-100);
        verify(write).setNString(4,"İ😀     ");verify(write).setNString(5,"Türkçe, \"satır\"\n\\N");
        verify(write).setBytes(6,raw);verify(write).setBytes(7,new byte[0]);verify(write).setNull(8,Types.BINARY);
        verify(write).setObject(11,offset,Types.TIMESTAMP_WITH_TIMEZONE);
    }
}

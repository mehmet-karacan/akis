package tr.com.innova.akis.knowledge;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Streams a single source cursor to an already-owned stage. Never touches the final target. */
public final class JdbcStagingTransfer {
    public static final long BUFFER_BYTES = 16L * 1024 * 1024;
    public enum Type { NUMBER, VARCHAR2, NVARCHAR2, DATE, TIMESTAMP }
    public record Column(String source, String stage, Type type) {
        public Column { StagedMappingDefinition.identifier(source); StagedMappingDefinition.identifier(stage); Objects.requireNonNull(type); }
    }
    public record Table(String owner, String name) {
        public Table { StagedMappingDefinition.identifier(owner); StagedMappingDefinition.identifier(name); }
        public String sql() { return quote(owner)+"."+quote(name); }
    }
    public record Result(long rows, long logicalBytes, String payloadHash) { }
    public static final class TransferFailure extends RuntimeException {
        private final boolean commitUncertain;
        TransferFailure(String message, boolean commitUncertain) { super(message); this.commitUncertain=commitUncertain; }
        public boolean commitUncertain() { return commitUncertain; }
    }
    public Result transfer(Connection source, Connection stage, Table from, Table to, List<Column> columns,
            StagedMappingDefinition.Options options, int timeoutSeconds, Runnable checkpoint) {
        return transfer(source, stage, from, to, columns, options, timeoutSeconds, checkpoint,
                JdbcTransactionBoundary.direct(stage));
    }
    public Result transfer(Connection source, Connection stage, Table from, Table to, List<Column> columns,
            StagedMappingDefinition.Options options, int timeoutSeconds, Runnable checkpoint,
            JdbcTransactionBoundary transaction) {
        Objects.requireNonNull(transaction);
        Objects.requireNonNull(checkpoint); Objects.requireNonNull(options);
        columns=List.copyOf(columns);
        if (columns.isEmpty() || columns.size()>256 || timeoutSeconds<1 || timeoutSeconds>3600
                || options.batchRows()<1 || options.batchRows()>5000 || options.fetchRows()<1 || options.fetchRows()>5000
                || options.maxRows()<1 || options.maxBytes()<1 || source==stage)
            throw new IllegalArgumentException("Aktarım sözleşmesi geçersiz.");
        long rows=0, bytes=0, batchBytes=0;
        int pending=0;
        boolean committing=false;
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        frame(digest,"AKIS_STAGE/1");
        for (var c:columns) { frame(digest,c.source()); frame(digest,c.stage()); frame(digest,c.type().name()); }
        String select="SELECT "+String.join(",",columns.stream().map(c->quote(c.source())).toList())+" FROM "+from.sql();
        String insert="INSERT INTO "+to.sql()+" ("+String.join(",",columns.stream().map(c->quote(c.stage())).toList())+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
        try {
            if (stage.getAutoCommit()) throw new IllegalArgumentException("Stage bağlantısında autocommit kapalı olmalıdır.");
            checkpoint.run();
            try (PreparedStatement read=source.prepareStatement(select); PreparedStatement write=stage.prepareStatement(insert)) {
                read.setFetchSize(options.fetchRows()); read.setQueryTimeout(timeoutSeconds); write.setQueryTimeout(timeoutSeconds);
                try (ResultSet cursor=read.executeQuery()) {
                    verifyMetadata(cursor.getMetaData(),columns);
                    while (cursor.next()) {
                        checkpoint.run();
                        if (rows>=options.maxRows()) throw new TransferFailure("Kaynak satır kotası aşıldı; stage mühürlenmedi.",false);
                        Object[] values=new Object[columns.size()];
                        long rowBytes=0;
                        for (int i=0;i<columns.size();i++) {
                            values[i]=read(cursor,i+1,columns.get(i).type());
                            String value=canonical(values[i]);
                            long cellBytes=value==null?4:4L+value.getBytes(StandardCharsets.UTF_8).length;
                            if (cellBytes>1_048_576) throw new TransferFailure("Tek hücre sınırı aşıldı.",false);
                            rowBytes+=cellBytes;
                        }
                        if (rowBytes>BUFFER_BYTES || bytes>options.maxBytes()-rowBytes) throw new TransferFailure("Aktarım byte kotası aşıldı; stage mühürlenmedi.",false);
                        if (pending>0 && batchBytes+rowBytes>BUFFER_BYTES) {
                            checkpoint.run(); execute(write,pending); checkpoint.run(); committing=true; transaction.commit(); committing=false;
                            pending=0; batchBytes=0;
                        }
                        for (int i=0;i<columns.size();i++) { bind(write,i+1,columns.get(i).type(),values[i]); frame(digest,canonical(values[i])); }
                        write.addBatch(); rows++; bytes+=rowBytes; batchBytes+=rowBytes; pending++;
                        if (pending==options.batchRows()) {
                            checkpoint.run(); execute(write,pending); checkpoint.run(); committing=true; transaction.commit(); committing=false;
                            pending=0; batchBytes=0;
                        }
                    }
                }
                if (rows==0 && !options.allowEmptySource()) throw new TransferFailure("Boş kaynakta hedef yenileme kapalı.",false);
                if (pending>0) { checkpoint.run(); execute(write,pending); checkpoint.run(); committing=true; transaction.commit(); committing=false; }
            }
            checkpoint.run();
            return new Result(rows,bytes,HexFormat.of().formatHex(digest.digest()));
        } catch (SQLException ex) {
            try { transaction.rollback(); } catch (SQLException | RuntimeException ignored) { }
            throw new TransferFailure(committing ? "Stage commit sonucu belirsiz; otomatik tekrar yasak." : "Stage aktarımı tamamlanamadı; hedef değiştirilmedi.",committing);
        } catch (RuntimeException ex) {
            try { transaction.rollback(); } catch (SQLException | RuntimeException ignored) { }
            if (committing) throw new TransferFailure("Stage commit sonucu belirsiz; otomatik tekrar yasak.",true);
            throw ex;
        }
    }
    private static void execute(PreparedStatement write,int expected) throws SQLException {
        int[] counts=write.executeBatch();
        if (counts.length!=expected || Arrays.stream(counts).anyMatch(n->n!=1)) throw new TransferFailure("Stage satır sayısı kesin doğrulanamadı.",false);
        write.clearBatch();
    }
    private static Object read(ResultSet r,int i,Type type) throws SQLException {
        return switch(type) { case NUMBER -> r.getBigDecimal(i); case VARCHAR2 -> r.getString(i); case NVARCHAR2 -> r.getNString(i); case DATE,TIMESTAMP -> r.getTimestamp(i); };
    }
    private static String canonical(Object value) {
        if (value==null) return null;
        if (value instanceof BigDecimal n) return n.signum()==0?"0":n.stripTrailingZeros().toPlainString();
        if (value instanceof Timestamp t) return t.toLocalDateTime().toString();
        return value.toString();
    }
    private static void bind(PreparedStatement s,int i,Type type,Object value) throws SQLException {
        if (value==null) { s.setNull(i,switch(type){case NUMBER->Types.NUMERIC;case VARCHAR2->Types.VARCHAR;case NVARCHAR2->Types.NVARCHAR;case DATE,TIMESTAMP->Types.TIMESTAMP;}); return; }
        switch(type) { case NUMBER -> s.setBigDecimal(i,(BigDecimal)value); case VARCHAR2 -> s.setString(i,(String)value); case NVARCHAR2 -> s.setNString(i,(String)value); case DATE,TIMESTAMP -> s.setTimestamp(i,(Timestamp)value); }
    }
    private static void verifyMetadata(ResultSetMetaData metadata,List<Column> columns) throws SQLException {
        if (metadata.getColumnCount()!=columns.size()) throw new TransferFailure("Kaynak kolonları değişmiş.",false);
        for (int i=0;i<columns.size();i++) {
            String type=metadata.getColumnTypeName(i+1).toUpperCase(Locale.ROOT);
            Type expected=columns.get(i).type();
            if (expected==Type.TIMESTAMP ? !type.matches("TIMESTAMP(\\([0-9]\\))?") : !type.equals(expected.name()))
                throw new TransferFailure("Kaynak kolon tipi desteklenmiyor veya değişmiş.",false);
        }
    }
    private static void frame(MessageDigest digest,String value) {
        byte[] bytes=value==null?new byte[0]:value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(value==null?-1:bytes.length).array()); digest.update(bytes);
    }
    private static String quote(String name) { return "\""+StagedMappingDefinition.identifier(name)+"\""; }
}

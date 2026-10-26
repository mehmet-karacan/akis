package tr.com.innova.akis.knowledge;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Streams a single source cursor to an already-owned stage. Never touches the final target. */
public final class JdbcStagingTransfer implements StagingTransferPort {
    public static final long BUFFER_BYTES = 16L * 1024 * 1024;
    /** Protects the worker from unbounded LOB allocation while allowing ordinary document payloads. */
    public static final long MAX_CELL_BYTES = 64L * 1024 * 1024;
    public enum Type { NUMBER, FLOAT, VARCHAR2, NVARCHAR2, DATE, TIMESTAMP, CLOB, BLOB }
    public record Column(String sourceObject, String source, String stage, Type type, JsonNode expression, boolean encrypted) {
        public Column(String sourceObject, String source, String stage, Type type, JsonNode expression) { this(sourceObject, source, stage, type, expression, false); }
        public Column(String sourceObject,String source,String stage,Type type) { this(sourceObject,source,stage,type,null); }
        /** Sensitive column: the value is encrypted in flight; only text columns can carry the ciphertext. */
        public Column protectedColumn() {
            if (type != Type.VARCHAR2 && type != Type.NVARCHAR2) throw new IllegalArgumentException("Yalnız metin kolonları şifrelenebilir: " + stage);
            return new Column(sourceObject, source, stage, type, expression, true);
        }
        public Column(String source, String stage, Type type) { this("SOURCE_1", source, stage, type); }
        public Column {
            if(expression==null) { StagedMappingDefinition.identifier(sourceObject); StagedMappingDefinition.identifier(source); }
            else { if(sourceObject!=null || source!=null) throw new IllegalArgumentException("İfade ve doğrudan kaynak aynı anda verilemez.");expression=expression.deepCopy(); }
            StagedMappingDefinition.identifier(stage); Objects.requireNonNull(type);
        }
        @Override public JsonNode expression() { return expression==null?null:expression.deepCopy(); }
    }
    public record Table(String owner, String name) {
        public Table { StagedMappingDefinition.identifier(owner); StagedMappingDefinition.identifier(name); }
        public String sql() { return quote(owner)+"."+quote(name); }
    }
    public record Result(long rows, long logicalBytes, String payloadHash) { }
    public record QuerySource(String object, String alias, Table table, Set<String> columns) {
        public QuerySource(String object,String alias,Table table) { this(object,alias,table,Set.of()); }
        public QuerySource { StagedMappingDefinition.identifier(object); StagedMappingDefinition.identifier(alias); Objects.requireNonNull(table);columns=Set.copyOf(columns);columns.forEach(StagedMappingDefinition::identifier); }
    }
    public record QueryOptions(boolean distinct, String oracleHint, List<QuerySource> sources,
            List<StagedMappingDefinition.Join> joins, List<StagedMappingDefinition.Filter> filters) {
        public QueryOptions(boolean distinct, String oracleHint) { this(distinct,oracleHint,List.of(),List.of(),List.of()); }
        public QueryOptions { oracleHint = safeHint(oracleHint); sources=List.copyOf(sources);joins=List.copyOf(joins);filters=List.copyOf(filters); }
        public static QueryOptions defaults() { return new QueryOptions(false, ""); }
    }
    public static final class TransferFailure extends RuntimeException {
        private final boolean commitUncertain;
        TransferFailure(String message, boolean commitUncertain) { super(message); this.commitUncertain=commitUncertain; }
        public boolean commitUncertain() { return commitUncertain; }
    }
    public Result transfer(Connection source, Connection stage, Table from, Table to, List<Column> columns,
            StagedMappingDefinition.Options options, int timeoutSeconds, Runnable checkpoint) {
        return transfer(source, stage, from, to, columns, options, timeoutSeconds, checkpoint,
                JdbcTransactionBoundary.direct(stage), QueryOptions.defaults());
    }
    public Result transfer(Connection source, Connection stage, Table from, Table to, List<Column> columns,
            StagedMappingDefinition.Options options, int timeoutSeconds, Runnable checkpoint,
            JdbcTransactionBoundary transaction) {
        return transfer(source,stage,from,to,columns,options,timeoutSeconds,checkpoint,transaction,QueryOptions.defaults());
    }
    @Override
    public Result transfer(Connection source, Connection stage, Table from, Table to, List<Column> columns,
            StagedMappingDefinition.Options options, int timeoutSeconds, Runnable checkpoint,
            JdbcTransactionBoundary transaction, QueryOptions queryOptions) {
        Objects.requireNonNull(transaction);
        Objects.requireNonNull(checkpoint); Objects.requireNonNull(options); Objects.requireNonNull(queryOptions);
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
        for (var c:columns) { frame(digest,c.sourceObject()); frame(digest,c.source()); frame(digest,c.stage()); frame(digest,c.type().name());if(c.expression()!=null) frame(digest,c.expression().toString()); }
        String hint=queryOptions.oracleHint().isBlank()?"":"/*+ "+queryOptions.oracleHint()+" */ ";
        QuerySql query=query(from,columns,queryOptions);
        String select="SELECT "+hint+(queryOptions.distinct()?"DISTINCT ":"")+query.columns()+" FROM "+query.from()+query.where();
        String insert="INSERT INTO "+to.sql()+" ("+String.join(",",columns.stream().map(c->quote(c.stage())).toList())+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
        try {
            if (stage.getAutoCommit()) throw new IllegalArgumentException("Stage bağlantısında autocommit kapalı olmalıdır.");
            checkpoint.run();
            try (PreparedStatement read=source.prepareStatement(select); PreparedStatement write=stage.prepareStatement(insert)) {
                for(int i=0;i<query.parameters().size();i++) {
                    Object value=query.parameters().get(i);
                    if(value instanceof BigDecimal number) read.setBigDecimal(i+1,number);
                    else read.setString(i+1,(String)value);
                }
                read.setFetchSize(options.fetchRows()); read.setQueryTimeout(timeoutSeconds); write.setQueryTimeout(timeoutSeconds);
                try (ResultSet cursor=read.executeQuery()) {
                    verifyMetadata(cursor.getMetaData(),columns);
                    while (cursor.next()) {
                        // Lease checkpoints happen per batch (below); one per row cost two control-plane round trips per row.
                        if (rows>=options.maxRows()) throw new TransferFailure("Kaynak satır kotası aşıldı; stage mühürlenmedi.",false);
                        Object[] values=new Object[columns.size()];
                        long rowBytes=0;
                        for (int i=0;i<columns.size();i++) {
                            values[i]=read(cursor,i+1,columns.get(i).type());
                            String value=canonical(values[i]);
                            long cellBytes=value==null?4:4L+value.getBytes(StandardCharsets.UTF_8).length;
                            if (cellBytes>MAX_CELL_BYTES || cellBytes>options.maxBytes())
                                throw new TransferFailure("Tek hücre aktarım kotası aşıldı; stage mühürlenmedi.",false);
                            rowBytes+=cellBytes;
                        }
                        if (rowBytes>BUFFER_BYTES || bytes>options.maxBytes()-rowBytes) throw new TransferFailure("Aktarım byte kotası aşıldı; stage mühürlenmedi.",false);
                        if (pending>0 && batchBytes+rowBytes>BUFFER_BYTES) {
                            checkpoint.run(); execute(write,pending); checkpoint.run(); committing=true; transaction.commit(); committing=false;
                            pending=0; batchBytes=0;
                        }
                        for (int i=0;i<columns.size();i++) {
                            if (columns.get(i).encrypted() && values[i]!=null) values[i]=tr.com.innova.akis.security.DataProtectionCipher.encrypt(String.valueOf(values[i]));
                            bind(write,i+1,columns.get(i).type(),values[i]); frame(digest,canonical(values[i]));
                        }
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
    private record QuerySql(String columns,String from,String where,List<Object> parameters) { }
    /** The Oracle source SELECT and its bind values; shared with staging adapters that write elsewhere (PostgreSQL COPY). */
    public record SourceQuery(String select, List<Object> parameters) { }
    public static SourceQuery sourceQuery(Table from, List<Column> columns, QueryOptions options) {
        String hint=options.oracleHint().isBlank()?"":"/*+ "+options.oracleHint()+" */ ";
        QuerySql query=query(from,List.copyOf(columns),options);
        return new SourceQuery("SELECT "+hint+(options.distinct()?"DISTINCT ":"")+query.columns()+" FROM "+query.from()+query.where(), query.parameters());
    }
    /** Seeds the seal digest with the transfer contract so two transfers of the same rows under different mappings differ. */
    public static MessageDigest sealDigest(List<Column> columns) {
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        frame(digest,"AKIS_STAGE/1");
        for (var c:columns) { frame(digest,c.sourceObject()); frame(digest,c.source()); frame(digest,c.stage()); frame(digest,c.type().name());if(c.expression()!=null) frame(digest,c.expression().toString()); }
        return digest;
    }
    public static Object readCell(ResultSet r,int i,Type type) throws SQLException { return read(r,i,type); }
    public static String canonicalText(Object value) { return canonical(value); }
    public static void frameValue(MessageDigest digest,String value) { frame(digest,value); }
    public static void verifySourceMetadata(ResultSetMetaData metadata,List<Column> columns) throws SQLException { verifyMetadata(metadata,columns); }
    public record SqlPreview(String select, String insert) { }
    /** Statement text only, for the pre-run report; binds stay as placeholders and nothing is executed. */
    public static SqlPreview previewSql(Table from, Table to, List<Column> columns, QueryOptions options) {
        if (from == null && options.sources().isEmpty()) throw new IllegalArgumentException("Kaynak tablosu gerekir.");
        if (columns.isEmpty()) throw new IllegalArgumentException("En az bir kolon eşlemesi gerekir.");
        String hint=options.oracleHint().isBlank()?"":"/*+ "+options.oracleHint()+" */ ";
        QuerySql query=query(from,List.copyOf(columns),options);
        String select="SELECT "+hint+(options.distinct()?"DISTINCT ":"")+query.columns()+" FROM "+query.from()+query.where();
        String insert="INSERT INTO "+to.sql()+" ("+String.join(",",columns.stream().map(c->quote(c.stage())).toList())+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
        return new SqlPreview(select, insert);
    }
    private static QuerySql query(Table fallback,List<Column> columns,QueryOptions options) {
        if(options.sources().isEmpty()) {
            if(!options.filters().isEmpty() || !options.joins().isEmpty()) throw new IllegalArgumentException("Filtre/join için açık kaynak sözleşmesi gerekir.");
            if(columns.stream().anyMatch(column->column.expression()!=null)) throw new IllegalArgumentException("SQL ifadeleri için sabitlenmiş kaynak kataloğu gerekir.");
            return new QuerySql(String.join(",",columns.stream().map(c->quote(c.source())).toList()),fallback.sql(),"",List.of());
        }
        Map<String,QuerySource> sources=new LinkedHashMap<>();
        options.sources().forEach(source->{if(sources.put(source.object(),source)!=null) throw new IllegalArgumentException("Kaynak kimliği tekrar edemez.");});
        if(options.sources().stream().map(QuerySource::alias).distinct().count()!=sources.size()) throw new IllegalArgumentException("Kaynak takma adları benzersiz olmalıdır.");
        for(var filter:options.filters()) if(!Set.of("SOURCE","GLOBAL").contains(filter.scope()) || !sources.containsKey(filter.object())) throw new IllegalArgumentException("Filtre kapsamı veya kaynağı geçersiz.");
        if(columns.stream().anyMatch(column->column.expression()==null && !sources.containsKey(column.sourceObject()))) throw new IllegalArgumentException("Kolon kaynağı sorgu sözleşmesinde yok.");
        var graph=StagedQueryGraph.compile(options.sources().stream().map(QuerySource::object).toList(),options.joins());
        QuerySource first=sources.get(graph.firstSource());
        List<Object> parameters=new ArrayList<>();
        var catalog=options.sources().stream().map(source->new MappingSql.Source(source.object(),source.alias(),source.columns())).toList();
        List<String> projections=new ArrayList<>();
        // SELECT placeholders precede FROM inline-view and final WHERE binds.
        for(Column column:columns) {
            if(column.expression()!=null) {
                var expression=MappingSql.render(column.expression(),catalog,false);
                projections.add(expression.sql());parameters.addAll(expression.parameters());
            } else projections.add(quote(sources.get(column.sourceObject()).alias())+"."+quote(column.source()));
        }
        StringBuilder from=new StringBuilder(sourceSql(first,options.filters(),parameters));
        for(var step:graph.steps()) {
            String keyword=switch(step.type()){case "INNER"->" JOIN ";case "LEFT"->" LEFT JOIN ";case "RIGHT"->" RIGHT JOIN ";case "FULL"->" FULL OUTER JOIN ";default->throw new IllegalArgumentException("Join türü geçersiz.");};
            from.append(keyword).append(sourceSql(sources.get(step.source()),options.filters(),parameters)).append(" ON ")
                    .append(String.join(" AND ",step.conditions().stream().map(join->reference(sources,join.left())+" = "+reference(sources,join.right())).toList()));
        }
        List<String> predicates=new ArrayList<>();
        for(var join:graph.remainingConditions()) predicates.add(reference(sources,join.left())+" = "+reference(sources,join.right()));
        for(var filter:options.filters()) if("GLOBAL".equals(filter.scope())) predicates.add(filterSql(sources.get(filter.object()),filter,parameters,catalog));
        String selectColumns=String.join(",",projections);
        return new QuerySql(selectColumns,from.toString(),predicates.isEmpty()?"":" WHERE "+String.join(" AND ",predicates),List.copyOf(parameters));
    }
    private static String sourceSql(QuerySource source,List<StagedMappingDefinition.Filter> filters,List<Object> parameters) {
        List<String> predicates=new ArrayList<>();
        var catalog=List.of(new MappingSql.Source(source.object(),source.alias(),source.columns()));
        for(var filter:filters) if("SOURCE".equals(filter.scope()) && source.object().equals(filter.object())) predicates.add(filterSql(source,filter,parameters,catalog));
        String table=source.table().sql()+" "+quote(source.alias());
        return predicates.isEmpty()?table:"(SELECT * FROM "+table+" WHERE "+String.join(" AND ",predicates)+") "+quote(source.alias());
    }
    private static String filterSql(QuerySource source,StagedMappingDefinition.Filter filter,List<Object> parameters,List<MappingSql.Source> catalog) {
        if(filter.predicate()!=null) {
            var predicate=MappingSql.render(filter.predicate(),catalog,true);
            parameters.addAll(predicate.parameters());
            return "("+predicate.sql()+")";
        }
        String ref=quote(source.alias())+"."+quote(filter.column());
        String predicate=switch(filter.operator()){case "EQUALS"->ref+" = ?";case "NOT_EQUALS"->ref+" <> ?";case "GREATER_THAN"->ref+" > ?";case "LESS_THAN"->ref+" < ?";case "LIKE"->ref+" LIKE ? ESCAPE '\\'";case "IS_NULL"->ref+" IS NULL";case "IS_NOT_NULL"->ref+" IS NOT NULL";default->throw new IllegalArgumentException("Filtre işleci geçersiz.");};
        if(!Set.of("IS_NULL","IS_NOT_NULL").contains(filter.operator())) {
            if(filter.value()==null) throw new IllegalArgumentException("Filtre değeri zorunludur.");
            parameters.add(filter.value());
        }
        return predicate;
    }
    private static String reference(Map<String,QuerySource> sources,StagedMappingDefinition.ColumnRef ref) { QuerySource source=sources.get(ref.object());if(source==null) throw new IllegalArgumentException("Join kaynağı bulunamadı.");return quote(source.alias())+"."+quote(ref.column()); }
    private static void execute(PreparedStatement write,int expected) throws SQLException {
        int[] counts=write.executeBatch();
        if (counts.length!=expected || Arrays.stream(counts).anyMatch(n->n!=1)) throw new TransferFailure("Stage satır sayısı kesin doğrulanamadı.",false);
        write.clearBatch();
    }
    private static Object read(ResultSet r,int i,Type type) throws SQLException {
        if (type==Type.FLOAT) { double value=r.getDouble(i); return r.wasNull()?null:value; }
        return switch(type) { case NUMBER -> r.getBigDecimal(i); case VARCHAR2,CLOB -> r.getString(i); case NVARCHAR2 -> r.getNString(i); case DATE,TIMESTAMP -> r.getTimestamp(i); case BLOB -> r.getBytes(i); case FLOAT -> throw new AssertionError(); };
    }
    private static String canonical(Object value) {
        if (value==null) return null;
        if (value instanceof BigDecimal n) return n.signum()==0?"0":n.stripTrailingZeros().toPlainString();
        if (value instanceof Double n) return Double.toString(n);
        if (value instanceof Timestamp t) return t.toLocalDateTime().toString();
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        return value.toString();
    }
    private static void bind(PreparedStatement s,int i,Type type,Object value) throws SQLException {
        if (value==null) { s.setNull(i,switch(type){case NUMBER->Types.NUMERIC;case FLOAT->Types.DOUBLE;case VARCHAR2->Types.VARCHAR;case NVARCHAR2->Types.NVARCHAR;case DATE,TIMESTAMP->Types.TIMESTAMP;case CLOB->Types.CLOB;case BLOB->Types.BINARY;}); return; }
        switch(type) { case NUMBER -> s.setBigDecimal(i,(BigDecimal)value); case FLOAT -> s.setDouble(i,(Double)value); case VARCHAR2 -> s.setString(i,(String)value); case NVARCHAR2 -> s.setNString(i,(String)value); case DATE,TIMESTAMP -> s.setTimestamp(i,(Timestamp)value); case CLOB -> s.setString(i,(String)value); case BLOB -> s.setBytes(i,(byte[])value); }
    }
    private static void verifyMetadata(ResultSetMetaData metadata,List<Column> columns) throws SQLException {
        if (metadata.getColumnCount()!=columns.size()) throw new TransferFailure("Kaynak kolonları değişmiş.",false);
        for (int i=0;i<columns.size();i++) {
            String type=metadata.getColumnTypeName(i+1).toUpperCase(Locale.ROOT);
            Type expected=columns.get(i).type();
            boolean matches=expected==Type.TIMESTAMP ? type.matches("TIMESTAMP(\\([0-9]\\))?")
                    : expected==Type.CLOB ? Set.of("CLOB","NCLOB").contains(type)
                    // Oracle JDBC exposes dictionary FLOAT columns as NUMBER in ResultSetMetaData.
                    : expected==Type.FLOAT ? Set.of("FLOAT","NUMBER","BINARY_FLOAT","BINARY_DOUBLE").contains(type)
                    : type.equals(expected.name());
            if (!matches) throw new TransferFailure("Kaynak kolon tipi desteklenmiyor veya değişmiş.",false);
        }
    }
    private static void frame(MessageDigest digest,String value) {
        byte[] bytes=value==null?new byte[0]:value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(value==null?-1:bytes.length).array()); digest.update(bytes);
    }
    private static String quote(String name) { return "\""+StagedMappingDefinition.identifier(name)+"\""; }
    public static String safeHint(String value) {
        String hint=value==null?"":value.strip();
        if(hint.length()>256 || !hint.matches("[A-Za-z0-9_$#., ()+\\-]*") || hint.contains("--") || hint.contains("/*") || hint.contains("*/"))
            throw new IllegalArgumentException("Oracle hint yalnız güvenli anahtar, sayı ve parametre karakterleri içerebilir.");
        return hint;
    }
}

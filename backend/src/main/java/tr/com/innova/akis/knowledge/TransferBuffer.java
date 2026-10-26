package tr.com.innova.akis.knowledge;

import java.lang.reflect.InvocationTargetException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;

/** One batch plus one row at a time; quotas count total data, this budget bounds resident payload copies. */
final class TransferBuffer {
    record Row(Object[] values,String[] canonical,long logicalBytes,long residentBytes) { }
    private final StagedMappingDefinition.Options options;
    private final Runnable checkpoint;
    private final long memoryBudget,readBudget,effectiveBudget;
    private long lobNanos,lobCells;
    private long lastCheckpoint,largestRow,projectedRow;
    private boolean unknownWidth;
    private final AdaptiveReadWindow adaptive;
    private long observedRows,gcMillis,lastGcSample;
    TransferBuffer(StagedMappingDefinition.Options options,Runnable checkpoint) {
        this.options=options;this.checkpoint=checkpoint;
        long heap=Runtime.getRuntime().maxMemory();
        effectiveBudget=options.automatic() && options.memoryBudgetBytes()!=null?Math.min(options.memoryBudgetBytes(),heap/4):0;
        if(options.automatic() && options.memoryBudgetBytes()!=null) {
            long usable=Math.min(options.memoryBudgetBytes(),heap/4);
            memoryBudget=Math.max(64*1024,Math.min(32L*1024*1024,usable/4));
            readBudget=Math.max(64*1024,usable/2);
        } else {
            memoryBudget=Math.max(64*1024,Math.min(4L*1024*1024,heap/64));
            readBudget=memoryBudget;
        }
        adaptive=options.automatic() && options.memoryBudgetBytes()!=null?new AdaptiveReadWindow(options.fetchRows()):null;
        gcMillis=collectionMillis();lastGcSample=System.nanoTime();
    }
    long memoryBudget() { return options.automatic()?memoryBudget:JdbcStagingTransfer.BUFFER_BYTES; }
    long requestedBudget() { return options.memoryBudgetBytes()==null?0:options.memoryBudgetBytes(); }
    long effectiveBudget() { return effectiveBudget; }
    long readBudget() { return readBudget; }
    long lobNanos() { return lobNanos; }
    long lobCells() { return lobCells; }
    int minFetchRows() {return adaptive==null?fetchRows():adaptive.minimum();}
    int maxFetchRows() {return adaptive==null?fetchRows():adaptive.maximum();}
    private static boolean lob(JdbcStagingTransfer.Type type) { return type==JdbcStagingTransfer.Type.CLOB || type==JdbcStagingTransfer.Type.NCLOB || type==JdbcStagingTransfer.Type.BLOB; }
    int configure(PreparedStatement read,List<JdbcStagingTransfer.Column> columns) throws SQLException {
        int fetch=options.automatic()?1:options.fetchRows();
        if(options.automatic()) {
            long lobs=columns.stream().filter(c->lob(c.type())).count();
            if(lobs>0) {
                configureLobPrefetch(read);
            }
        }
        read.setFetchSize(fetch);return fetch;
    }
    void project(ResultSetMetaData metadata,List<JdbcStagingTransfer.Column> columns) throws SQLException {
        if(!options.automatic()) return;
        long projected=0;unknownWidth=false;
        for(int i=0;i<columns.size();i++) {
            var column=columns.get(i);
            long width=switch(column.type()) {
                case CLOB,NCLOB,BLOB -> 2L*lobPrefetchBytes()+256;
                case VARCHAR2,NVARCHAR2,CHAR,NCHAR,RAW -> {
                    int display=metadata.getColumnDisplaySize(i+1);
                    if(display<=0)unknownWidth=true;
                    yield display>0?256+8L*display:memoryBudget;
                }
                default -> 512;
            };
            projected+=column.encrypted()?width*2:width;
        }
        projectedRow=Math.max(1,projected);
    }
    private int lobPrefetchBytes() { return options.memoryBudgetBytes()==null?4096:8192; }
    private void configureLobPrefetch(PreparedStatement statement) throws SQLException {
        try {
            Class<?> oracle=Class.forName("oracle.jdbc.OracleStatement");
            if(statement.isWrapperFor(oracle)) oracle.getMethod("setLobPrefetchSize",int.class).invoke(statement.unwrap(oracle),lobPrefetchBytes());
        } catch(ClassNotFoundException absent) {
            // Non-Oracle test sources do not carry this optional runtime driver.
        } catch(ReflectiveOperationException failure) {
            Throwable cause=failure instanceof InvocationTargetException wrapped?wrapped.getCause():failure;
            throw new SQLException("Oracle LOB ön-getirme sınırı uygulanamadı.",cause);
        }
    }
    void tick() {
        long now=System.nanoTime();
        if(now-lastCheckpoint>=250_000_000L) { checkpoint.run();lastCheckpoint=now; }
    }
    Row read(ResultSet cursor,List<JdbcStagingTransfer.Column> columns,long transferredBytes) throws SQLException {
        Object[] values=new Object[columns.size()];String[] texts=new String[columns.size()];
        long logical=0,resident=256L*columns.size();
        boolean boundedRow=options.automatic() && options.memoryBudgetBytes()!=null;
        for(int i=0;i<columns.size();i++) {
            tick();var column=columns.get(i);
            long allowance=boundedRow?Math.max(0,memoryBudget()-resident):Long.MAX_VALUE;
            if(column.encrypted() && boundedRow) allowance/=2;
            Object value;
            if(lob(column.type())) {
                long start=System.nanoTime();
                value=options.automatic()?BoundedLobReader.read(cursor,i+1,column.type(),this::tick,allowance)
                    :BoundedLobReader.readManual(cursor,i+1,column.type(),this::tick,
                        Math.min(options.maxBytes()-transferredBytes-logical,JdbcStagingTransfer.BUFFER_BYTES-logical));
                lobNanos+=System.nanoTime()-start;lobCells++;
            } else value=JdbcStagingTransfer.readCell(cursor,i+1,column.type());
            String canonical=JdbcStagingTransfer.canonicalText(value);
            long cell=canonical==null?4:4L+canonical.getBytes(StandardCharsets.UTF_8).length;
            if(cell>JdbcStagingTransfer.MAX_CELL_BYTES || cell>options.maxBytes()) throw new JdbcStagingTransfer.TransferFailure("Tek hücre aktarım kotası aşıldı; stage mühürlenmedi.",false);
            logical+=cell;
            if(logical>JdbcStagingTransfer.BUFFER_BYTES) throw new JdbcStagingTransfer.TransferFailure("Tek satır güvenli bellek sınırını aşıyor; stage mühürlenmedi.",false);
            if(transferredBytes>options.maxBytes()-logical) throw new JdbcStagingTransfer.TransferFailure("Aktarım byte kotası aşıldı; stage mühürlenmedi.",false);
            if(column.encrypted() && value!=null) { value=tr.com.innova.akis.security.DataProtectionCipher.encrypt(String.valueOf(value));canonical=String.valueOf(value); }
            values[i]=value;texts[i]=canonical;
            // Account for UTF-16 text, binary/driver references and encoding/COPY copies conservatively.
            resident+=canonical==null?16:128+8L*canonical.length();
            if(boundedRow && resident>memoryBudget())
                throw new JdbcStagingTransfer.TransferFailure("Tek satır yönetilen tampon bütçesini aşıyor; stage mühürlenmedi.",false);
        }
        largestRow=Math.max(largestRow,resident);
        return new Row(values,texts,logical,resident);
    }
    private int capacity() {
        if(unknownWidth || projectedRow==0)return 1;
        return (int)Math.max(1,Math.min(options.fetchRows(),readBudget/Math.max(1,Math.max(largestRow,projectedRow)*2)));
    }
    int fetchRows() {
        if(!options.automatic())return options.fetchRows();
        int capacity=capacity();return adaptive==null?capacity:adaptive.rows(capacity);
    }
    void observeRead(long nanos) {
        if(adaptive==null)return;
        boolean pressure=false;
        if(++observedRows%512==0) {
            long now=System.nanoTime(),gc=collectionMillis();
            var usage=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            pressure=(gc-gcMillis)*1_000_000L>(now-lastGcSample)/10 || usage.getUsed()>usage.getMax()*0.8;
            gcMillis=gc;lastGcSample=now;
        }
        adaptive.observe(nanos,capacity(),pressure);
    }
    private static long collectionMillis() {return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->Math.max(0,bean.getCollectionTime())).sum();}
}

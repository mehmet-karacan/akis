package tr.com.innova.akis.knowledge;

import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;

/** Confirmed staging batches only. Sampling does not turn every row into a metadata write. */
final class TransferTelemetry {
    private final Runnable checkpoint;
    private final LongSupplier clock;
    private final long started;
    private long lastReport,readNanos,writeNanos,commitNanos;
    private long queryNanos,fetchNanos,lobNanos,decodeNanos,encodeNanos,checkpointNanos;
    private long fetchCalls,rowsDecoded,lobCells,batchCount,requestedBudget,effectiveBudget,readWindow,writeWindow;
    private int fetchRows,minFetchRows,maxFetchRows;
    private final long initialGcCount=gcCount(),initialGcMillis=gcMillis();
    TransferTelemetry(Runnable checkpoint) { this(checkpoint,System::nanoTime); }
    TransferTelemetry(Runnable checkpoint,LongSupplier clock) {
        this.checkpoint=checkpoint;this.clock=clock;started=clock.getAsLong();lastReport=started;
    }
    void read(long nanos) { readNanos+=nanos; }
    void write(long nanos) { writeNanos+=nanos;batchCount++; }
    void commit(long nanos) { commitNanos+=nanos; }
    void query(long nanos) { queryNanos+=nanos; }
    void fetch(long nanos) { fetchNanos+=nanos;fetchCalls++; }
    void decode(long nanos,long lob,long cells) { lobNanos+=lob;decodeNanos+=Math.max(0,nanos-lob);rowsDecoded++;lobCells+=cells; }
    void encode(long nanos) { encodeNanos+=nanos; }
    Runnable checkpoint() { return ()->{long start=clock.getAsLong();try{checkpoint.run();}finally{checkpointNanos+=clock.getAsLong()-start;}}; }
    void window(long requested,long effective,long read,long write,int current,int minimum,int maximum) {
        requestedBudget=requested;effectiveBudget=effective;readWindow=read;writeWindow=write;
        fetchRows=current;minFetchRows=minimum;maxFetchRows=maximum;
    }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->Math.max(0,bean.getCollectionCount())).sum(); }
    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->Math.max(0,bean.getCollectionTime())).sum(); }
    void report(long rows,long bytes,boolean complete) {
        long now=clock.getAsLong();
        if(checkpoint instanceof TransferProgress.Listener listener && (complete || now-lastReport>=5_000_000_000L)) {
            var diagnostics=new TransferProgress.Diagnostics(queryNanos/1e9,fetchNanos/1e9,lobNanos/1e9,decodeNanos/1e9,
                    encodeNanos/1e9,checkpointNanos/1e9,fetchCalls,rowsDecoded,lobCells,
                    requestedBudget,effectiveBudget,readWindow,writeWindow,fetchRows,minFetchRows,maxFetchRows,batchCount,
                    Math.max(0,gcCount()-initialGcCount),Math.max(0,gcMillis()-initialGcMillis)/1000.0);
            listener.report(new TransferProgress(rows,bytes,(now-started)/1e9,readNanos/1e9,writeNanos/1e9,commitNanos/1e9,diagnostics));
            lastReport=now;
        }
    }
}

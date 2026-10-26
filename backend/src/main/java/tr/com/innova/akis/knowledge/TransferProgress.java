package tr.com.innova.akis.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Cumulative metrics for confirmed transfer commits. */
public record TransferProgress(long rows, long bytes, double elapsedSeconds, double readSeconds,
        double writeSeconds, double commitSeconds,
        @JsonInclude(JsonInclude.Include.NON_NULL) Diagnostics diagnostics) {
    public TransferProgress(long rows,long bytes,double elapsedSeconds,double readSeconds,double writeSeconds,double commitSeconds) {
        this(rows,bytes,elapsedSeconds,readSeconds,writeSeconds,commitSeconds,null);
    }
    /** Query/fetch/LOB/decode are disjoint read subphases; encode is outside read. Checkpoints and GC overlap wall time. */
    public record Diagnostics(double querySeconds,double fetchSeconds,double lobSeconds,double decodeSeconds,
            double encodeSeconds,double checkpointSeconds,long fetchCalls,long rowsDecoded,long lobCells,
            long requestedBudgetBytes,long effectiveBudgetBytes,long readWindowBytes,long writeWindowBytes,
            int fetchRows,int minFetchRows,int maxFetchRows,long batchCount,long gcCount,double gcSeconds) { }
    public interface Listener extends Runnable {
        void report(TransferProgress progress);
    }
}

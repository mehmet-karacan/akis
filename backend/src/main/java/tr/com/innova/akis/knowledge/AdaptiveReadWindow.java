package tr.com.innova.akis.knowledge;

/** Bounded single-reader hill climb. A plateau returns to the smallest measured useful window. */
final class AdaptiveReadWindow {
    private int current,best,minimum,maximum;
    private long sampleRows,sampleNanos;
    private double bestRate;
    private boolean settled;
    AdaptiveReadWindow(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("Okuma kapasitesi pozitif olmalıdır.");
        current=Math.min(256,capacity);best=current;minimum=Integer.MAX_VALUE;maximum=0;
    }
    int rows(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("Okuma kapasitesi pozitif olmalıdır.");
        if(current>capacity) {current=capacity;best=current;bestRate=0;sampleRows=sampleNanos=0;settled=false;}
        minimum=Math.min(minimum,current);maximum=Math.max(maximum,current);return current;
    }
    void observe(long readNanos,int capacity,boolean pressure) {
        rows(capacity);
        if(pressure) {
            current=Math.max(1,current/2);best=current;bestRate=0;sampleRows=sampleNanos=0;settled=false;rows(capacity);return;
        }
        if(settled)return;
        sampleRows++;sampleNanos+=Math.max(0,readNanos);
        if(sampleRows<Math.max(512,2*current) || sampleNanos<50_000_000L)return;
        double rate=sampleRows*1e9/sampleNanos;
        if(bestRate==0 || rate>bestRate*1.05) {
            best=current;bestRate=rate;int next=Math.min(capacity,current*2);
            if(next==current)settled=true;else current=next;
        } else {current=best;settled=true;}
        sampleRows=sampleNanos=0;rows(capacity);
    }
    int minimum() {return minimum;}
    int maximum() {return maximum;}
}

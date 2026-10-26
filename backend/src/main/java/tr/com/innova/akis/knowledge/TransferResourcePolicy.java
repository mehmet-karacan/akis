package tr.com.innova.akis.knowledge;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Operator-owned ceilings. Total transfer size is independent of the bounded in-memory batch. */
@Component
public final class TransferResourcePolicy {
    private final long maxRows,maxBytes;
    private final int batchRows,fetchRows;
    private final Semaphore slots,memory;
    private final int memoryUnits;
    private final long poolMiB;
    public TransferResourcePolicy(long maxRows,long maxBytes,int batchRows,int fetchRows,int maxConcurrent) {
        this(maxRows,maxBytes,batchRows,fetchRows,maxConcurrent,1024);
    }
    @Autowired
    public TransferResourcePolicy(
            @Value("${akis.execution.transfer.max-rows:100000000}") long maxRows,
            @Value("${akis.execution.transfer.max-bytes:1099511627776}") long maxBytes,
            @Value("${akis.execution.transfer.batch-rows:1000}") int batchRows,
            @Value("${akis.execution.transfer.fetch-rows:5000}") int fetchRows,
            @Value("${akis.execution.transfer.max-concurrent:1}") int maxConcurrent,
            @Value("${akis.execution.transfer.memory-pool-mib:1024}") long poolMiB) {
        new StagedMappingDefinition.Options(batchRows,fetchRows,maxRows,maxBytes,false,"AUTO");
        if(maxConcurrent<1 || maxConcurrent>32) throw new IllegalArgumentException("Eşzamanlı aktarım sınırı 1–32 olmalıdır.");
        this.maxRows=maxRows;this.maxBytes=maxBytes;this.batchRows=batchRows;this.fetchRows=fetchRows;
        this.slots=new Semaphore(maxConcurrent,true);
        this.poolMiB=TransferMemoryPolicy.validateMiB(poolMiB);
        this.memoryUnits=(int)Math.max(1,Math.min(poolMiB,Runtime.getRuntime().maxMemory()/4/TransferMemoryPolicy.MIB));
        this.memory=new Semaphore(memoryUnits,true);
    }
    public StagedMappingDefinition.Options resolve(StagedMappingDefinition.Options requested) {
        return resolve(requested,TransferMemoryPolicy.DEFAULT_MIB);
    }
    public StagedMappingDefinition.Options resolve(StagedMappingDefinition.Options requested,long memoryMiB) {
        long bytes=Math.min(TransferMemoryPolicy.validateMiB(memoryMiB),poolMiB)*TransferMemoryPolicy.MIB;
        return requested.automatic()?new StagedMappingDefinition.Options(batchRows,fetchRows,maxRows,maxBytes,requested.allowEmptySource(),"AUTO",bytes):requested;
    }
    public void validate(StagedMappingDefinition.Options pinned) {
        if(pinned.automatic() && (pinned.batchRows()>batchRows || pinned.fetchRows()>fetchRows
                || pinned.maxRows()>maxRows || pinned.maxBytes()>maxBytes
                || pinned.memoryBudgetBytes()!=null && pinned.memoryBudgetBytes()>poolMiB*TransferMemoryPolicy.MIB))
            throw new IllegalStateException("Sistem kaynak sınırları değişmiş; aktarımı yeniden yayınlayın.");
    }
    public WorkAreaPolicyService.Policy managed(WorkAreaPolicyService.Policy policy) {
        return new WorkAreaPolicyService.Policy(policy.enabled(),policy.allowSameSchema(),policy.maxObjects(),maxRows,maxBytes,policy.retentionHours());
    }
    public Permit acquire(Runnable checkpoint) {
        return acquire(null,checkpoint);
    }
    public Permit acquire(StagedMappingDefinition.Options options,Runnable checkpoint) {
        int units=options==null?0:(int)Math.min(memoryUnits,
                Math.max(1,((options.memoryBudgetBytes()==null?64L*TransferMemoryPolicy.MIB:options.memoryBudgetBytes())+TransferMemoryPolicy.MIB-1)/TransferMemoryPolicy.MIB));
        boolean slot=false,reserved=false,handedOff=false;
        try {
            checkpoint.run();
            while(!slots.tryAcquire(1,TimeUnit.SECONDS)) checkpoint.run();
            slot=true;
            while(!memory.tryAcquire(units,1,TimeUnit.SECONDS)) checkpoint.run();
            reserved=true;
            checkpoint.run();handedOff=true;return new Permit(slots,memory,units);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Aktarım sırası beklenirken durduruldu.");
        } finally {
            if(!handedOff) { if(reserved)memory.release(units);if(slot)slots.release(); }
        }
    }
    public static final class Permit implements AutoCloseable {
        private Semaphore slots;
        private final Semaphore memory;
        private final int units;
        private Permit(Semaphore slots,Semaphore memory,int units) { this.slots=slots;this.memory=memory;this.units=units; }
        @Override public synchronized void close() { if(slots!=null) { memory.release(units);slots.release();slots=null; } }
    }
}

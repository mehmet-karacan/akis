package tr.com.innova.akis.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferResourcePolicyTest {
    @Test void sharedMemoryAdmissionWaitsEvenWhenAConcurrencySlotIsFree() throws Exception {
        var policy=new TransferResourcePolicy(1000,10000000,1000,256,2);
        var options=policy.resolve(new StagedMappingDefinition.Options(1000,256,1000,10000000,false,"AUTO"));
        try(var first=policy.acquire(options,()->{});var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            var waiting=executor.submit(()->policy.acquire(options,()->{if(checks.incrementAndGet()>2)throw new IllegalStateException("memory occupied");}));
            assertThrows(java.util.concurrent.ExecutionException.class,()->waiting.get(4,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->{try(var next=policy.acquire(options,()->{})){assertNotNull(next);}});
    }
    @Test void automaticPublicationPinsDefaultOneGiBBudget() {
        var policy=new TransferResourcePolicy(1000,10000000,1000,256,1);
        var effective=policy.resolve(new StagedMappingDefinition.Options(1000,256,1000,10000000,false,"AUTO"));
        var json=new tools.jackson.databind.ObjectMapper().valueToTree(effective);
        assertEquals(1073741824L,json.path("memoryBudgetBytes").asLong());
    }
    @Test void automaticOptionsAreSelectedByTheServerNotByMappingNumbers() {
        var policy=new TransferResourcePolicy(5_000_000,32L*1024*1024*1024,750,128,1);
        var supplied=new StagedMappingDefinition.Options(1,1,1,1,false,"AUTO");
        var effective=policy.resolve(supplied);
        assertEquals(5_000_000,effective.maxRows());
        assertEquals(34_359_738_368L,effective.maxBytes());
        assertEquals(750,effective.batchRows());
        assertEquals(128,effective.fetchRows());
        assertFalse(effective.allowEmptySource());
        assertTrue(effective.automatic());
    }
    @Test void legacyLimitsAndWorkAreaPermissionsRemainExplicit() {
        var policy=new TransferResourcePolicy(5_000_000,32L*1024*1024*1024,750,128,1);
        var old=new StagedMappingDefinition.Options(10,20,100,2000,true);
        assertSame(old,policy.resolve(old));
        var work=policy.managed(new WorkAreaPolicyService.Policy(false,false,3,100,2000,48));
        assertFalse(work.enabled());assertFalse(work.allowSameSchema());
        assertEquals(3,work.maxObjects());assertEquals(48,work.retentionHours());
        assertEquals(5_000_000,work.maxRowsPerRun());assertEquals(34_359_738_368L,work.maxBytesPerRun());
    }
    @Test void publishedAutoFetchAndBatchCannotExceedReducedServerCeilings() {
        var policy=new TransferResourcePolicy(5_000_000,34_359_738_368L,100,8,1);
        assertThrows(IllegalStateException.class,()->policy.validate(new StagedMappingDefinition.Options(100,256,1000,10000,false,"AUTO")));
        assertThrows(IllegalStateException.class,()->policy.validate(new StagedMappingDefinition.Options(1000,8,1000,10000,false,"AUTO")));
        assertDoesNotThrow(()->policy.validate(new StagedMappingDefinition.Options(100,8,1000,10000,false,"AUTO")));
    }
    @Test void concurrencyPermitIsReleasedAfterFailureAndRepeatedClose() {
        var policy=new TransferResourcePolicy(100,2000,10,10,1);
        assertThrows(IllegalStateException.class,()->{try(var permit=policy.acquire(()->{})) {throw new IllegalStateException("work failed");}});
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->{
            var permit=policy.acquire(()->{});permit.close();permit.close();
            try(var next=policy.acquire(()->{})) {assertNotNull(next);}
        });
    }
    @Test void waitingTransferCanBeCancelledWithoutBorrowingAnActiveSlot() throws Exception {
        var policy=new TransferResourcePolicy(100,2000,10,10,1);
        try(var occupied=policy.acquire(()->{});var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            var waiting=executor.submit(()->policy.acquire(()->{if(checks.incrementAndGet()>1)throw new IllegalStateException("cancelled");}));
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->waiting.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("cancelled",failure.getCause().getMessage());
        }
    }
    @Test void repeatedPermitCloseDoesNotCreateAnExtraSlot() throws Exception {
        var policy=new TransferResourcePolicy(100,2000,10,10,1);
        var first=policy.acquire(()->{});first.close();first.close();
        try(var occupied=policy.acquire(()->{});var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            var waiting=executor.submit(()->policy.acquire(()->{if(checks.incrementAndGet()>1)throw new IllegalStateException("still occupied");}));
            assertThrows(java.util.concurrent.ExecutionException.class,()->waiting.get(3,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}

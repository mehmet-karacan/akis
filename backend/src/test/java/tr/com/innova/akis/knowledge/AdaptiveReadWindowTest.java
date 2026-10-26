package tr.com.innova.akis.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveReadWindowTest {
    @Test void recordsOnlyConfiguredWindowsAfterTheMetadataCapacityIsKnown() {
        var window=new AdaptiveReadWindow(5000);
        assertEquals(3,window.rows(3));assertEquals(3,window.minimum());assertEquals(3,window.maximum());
    }
    private static void observe(AdaptiveReadWindow window,int count,long nanosPerRow,int capacity,boolean pressure) {
        for(int i=0;i<count;i++)window.observe(nanosPerRow,capacity,pressure);
    }
    @Test void growsOnlyWhileLargerFetchActuallyImprovesThroughputThenReturnsToBest() {
        var window=new AdaptiveReadWindow(5000);
        assertEquals(256,window.rows(5000));
        observe(window,512,1_000_000,5000,false);assertEquals(512,window.rows(5000));
        observe(window,1024,500_000,5000,false);assertEquals(1024,window.rows(5000));
        observe(window,2048,500_000,5000,false);assertEquals(512,window.rows(5000));
        observe(window,20_000,500_000,5000,false);assertEquals(512,window.rows(5000));
    }
    @Test void plateauDoesNotConsumeLargerBuffersForNoGain() {
        var window=new AdaptiveReadWindow(5000);
        observe(window,512,1_000_000,5000,false);
        observe(window,1024,1_000_000,5000,false);
        assertEquals(256,window.rows(5000));
        observe(window,20_000,1_000_000,5000,false);assertEquals(256,window.rows(5000));
    }
    @Test void largeRowsAndGcPressureShrinkImmediatelyEvenAfterPlateau() {
        var window=new AdaptiveReadWindow(5000);
        observe(window,512,1_000_000,5000,false);assertEquals(512,window.rows(5000));
        assertEquals(3,window.rows(3));
        window.observe(1_000_000,3,true);assertEquals(1,window.rows(3));
        assertTrue(window.rows(5000)<=3);
    }
    @Test void aTinySafeCapacityIsNeverExceededAndInvalidCapacityIsRejected() {
        var window=new AdaptiveReadWindow(8);
        observe(window,10_000,1_000_000,8,false);assertEquals(8,window.rows(8));
        assertEquals(1,window.rows(1));assertThrows(IllegalArgumentException.class,()->new AdaptiveReadWindow(0));
    }
}

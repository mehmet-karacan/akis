package tr.com.innova.akis.knowledge;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class TransferTelemetryTest {
    @Test void oldProgressJsonRemainsReadableWithoutInventingDiagnostics() {
        var mapper=new ObjectMapper();
        var old=mapper.readValue("""
            {"rows":2,"bytes":30,"elapsedSeconds":9,"readSeconds":6,"writeSeconds":2,"commitSeconds":1}
            """,TransferProgress.class);
        assertNull(old.diagnostics());assertEquals(6,old.readSeconds());
        assertEquals(old,mapper.readValue(mapper.writeValueAsString(old),TransferProgress.class));
    }
    @Test void phaseTimesAreExclusiveAndRequestedBudgetIsNotConfusedWithEffectiveBudget() {
        var clock=new AtomicLong();var saved=new ArrayList<TransferProgress>();
        TransferProgress.Listener listener=new TransferProgress.Listener(){public void run(){clock.addAndGet(250_000_000L);}public void report(TransferProgress p){saved.add(p);}};
        var telemetry=new TransferTelemetry(listener,clock::get);
        telemetry.query(1_000_000_000L);telemetry.fetch(2_000_000_000L);
        telemetry.decode(3_000_000_000L,2_000_000_000L,2);
        telemetry.encode(500_000_000L);telemetry.checkpoint().run();
        telemetry.read(6_000_000_000L);telemetry.write(1_000_000_000L);telemetry.commit(100_000_000L);
        telemetry.window(5L*1024*1024*1024,2L*1024*1024*1024,1024,512,128,64,256);
        telemetry.report(1,50,true);
        var p=saved.getLast();var d=p.diagnostics();
        assertEquals(1,d.querySeconds());assertEquals(2,d.fetchSeconds());
        assertEquals(2,d.lobSeconds());assertEquals(1,d.decodeSeconds());
        assertEquals(.5,d.encodeSeconds());assertEquals(.25,d.checkpointSeconds());
        assertEquals(6,p.readSeconds());assertEquals(1,p.writeSeconds());assertEquals(.1,p.commitSeconds());
        assertEquals(1,d.fetchCalls());assertEquals(1,d.rowsDecoded());assertEquals(2,d.lobCells());
        assertEquals(5368709120L,d.requestedBudgetBytes());assertEquals(2147483648L,d.effectiveBudgetBytes());
        assertEquals(128,d.fetchRows());assertEquals(64,d.minFetchRows());assertEquals(256,d.maxFetchRows());
        var mapper=new ObjectMapper();assertEquals(p,mapper.readValue(mapper.writeValueAsString(p),TransferProgress.class));
    }
    @Test void intermediateReportsAreThrottledAndCompletionIsAlwaysPersisted() {
        var clock=new AtomicLong();var saved=new ArrayList<TransferProgress>();
        TransferProgress.Listener listener=new TransferProgress.Listener(){public void run(){}public void report(TransferProgress p){saved.add(p);}};
        var telemetry=new TransferTelemetry(listener,clock::get);
        telemetry.report(1,20,false);assertTrue(saved.isEmpty());
        clock.set(5_000_000_000L);telemetry.report(2,40,false);
        telemetry.report(3,60,false);assertEquals(1,saved.size());
        telemetry.report(3,60,true);assertEquals(2,saved.size());assertEquals(3,saved.getLast().rows());
    }
}

package tr.com.innova.akis.knowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.knowledge.AkisKmLanguage.*;

class AkisKmInterpreterTest {
    private AkisKmInterpreter.Modules modules() {
        return new AkisKmInterpreter.Modules(example(Kind.LKM), example(Kind.CKM), example(Kind.IKM));
    }
    private static class RecordingRuntime implements AkisKmInterpreter.Runtime {
        final List<String> calls = new ArrayList<>();
        boolean rejectCheck;
        public void verify(AkisKmInterpreter.Plan plan) { calls.add("verify"); }
        public void createWork(String slot) { calls.add("create"); }
        public long transferJdbc(String slot) { calls.add("transfer"); return 42; }
        public void sealWork(String slot, long rows) { assertEquals(42, rows); calls.add("seal"); }
        public void checkNotNull(String slot) {
            calls.add("check");
            if (rejectCheck) throw new IllegalStateException("CKM failed");
        }
        public void checkUnique(String slot) { calls.add("unique"); }
        public long atomicReplace(String slot) { calls.add("publish"); return 42; }
    }
    @Test void interpretsInDependencyOrder() {
        var runtime = new RecordingRuntime();
        var results = AkisKmInterpreter.execute(modules(), runtime);
        assertEquals(List.of("verify", "create", "transfer", "seal", "check", "publish"), runtime.calls);
        assertEquals(42, results.getLast().affectedRows());
    }
    @Test void validatesEntireProgramBeforeFirstEffect() {
        var runtime = new RecordingRuntime();
        var invalid = new AkisKmInterpreter.Modules(example(Kind.LKM), null,
                example(Kind.IKM).replace("WORK_SOURCE_1", "UNKNOWN"));
        assertThrows(SyntaxException.class, () -> AkisKmInterpreter.execute(invalid, runtime));
        assertTrue(runtime.calls.isEmpty());
    }
    @Test void failedCheckNeverPublishesOrSilentlyCleans() {
        var runtime = new RecordingRuntime();
        runtime.rejectCheck = true;
        assertThrows(IllegalStateException.class, () -> AkisKmInterpreter.execute(modules(), runtime));
        assertEquals(List.of("verify", "create", "transfer", "seal", "check"), runtime.calls);
    }
    @Test void requiresCorrectModuleRoles() {
        assertThrows(SyntaxException.class, () -> AkisKmInterpreter.compile(
                new AkisKmInterpreter.Modules(example(Kind.IKM), null, example(Kind.LKM))));
    }
}

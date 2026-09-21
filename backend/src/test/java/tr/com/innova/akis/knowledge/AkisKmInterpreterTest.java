package tr.com.innova.akis.knowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.knowledge.AkisKmLanguage.*;

class AkisKmInterpreterTest {
    private static final String CONDITIONAL_CHECK = """
            AKIS_KM/2
            MODUL CKM
            SECENEK CHECK_REQUIRED BOOLEAN ISTEGE_BAGLI true YOK
            ADIM CHECK_ROWS STAGING CHECK_NOT_NULL WORK_SOURCE_1 EGER CHECK_REQUIRED
            """;

    @Test void customBooleanControlsActualExecutionAndDefaultsToDeclaredValue() {
        for (var values : List.of(Map.<String, Object>of(), Map.<String, Object>of("CHECK_REQUIRED", true),
                Map.<String, Object>of("CHECK_REQUIRED", false))) {
            var runtime = new RecordingRuntime();
            var modules = new AkisKmInterpreter.Modules(example(Kind.LKM), CONDITIONAL_CHECK, example(Kind.IKM),
                    Map.of("checking", values, "loading", Map.of("CHECK_REQUIRED", false)));
            var plan = AkisKmInterpreter.compile(modules);
            var results = AkisKmInterpreter.execute(modules, runtime);
            boolean enabled = !Boolean.FALSE.equals(values.get("CHECK_REQUIRED"));
            assertEquals(enabled, runtime.calls.contains("check"));
            assertEquals(enabled ? 5 : 4, plan.steps().size());
            assertEquals(plan.steps().stream().map(Step::id).toList(), results.stream().map(AkisKmInterpreter.StepResult::id).toList());
            assertEquals("publish", runtime.calls.getLast());
        }
    }

    @Test void missingOrWrongTypedConditionFailsBeforeRuntimeVerification() {
        for (var values : List.of(Map.<String, Object>of(), Map.<String, Object>of("CHECK_REQUIRED", "false"))) {
            var runtime = new RecordingRuntime();
            var modules = new AkisKmInterpreter.Modules(example(Kind.LKM),
                    CONDITIONAL_CHECK.replace("true YOK", "YOK YOK"), example(Kind.IKM), Map.of("checking", values));
            assertThrows(SyntaxException.class, () -> AkisKmInterpreter.execute(modules, runtime));
            assertTrue(runtime.calls.isEmpty());
        }
    }

    @Test void falseConditionsCannotHideBrokenDependenciesOrUnknownSlots() {
        String loading = example(Kind.LKM).replace("MODUL LKM", "MODUL LKM\nSECENEK LOAD BOOLEAN ISTEGE_BAGLI false YOK")
                .replace("TRANSFER_JDBC WORK_SOURCE_1", "TRANSFER_JDBC WORK_SOURCE_1 EGER LOAD");
        String integration = example(Kind.IKM).replace("MODUL IKM", "MODUL IKM\nSECENEK WRITE BOOLEAN ISTEGE_BAGLI false YOK")
                .replace("ATOMIC_REPLACE WORK_SOURCE_1", "ATOMIC_REPLACE WORK_SOURCE_1 EGER WRITE");
        for (var modules : List.of(
                new AkisKmInterpreter.Modules(loading, null, example(Kind.IKM)),
                new AkisKmInterpreter.Modules(example(Kind.LKM), null, integration),
                new AkisKmInterpreter.Modules(example(Kind.LKM), CONDITIONAL_CHECK.replace("WORK_SOURCE_1", "UNKNOWN").replace("true YOK", "false YOK"), example(Kind.IKM)))) {
            var runtime = new RecordingRuntime();
            assertThrows(SyntaxException.class, () -> AkisKmInterpreter.execute(modules, runtime));
            assertTrue(runtime.calls.isEmpty());
        }
    }

    @Test void conditionMustBeDeclaredBooleanInTheSameVersionTwoModule() {
        for (String source : List.of(CONDITIONAL_CHECK.replace("BOOLEAN", "STRING"),
                CONDITIONAL_CHECK.replace("EGER CHECK_REQUIRED", "EGER UNKNOWN"),
                CONDITIONAL_CHECK.replace("EGER CHECK_REQUIRED", "IF CHECK_REQUIRED"),
                CONDITIONAL_CHECK.replace("EGER CHECK_REQUIRED", "EGER"),
                CONDITIONAL_CHECK.replace("AKIS_KM/2", "AKIS_KM/1"))) {
            assertThrows(SyntaxException.class, () -> parse(source));
        }
        assertEquals(Map.of("CHECK_ROWS", "CHECK_REQUIRED"), parse(CONDITIONAL_CHECK).conditions());
    }

    @Test void moduleOptionsAreSnapshottedBeforeExecution() {
        var values = new HashMap<String, Object>(); values.put("CHECK_REQUIRED", false);
        var options = new HashMap<String, Map<String, Object>>(); options.put("checking", values);
        var modules = new AkisKmInterpreter.Modules(example(Kind.LKM), CONDITIONAL_CHECK, example(Kind.IKM), options);
        values.put("CHECK_REQUIRED", true); options.clear();
        assertEquals(4, AkisKmInterpreter.compile(modules).steps().size());
        assertThrows(UnsupportedOperationException.class, () -> modules.options().get("checking").put("CHECK_REQUIRED", true));
    }
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

    @Test void resumeSkipsAdoptedStepsAndReportsThemWithTheSealedRowCount() {
        var runtime = new RecordingRuntime();
        var modules = new AkisKmInterpreter.Modules(example(Kind.LKM), CONDITIONAL_CHECK, example(Kind.IKM), Map.of("checking", Map.of("CHECK_REQUIRED", true)));
        var plan = AkisKmInterpreter.compile(modules);
        int sealIndex = plan.steps().stream().map(Step::operation).toList().indexOf(Operation.SEAL_WORK);
        List<String> skipped = new ArrayList<>();
        var observer = new AkisKmInterpreter.Observer() {
            public void skipped(int ordinal, Step step, long rows) { skipped.add(ordinal + ":" + step.operation() + ":" + rows); }
        };
        var results = AkisKmInterpreter.execute(modules, runtime, observer, new AkisKmInterpreter.Resume(sealIndex + 1, 42));
        assertEquals(List.of("verify", "check", "publish"), runtime.calls);
        assertEquals(sealIndex + 1, skipped.size());
        assertEquals("2:TRANSFER_JDBC:42", skipped.get(1));
        assertEquals(plan.steps().size(), results.size());
        assertEquals(42, results.get(1).affectedRows());
    }

    @Test void resumeRejectsPointsBeforeTheSealOrPastThePublication() {
        var modules = new AkisKmInterpreter.Modules(example(Kind.LKM), null, example(Kind.IKM));
        int steps = AkisKmInterpreter.compile(modules).steps().size();
        for (int point : List.of(1, steps)) {
            var runtime = new RecordingRuntime();
            assertThrows(IllegalStateException.class, () -> AkisKmInterpreter.execute(modules, runtime, new AkisKmInterpreter.Observer() { }, new AkisKmInterpreter.Resume(point, 1)));
            assertEquals(List.of("verify"), runtime.calls);
        }
    }
}

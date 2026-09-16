package tr.com.innova.akis.knowledge;

import java.util.*;
import static tr.com.innova.akis.knowledge.AkisKmLanguage.*;

/** Finite operation dispatcher. Database effects belong to a fenced runtime adapter, never DSL eval. */
public final class AkisKmInterpreter {
    public record Modules(String loading, String checking, String integration) { }
    public record Plan(List<Step> steps, Set<String> slots) {
        public Plan { steps = List.copyOf(steps); slots = Set.copyOf(slots); }
    }
    public record StepResult(String id, Operation operation, String slot, long affectedRows) { }
    public interface Observer {
        default void before(int ordinal,Step step) { }
        default void succeeded(int ordinal,StepResult result) { }
        default void failed(int ordinal,Step step,RuntimeException failure) { }
    }
    public interface Runtime {
        /** Verify published plan, ownership and fencing before performing any effect. */
        void verify(Plan plan);
        void createWork(String slot);
        long transferJdbc(String slot);
        void sealWork(String slot, long transferredRows);
        void checkNotNull(String slot);
        void checkUnique(String slot);
        /** Target DML and existing target ledger must commit in the same transaction. */
        long atomicReplace(String slot);
    }
    private AkisKmInterpreter() { }

    public static Plan compile(Modules modules) {
        Objects.requireNonNull(modules, "modules");
        Program loading = requireKind(modules.loading(), Kind.LKM);
        Program integration = requireKind(modules.integration(), Kind.IKM);
        Program checking = modules.checking() == null ? null : requireKind(modules.checking(), Kind.CKM);
        Set<String> slots = new LinkedHashSet<>();
        loading.steps().forEach(step -> slots.add(step.slot()));
        List<Step> steps = new ArrayList<>(loading.steps());
        if (checking != null) steps.addAll(checking.steps());
        steps.addAll(integration.steps());
        for (Step step : steps) {
            if (!slots.contains(step.slot())) throw new SyntaxException(step.line(), "Yüklenmemiş çalışma slotu: " + step.slot());
        }
        return new Plan(steps, slots);
    }

    private static Program requireKind(String source, Kind kind) {
        Program program = parse(source);
        if (program.kind() != kind) throw new SyntaxException(1, "Beklenen modül türü: " + kind);
        return program;
    }

    /** Parse all modules before the first effect. A failure stops execution; no blind cleanup/retry. */
    public static List<StepResult> execute(Modules modules, Runtime runtime) {
        return execute(modules,runtime,new Observer() { });
    }
    public static List<StepResult> execute(Modules modules, Runtime runtime,Observer observer) {
        Plan plan = compile(modules);
        Objects.requireNonNull(observer);
        Objects.requireNonNull(runtime, "runtime").verify(plan);
        Map<String, Long> transferred = new HashMap<>();
        List<StepResult> results = new ArrayList<>();
        for (Step step : plan.steps()) {
            int ordinal=results.size()+1;
            observer.before(ordinal,step);
            try {
            long rows = 0;
            switch (step.operation()) {
                case CREATE_WORK -> runtime.createWork(step.slot());
                case TRANSFER_JDBC -> {
                    rows = runtime.transferJdbc(step.slot());
                    if (rows < 0) throw new IllegalStateException("Aktarım satır sayısı negatif olamaz.");
                    transferred.put(step.slot(), rows);
                }
                case SEAL_WORK -> runtime.sealWork(step.slot(), transferred.get(step.slot()));
                case CHECK_NOT_NULL -> runtime.checkNotNull(step.slot());
                case CHECK_UNIQUE -> runtime.checkUnique(step.slot());
                case ATOMIC_REPLACE -> rows = runtime.atomicReplace(step.slot());
            }
            var result=new StepResult(step.id(), step.operation(), step.slot(), rows);
            observer.succeeded(ordinal,result);
            results.add(result);
            } catch(RuntimeException failure) {
                try { observer.failed(ordinal,step,failure); } catch(RuntimeException ignored) { }
                throw failure;
            }
        }
        return List.copyOf(results);
    }
}

package tr.com.innova.akis.knowledge;

import java.util.*;
import static tr.com.innova.akis.knowledge.AkisKmLanguage.*;

/** Finite operation dispatcher. Database effects belong to a fenced runtime adapter, never DSL eval. */
public final class AkisKmInterpreter {
    public record Modules(String loading, String checking, String integration, Map<String, Map<String, Object>> options) {
        public Modules(String loading, String checking, String integration) { this(loading, checking, integration, Map.of()); }
        public Modules {
            Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
            options.forEach((role, values) -> copy.put(role, Map.copyOf(values)));
            options = Map.copyOf(copy);
        }
    }
    public record Plan(List<Step> steps, Set<String> slots) {
        public Plan { steps = List.copyOf(steps); slots = Set.copyOf(slots); }
    }
    public record StepResult(String id, Operation operation, String slot, long affectedRows) { }
    /** RESUME: the first {@code completedSteps} plan steps already succeeded in a previous attempt whose sealed work table is adopted. */
    public record Resume(int completedSteps, long transferredRows) {
        public static final Resume NONE = new Resume(0, 0);
        public Resume { if (completedSteps < 0 || transferredRows < 0) throw new IllegalArgumentException("Devam noktası geçersiz."); }
    }
    public interface Observer {
        default void before(int ordinal,Step step) { }
        default void skipped(int ordinal,Step step,long transferredRows) { }
        default void succeeded(int ordinal,StepResult result) { }
        default void failed(int ordinal,Step step,RuntimeException failure) { }
    }
    public interface Runtime {
        /** Verify published plan, ownership and fencing before performing any effect. */
        void verify(Plan plan);
        default void dropWorkIfExists(String slot) { }
        void createWork(String slot);
        long transferJdbc(String slot);
        void sealWork(String slot, long transferredRows);
        void checkNotNull(String slot);
        void checkUnique(String slot);
        /** Target DML and existing target ledger must commit in the same transaction. */
        long atomicReplace(String slot);
        /** Drop the sealed work table only after target publication is durably committed. */
        void dropWork(String slot);
    }
    private AkisKmInterpreter() { }

    public static Plan compile(Modules modules) {
        Objects.requireNonNull(modules, "modules");
        Program loading = requireKind(modules.loading(), Kind.LKM);
        Program integration = requireKind(modules.integration(), Kind.IKM);
        Program checking = modules.checking() == null ? null : requireKind(modules.checking(), Kind.CKM);
        Set<String> declaredSlots = new LinkedHashSet<>();
        loading.steps().forEach(step -> declaredSlots.add(step.slot()));
        List<Step> declared = new ArrayList<>(loading.steps());
        if (checking != null) declared.addAll(checking.steps());
        declared.addAll(integration.steps());
        for (Step step : declared) {
            if (!declaredSlots.contains(step.slot())) throw new SyntaxException(step.line(), "Yüklenmemiş çalışma slotu: " + step.slot());
        }
        List<Step> loadSteps = enabled(loading, modules.options().getOrDefault("loading", Map.of()));
        List<Step> integrateSteps = enabled(integration, modules.options().getOrDefault("integration", Map.of()));
        AkisKmLanguage.validateSequence(Kind.LKM, loadSteps);
        AkisKmLanguage.validateSequence(Kind.IKM, integrateSteps);
        Set<String> slots = new LinkedHashSet<>();
        loadSteps.forEach(step -> slots.add(step.slot()));
        List<Step> steps = new ArrayList<>(loadSteps);
        if (checking != null) steps.addAll(enabled(checking, modules.options().getOrDefault("checking", Map.of())));
        steps.addAll(integrateSteps);
        for (Step step : steps) {
            if (!slots.contains(step.slot())) throw new SyntaxException(step.line(), "Yüklenmemiş çalışma slotu: " + step.slot());
        }
        return new Plan(steps, slots);
    }

    /** Conditions are resolved from the module's pinned values, not global keys
     * or mutable definitions. The resulting effective steps are hashed in the
     * physical plan and recompiled independently before any database effect.
     */
    private static List<Step> enabled(Program program, Map<String, Object> values) {
        return program.steps().stream().filter(step -> {
            String key = program.conditions().get(step.id());
            if (key == null) return true;
            Object value = values.get(key);
            if (value == null) {
                var option = program.options().stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElseThrow();
                if (option.defaultValue() != null) value = Boolean.valueOf(option.defaultValue());
            }
            if (!(value instanceof Boolean decision)) throw new SyntaxException(step.line(), "Adım koşulu için Boolean seçenek değeri zorunludur: " + key);
            return decision;
        }).toList();
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
        return execute(modules,runtime,observer,Resume.NONE);
    }
    public static List<StepResult> execute(Modules modules, Runtime runtime,Observer observer,Resume resume) {
        Plan plan = compile(modules);
        Objects.requireNonNull(observer); Objects.requireNonNull(resume);
        Objects.requireNonNull(runtime, "runtime").verify(plan);
        Map<String, Long> transferred = new HashMap<>();
        List<StepResult> results = new ArrayList<>();
        if (resume.completedSteps() > 0) {
            // Skipping is only sound past SEAL_WORK: nothing before it leaves a durable effect to adopt.
            List<Step> skipped = plan.steps().subList(0, Math.min(resume.completedSteps(), plan.steps().size()));
            if (skipped.stream().noneMatch(step -> step.operation() == Operation.SEAL_WORK)
                    || skipped.stream().anyMatch(step -> step.operation() == Operation.ATOMIC_REPLACE))
                throw new IllegalStateException("Devam noktası mühürlenmiş çalışma tablosundan sonra ve yayından önce olmalıdır.");
            for (Step step : skipped) {
                int ordinal=results.size()+1;
                long rows = step.operation() == Operation.TRANSFER_JDBC ? resume.transferredRows() : 0;
                if (step.operation() == Operation.TRANSFER_JDBC) transferred.put(step.slot(), rows);
                observer.skipped(ordinal,step,resume.transferredRows());
                results.add(new StepResult(step.id(), step.operation(), step.slot(), rows));
            }
        }
        for (Step step : plan.steps().subList(results.size(), plan.steps().size())) {
            int ordinal=results.size()+1;
            observer.before(ordinal,step);
            try {
            long rows = 0;
            switch (step.operation()) {
                case DROP_WORK_IF_EXISTS -> runtime.dropWorkIfExists(step.slot());
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
                case DROP_WORK -> runtime.dropWork(step.slot());
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

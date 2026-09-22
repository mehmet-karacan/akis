package tr.com.innova.akis.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;

/** Target technology bundles by provider; a plan whose target technology has no bundle cannot run. */
@Component
final class TargetTechnologyRegistry {
    private final Map<DatabaseType, TargetTechnology> technologies;

    TargetTechnologyRegistry(List<TargetTechnology> technologies) {
        Map<DatabaseType, TargetTechnology> byType = new LinkedHashMap<>();
        for (TargetTechnology technology : technologies) byType.put(technology.technology(), technology);
        this.technologies = Map.copyOf(byType);
    }

    TargetTechnology of(DatabaseType technology) {
        TargetTechnology bundle = technology == null ? null : technologies.get(technology);
        if (bundle == null) throw new IllegalStateException("Hedef teknolojisi çalıştırılamıyor: " + technology);
        return bundle;
    }

    TargetTechnology of(StagedRuntimePlan plan) { return of(plan.target().databaseType()); }
}

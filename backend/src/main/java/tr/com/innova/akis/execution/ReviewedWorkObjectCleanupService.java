package tr.com.innova.akis.execution;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tr.com.innova.akis.knowledge.WorkObjectStore;

@Service
public final class ReviewedWorkObjectCleanupService {
    private final PinnedExecutionContextPort contexts;
    private final StagedRuntimePlanResolver plans;
    private final StagedWorkSessionFactory sessions;
    private final TargetTechnologyRegistry targets;
    private final WorkObjectStore objects;

    ReviewedWorkObjectCleanupService(PinnedExecutionContextPort contexts,StagedRuntimePlanResolver plans,
            StagedWorkSessionFactory sessions,TargetTechnologyRegistry targets,WorkObjectStore objects) {
        this.contexts=contexts;this.plans=plans;this.sessions=sessions;this.targets=targets;this.objects=objects;
    }

    public void cleanup(UUID projectUuid,UUID runUuid,UUID objectUuid) {
        var context=contexts.find(runUuid).orElseThrow(()->new IllegalStateException("Çalıştırma bağlamı bulunamadı."));
        var plan=plans.resolve(context.releaseHash(),context.planHash(),context.scenarioPlan(),context.physicalManifest());
        if(!projectUuid.equals(plan.projectUuid())) throw new IllegalStateException("Çalıştırma proje kapsamıyla uyuşmuyor.");
        var technology=targets.of(plan);
        try(var session=sessions.open(plan,true)) {
            technology.workTables(objects).cleanupReviewed(session.connection(),projectUuid,runUuid,objectUuid,30);
        }
    }
}

package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.RUN_START;

/** Explicit reconciliation reads committed ledger evidence; it does not replay the mapping. */
@RestController
@ConditionalOnProperty(name="akis.execution.staged-runtime-enabled",havingValue="true")
@RequestMapping("/api/v1/projects/{projectUuid}/runs/{runUuid}/knowledge-modules/reconcile")
final class KmReconciliationController {
    private final AuthorizationService auth;
    private final ExecutionService executions;
    private final PinnedExecutionContextPort contexts;
    private final PublishReconciliationService reconciliation;
    private final String worker,profile;
    KmReconciliationController(AuthorizationService auth,ExecutionService executions,PinnedExecutionContextPort contexts,
            PublishReconciliationService reconciliation,@Value("${akis.execution.worker-reference:}") String worker,
            @Value("${akis.execution.worker-profile-uuid:}") String profile) {
        this.auth=auth;this.executions=executions;this.contexts=contexts;this.reconciliation=reconciliation;this.worker=worker;this.profile=profile;
    }
    record View(String outcome,String message) { }
    @PostMapping
    View reconcile(@PathVariable UUID projectUuid,@PathVariable UUID runUuid) {
        auth.requireProjectPermission(projectUuid,RUN_START);
        executions.get(projectUuid,runUuid); // Project boundary before any claim or Oracle access.
        var context=contexts.find(runUuid).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Çalıştırma bulunamadı."));
        if(!StagedRuntimePlanResolver.CAPABILITY.equals(context.physicalManifest().path("runtimeCapability").asText()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"KM_REQUIRED","Bu işlem yalnız KM çalıştırmaları içindir.");
        UUID profileUuid;
        try { profileUuid=UUID.fromString(profile);if(worker.isBlank()) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException invalid) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"WORKER_UNAVAILABLE","Mutabakat işleyicisi yapılandırılmamış."); }
        var result=reconciliation.reconcile(runUuid,new RunLeasePort.WorkerIdentity(worker,profileUuid),Duration.ofSeconds(120));
        String message=switch(result.outcome()) {
            case PUBLISHED -> "Hedef yayınının tamamlandığı doğrulandı.";
            case NOT_PUBLISHED -> "Hedef yayınının gerçekleşmediği doğrulandı. İşlem tekrar çalıştırılmadı.";
            case CONFLICT -> "Yayın kanıtında çakışma var; inceleme gerekiyor.";
            case OUTCOME_UNKNOWN -> "Hedef sonucu henüz doğrulanamadı. İşlem tekrar çalıştırılmadı.";
            case FAILED_CLOSED -> "Mutabakat tamamlanamadı veya başka bir işleyici tarafından yürütülüyor.";
        };
        return new View(result.outcome().name(),message);
    }
}

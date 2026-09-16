package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;
import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.BarrierEvidence;
import tr.com.innova.akis.execution.PinnedPublishReconciliationPort.PinnedReconciliation;

/**
 * Loads reconciliation evidence only through the exact active PostgreSQL lease.
 * The original publish token (T) and the durable reconciliation barrier (T+1)
 * are selected from one fresh transaction and can never be supplied by a caller.
 */
@Repository
public class JdbcPinnedPublishReconciliationStore
        implements PinnedPublishReconciliationPort {

    private final JdbcClient jdbc;
    private final PinnedExecutionContextPort executionContexts;
    private final ReconciliationPlanResolver plans;

    public JdbcPinnedPublishReconciliationStore(
            JdbcClient jdbc,
            PinnedExecutionContextPort executionContexts,
            PilotRuntimePlanResolver plans) {
        this(jdbc, executionContexts, plans::resolve);
    }
    @Autowired
    public JdbcPinnedPublishReconciliationStore(JdbcClient jdbc,PinnedExecutionContextPort executionContexts,
            PilotRuntimePlanResolver pilot,StagedRuntimePlanResolver staged) {
        this(jdbc,executionContexts,(release,hash,scenario,manifest)->
                StagedRuntimePlanResolver.CAPABILITY.equals(manifest.path("runtimeCapability").asText())
                    ?staged.resolve(release,hash,scenario,manifest):pilot.resolve(release,hash,scenario,manifest));
    }

    JdbcPinnedPublishReconciliationStore(
            JdbcClient jdbc,
            PinnedExecutionContextPort executionContexts,
            ReconciliationPlanResolver plans) {
        this.jdbc = jdbc;
        this.executionContexts = executionContexts;
        this.plans = plans;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PinnedReconciliation> find(UUID runUuid) {
        if (runUuid == null) {
            return Optional.empty();
        }
        Optional<StoredEvidence> stored = jdbc.sql("""
                        select p.uuid as project_uuid,
                               y.uuid as publication_uuid,
                               it.uuid as job_request_uuid,
                               c.uuid as run_uuid,
                               pyn.deneme_no,
                               pyn.calistirma_nesil_no as publish_run_generation,
                               pyn.isleyici_referansi as publish_worker_reference,
                               hk.uuid as target_resource_uuid,
                               pyn.hedef_nesil_no as publish_target_generation,
                               pyn.hedef_fiziksel_ozeti,
                               pyn.hedef_kimlik_surumu,
                               pyn.yayin_ozeti,
                               pyn.plan_ozeti,
                               pyn.runtime_plan_ozeti,
                               pyn.yayin_anahtari_ozeti,
                               pyn.payload_ozeti,
                               pyn.satir_sayisi,
                               pyn.bayt_sayisi,
                               cd.nesil_no as reconciliation_run_generation,
                               cd.isleyici_referansi as reconciliation_worker_reference,
                               cd.hedef_nesil_no as barrier_target_generation
                          from akis.pilot_yayin_niyeti pyn
                          join akis.calistirma c
                            on c.proje_id = pyn.proje_id
                           and c.id = pyn.calistirma_id
                          join akis.proje p on p.id = pyn.proje_id
                          join akis.is_talebi it
                            on it.proje_id = pyn.proje_id
                           and it.id = pyn.is_talebi_id
                          join akis.yayin y
                            on y.proje_id = it.proje_id and y.id = it.yayin_id
                          join akis.calistirma_durumu cd
                            on cd.proje_id = pyn.proje_id
                           and cd.calistirma_id = pyn.calistirma_id
                          join akis.hedef_kaynagi hk
                            on hk.id = pyn.hedef_kaynagi_id
                         where c.uuid = :runUuid
                           and cd.durum = 'MUTABAKAT'
                           and cd.worker_profili_id is not null
                           and cd.isleyici_referansi is not null
                           and cd.kiralama_bitis_zamani > clock_timestamp()
                           and cd.nesil_no = pyn.calistirma_nesil_no + 1
                           and cd.hedef_kaynagi_id = pyn.hedef_kaynagi_id
                           and cd.hedef_nesil_no = pyn.hedef_nesil_no + 1
                           and hk.durum = 'ASKIDA'
                           and hk.calistirma_id is null
                           and hk.kiralama_bitis_zamani is null
                           and hk.nesil_no = cd.hedef_nesil_no
                           and hk.fiziksel_ozet = pyn.hedef_fiziksel_ozeti
                           and hk.kimlik_surumu = pyn.hedef_kimlik_surumu
                           and pyn.deneme_no = c.deneme_no
                           and pyn.yayin_ozeti = c.yayin_ozeti
                           and pyn.plan_ozeti = c.plan_ozeti
                        """)
                .param("runUuid", runUuid)
                .query((rs, rowNum) -> {
                    PilotPublishIntent intent = new PilotPublishIntent(
                            rs.getObject("project_uuid", UUID.class),
                            rs.getObject("publication_uuid", UUID.class),
                            rs.getObject("job_request_uuid", UUID.class),
                            rs.getObject("run_uuid", UUID.class),
                            rs.getInt("deneme_no"),
                            rs.getLong("publish_run_generation"),
                            rs.getString("publish_worker_reference"),
                            rs.getObject("target_resource_uuid", UUID.class),
                            rs.getLong("publish_target_generation"),
                            rs.getString("hedef_fiziksel_ozeti"),
                            rs.getInt("hedef_kimlik_surumu"),
                            rs.getString("yayin_ozeti"),
                            rs.getString("plan_ozeti"),
                            rs.getString("runtime_plan_ozeti"),
                            rs.getString("yayin_anahtari_ozeti"),
                            rs.getString("payload_ozeti"),
                            rs.getLong("satir_sayisi"),
                            rs.getLong("bayt_sayisi"));
                    BarrierEvidence barrier = new BarrierEvidence(
                            rs.getObject("run_uuid", UUID.class),
                            rs.getLong("reconciliation_run_generation"),
                            rs.getString("reconciliation_worker_reference"),
                            rs.getObject("target_resource_uuid", UUID.class),
                            rs.getLong("publish_target_generation"),
                            rs.getLong("barrier_target_generation"),
                            rs.getString("hedef_fiziksel_ozeti"),
                            rs.getInt("hedef_kimlik_surumu"));
                    return new StoredEvidence(intent, barrier);
                })
                .optional();
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        Optional<PinnedExecutionContext> execution = executionContexts.find(runUuid);
        if (execution.isEmpty()) {
            return Optional.empty();
        }
        PinnedExecutionContext pinned = execution.get();
        MappingExecutionContract plan = plans.resolve(
                pinned.releaseHash(), pinned.planHash(),
                pinned.scenarioPlan(), pinned.physicalManifest());
        if (!plan.runtimePlanHash().equals(stored.get().intent().runtimePlanHash())) {
            return Optional.empty();
        }
        return Optional.of(new PinnedReconciliation(
                plan, pinned, stored.get().intent(), stored.get().barrier()));
    }

    private record StoredEvidence(
            PilotPublishIntent intent,
            BarrierEvidence barrier) {
    }

    @FunctionalInterface
    interface ReconciliationPlanResolver {
        MappingExecutionContract resolve(
                String releaseHash,
                String planHash,
                JsonNode scenarioPlan,
                JsonNode physicalManifest);
    }
}

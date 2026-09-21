package tr.com.innova.akis.execution;

import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.publication.PackagePublicationPlanner;
import static tr.com.innova.akis.execution.ProcedureWorkerOrchestrator.*;
import static tr.com.innova.akis.execution.RunLeasePort.*;

/**
 * Executes one claimed package run: walks the pinned step graph from firstStepId following SUCCESS/FAILURE/TRUE/FALSE/ALWAYS
 * transitions. Variable steps refresh in-process and their values seed the variable history of later child runs; procedure
 * and mapping steps start a child run of the step's pinned publication and execute it in this worker before continuing.
 */
@Component
final class PackageWorkerOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(PackageWorkerOrchestrator.class);
    private static final int MAXIMUM_STEPS_PER_RUN = 200;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final WorkerLeaseService leases;
    private final PinnedExecutionContextPort contexts;
    private final PackageVariableEvaluator variables;

    PackageWorkerOrchestrator(JdbcClient jdbc, ObjectMapper mapper, WorkerLeaseService leases, PinnedExecutionContextPort contexts, PackageVariableEvaluator variables) {
        this.jdbc = jdbc; this.mapper = mapper; this.leases = leases; this.contexts = contexts; this.variables = variables;
    }

    /** Runs a child run claimed by this worker; wired by the procedure orchestrator, which owns the per-capability dispatch. */
    interface ChildRunner { RunOnceResult run(ClaimedRun claimed, LeaseGate gate); }

    RunOnceResult run(PinnedExecutionContextPort.PinnedExecutionContext context, ClaimedRun claimed, LeaseGate gate, WorkerIdentity worker, Duration lease, ChildRunner children) {
        JsonNode plan = context.physicalManifest().path("packagePlan");
        RunLeaseToken token = claimed.token();
        Map<String, JsonNode> steps = new LinkedHashMap<>();
        for (JsonNode step : plan.path("steps")) steps.put(step.path("id").asText(), step);
        if (steps.isEmpty() || !steps.containsKey(plan.path("firstStepId").asText())) {
            finish(token, "BASARISIZ", "PACKAGE_PLAN_INVALID");
            return new FailedSafely("PACKAGE_PLAN_INVALID");
        }
        ArrayNode registered = mapper.createArrayNode();
        steps.values().forEach(step -> registered.add(mapper.createObjectNode().put("id", step.path("id").asText()).put("name", step.path("name").asText()).put("type", step.path("type").asText())));
        if (!Boolean.TRUE.equals(jdbc.sql("select akis.paket_calistirmayi_baslat(:run,:worker,:generation,cast(:steps as jsonb))")
                .param("run", token.runUuid()).param("worker", token.workerReference()).param("generation", token.generation())
                .param("steps", registered.toString()).query(Boolean.class).single())) {
            return new FailedSafely("PACKAGE_START_REJECTED");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        String current = plan.path("firstStepId").asText();
        int executed = 0;
        try {
            while (current != null) {
                if (++executed > MAXIMUM_STEPS_PER_RUN) throw new IllegalStateException("Paket adım döngüsü sınırı aşıldı.");
                gate.checkpoint();
                JsonNode step = steps.get(current);
                String outcome;
                String type = step.path("type").asText();
                stepState(token, current, "CALISIYOR", null, null, null);
                try {
                    if ("VARIABLE_REFRESH".equals(type) || "VARIABLE_EVALUATE".equals(type)) {
                        Object value = variables.refresh(token.runUuid(), step.path("variable"));
                        values.put(step.path("definitionUuid").asText(), value);
                        boolean evaluation = !"VARIABLE_EVALUATE".equals(type) || PackageVariableEvaluator.evaluate(value, step.path("evaluate"));
                        outcome = "VARIABLE_EVALUATE".equals(type) ? (evaluation ? "TRUE" : "FALSE") : "SUCCESS";
                        stepState(token, current, "BASARILI", null, String.valueOf(value), null);
                    } else {
                        ChildOutcome child = runChild(context, token, step, values, worker, lease, children);
                        outcome = child.succeeded() ? "SUCCESS" : "FAILURE";
                        stepState(token, current, child.succeeded() ? "BASARILI" : "BASARISIZ", child.runUuid(), null, child.errorCode());
                    }
                } catch (RuntimeException failure) {
                    LOG.warn("Package run {} step {} failed: {}", token.runUuid(), current, failure.toString());
                    stepState(token, current, "BASARISIZ", null, null, "PACKAGE_STEP_FAILED");
                    outcome = "FAILURE";
                }
                String next = nextStep(plan, current, outcome);
                if (next == null && !"SUCCESS".equals(outcome) && !"TRUE".equals(outcome) && !"FALSE".equals(outcome)) {
                    finish(token, "BASARISIZ", "PACKAGE_STEP_FAILED");
                    return new FailedSafely("PACKAGE_STEP_FAILED");
                }
                current = next;
            }
            finish(token, "BASARILI", null);
            return new Succeeded(executed, 0, 0, 0);
        } catch (RuntimeException failure) {
            LOG.warn("Package run {} failed: {}", token.runUuid(), failure.toString());
            finish(token, "BASARISIZ", "PACKAGE_EXECUTION_REJECTED");
            return new FailedSafely("PACKAGE_EXECUTION_REJECTED");
        }
    }

    private record ChildOutcome(UUID runUuid, boolean succeeded, String errorCode) { }

    private ChildOutcome runChild(PinnedExecutionContextPort.PinnedExecutionContext parent, RunLeaseToken token, JsonNode step, Map<String, Object> values,
            WorkerIdentity worker, Duration lease, ChildRunner children) {
        JsonNode publication = step.path("publication");
        UUID childRun = createChildRun(parent, token, step.path("id").asText(), UUID.fromString(publication.path("publicationUuid").asText()));
        // Package variable values win over the child's own refresh: the procedure runtime reads its history row for this run first.
        seedVariables(childRun, publication.path("runtimePlanHash").asText(), values, publication);
        stepState(token, step.path("id").asText(), "CALISIYOR", childRun, null, null);
        Optional<ClaimedRun> claimed = jdbc.sql("""
                        select calistirma_uuid, nesil_no, yayin_ozeti, plan_ozeti, kiralama_bitis_zamani
                          from akis.calistirma_belirli_sahiplen(:run, :profileUuid, :workerReference, :leaseSeconds)
                        """).param("run", childRun).param("profileUuid", worker.profileUuid()).param("workerReference", worker.reference())
                .param("leaseSeconds", (int) lease.toSeconds())
                .query((rs, n) -> new ClaimedRun(new RunLeaseToken(rs.getObject("calistirma_uuid", UUID.class), worker.reference(), rs.getLong("nesil_no"),
                        rs.getObject("kiralama_bitis_zamani", java.time.OffsetDateTime.class)), rs.getString("yayin_ozeti"), rs.getString("plan_ozeti")))
                .optional();
        if (claimed.isEmpty()) return new ChildOutcome(childRun, false, "CHILD_CLAIM_REJECTED");
        RunOnceResult result;
        try (LeaseGate childGate = HeartbeatSupervisor.start(leases, claimed.get().token(), lease)) {
            result = children.run(claimed.get(), childGate);
        } catch (RuntimeException failure) {
            LOG.warn("Package child run {} failed at the worker boundary: {}", childRun, failure.toString());
            return new ChildOutcome(childRun, false, "CHILD_WORKER_BOUNDARY_FAILED");
        }
        return switch (result) {
            case Succeeded s -> new ChildOutcome(childRun, true, null);
            case FailedSafely f -> new ChildOutcome(childRun, false, f.errorCode());
            case OutcomeUnknown u -> new ChildOutcome(childRun, false, u.errorCode());
            case StoppedFailClosed s -> new ChildOutcome(childRun, false, s.errorCode());
            case Idle i -> new ChildOutcome(childRun, false, "CHILD_NOT_STARTED");
        };
    }

    private UUID createChildRun(PinnedExecutionContextPort.PinnedExecutionContext parent, RunLeaseToken token, String stepCode, UUID publicationUuid) {
        UUID jobUuid = UUID.randomUUID(), runUuid = UUID.randomUUID();
        Long jobId = jdbc.sql("""
                insert into akis.is_talebi(proje_id, yayin_id, istek_ozeti, is_turu, oncelik, parametre_sema_surumu, parametre, uuid, olusturan_kullanici_id)
                select y.proje_id, y.id, encode(sha256(convert_to(:runUuid || '|' || :step, 'UTF8')), 'hex'), 'CALISTIR', 60, 1,
                       jsonb_build_object('packageRunUuid', :parentRun, 'packageStepCode', :step) || (parentJob.parametre - 'packageRunUuid' - 'packageStepCode'),
                       :jobUuid, parent.olusturan_kullanici_id
                  from akis.yayin y, akis.calistirma parent join akis.is_talebi parentJob on parentJob.id = parent.is_talebi_id
                 where y.uuid = :publication and y.durum = 'AKTIF' and parent.uuid = :parentRun
                returning id
                """).param("runUuid", runUuid.toString()).param("step", stepCode).param("parentRun", token.runUuid()).param("jobUuid", jobUuid)
                .param("publication", publicationUuid).query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("Adımın yayını artık aktif değil: " + publicationUuid));
        Long runId = jdbc.sql("""
                insert into akis.calistirma(proje_id, is_talebi_id, deneme_no, yayin_ozeti, plan_ozeti, baslatma_turu, uuid, olusturan_kullanici_id, ust_calistirma_id, ust_adim_kodu)
                select y.proje_id, :jobId, 1, y.fiziksel_manifesto->>'releaseHash', s.plan_ozeti, 'ILK', :runUuid, parent.olusturan_kullanici_id, parent.id, :step
                  from akis.yayin y join akis.senaryo s on s.id = y.senaryo_id, akis.calistirma parent
                 where y.uuid = :publication and parent.uuid = :parentRun
                returning id
                """).param("jobId", jobId).param("runUuid", runUuid).param("step", stepCode).param("publication", publicationUuid).param("parentRun", token.runUuid())
                .query(Long.class).single();
        jdbc.sql("""
                insert into akis.calistirma_durumu(proje_id, calistirma_id, durum, son_olay_no, uuid, olusturan_kullanici_id, guncellenme_zamani, guncelleyen_kullanici_id)
                select c.proje_id, c.id, 'BEKLIYOR', 1, :stateUuid, c.olusturan_kullanici_id, current_timestamp, c.olusturan_kullanici_id from akis.calistirma c where c.id = :runId
                """).param("stateUuid", UUID.randomUUID()).param("runId", runId).update();
        jdbc.sql("""
                insert into akis.calistirma_olayi(proje_id, calistirma_id, olay_no, tur, olay_zamani, veri, uuid, olusturan_kullanici_id)
                select c.proje_id, c.id, 1, 'RUN_REQUESTED', clock_timestamp(),
                       jsonb_build_object('releaseHash', c.yayin_ozeti, 'planHash', c.plan_ozeti, 'publicationUuid', :publication, 'packageRunUuid', :parentRun, 'packageStepCode', :step),
                       :eventUuid, c.olusturan_kullanici_id
                  from akis.calistirma c where c.id = :runId
                """).param("publication", publicationUuid.toString()).param("parentRun", token.runUuid().toString()).param("step", stepCode)
                .param("eventUuid", UUID.randomUUID()).param("runId", runId).update();
        return runUuid;
    }

    /** Pre-seed the child run's variable history so its procedure steps bind the package value instead of refreshing again. */
    private void seedVariables(UUID childRun, String runtimePlanHash, Map<String, Object> values, JsonNode publication) {
        if (values.isEmpty() || runtimePlanHash == null || runtimePlanHash.isBlank()) return;
        JsonNode bindings = childManifest(UUID.fromString(publication.path("publicationUuid").asText())).path("variableBindings");
        for (var entry : values.entrySet()) {
            JsonNode binding = bindings.path(entry.getKey());
            if (!binding.isObject()) continue;
            jdbc.sql("""
                    insert into akis.degisken_deger_gecmisi(proje_uuid,tanim_uuid,calistirma_uuid,ortam_uuid,mantiksal_sema_uuid,baglanti_surumu_uuid,plan_ozeti,veri_turu,deger,gecmis_modu)
                    values(:project,:definition,:run,:environment,:logical,:version,:hash,:type,:value,'ALL')
                    """).param("project", UUID.fromString(binding.path("projectUuid").asText())).param("definition", UUID.fromString(entry.getKey()))
                    .param("run", childRun).param("environment", UUID.fromString(binding.path("environmentUuid").asText()))
                    .param("logical", UUID.fromString(binding.path("logicalSchemaUuid").asText())).param("version", UUID.fromString(binding.path("connectionVersionUuid").asText()))
                    .param("hash", runtimePlanHash).param("type", binding.path("type").asText()).param("value", String.valueOf(entry.getValue())).update();
        }
    }

    private JsonNode childManifest(UUID publicationUuid) {
        return jdbc.sql("select fiziksel_manifesto::text from akis.yayin where uuid=:uuid").param("uuid", publicationUuid)
                .query(String.class).optional().map(mapper::readTree).orElse(mapper.createObjectNode());
    }

    private static String nextStep(JsonNode plan, String from, String outcome) {
        String fallback = null;
        for (JsonNode edge : plan.path("transitions")) {
            if (!edge.path("fromStepId").asText().equals(from)) continue;
            String edgeOutcome = edge.path("outcome").asText("SUCCESS");
            if (edgeOutcome.equals(outcome)) return edge.path("toStepId").asText();
            if ("ALWAYS".equals(edgeOutcome)) fallback = edge.path("toStepId").asText();
        }
        return fallback;
    }

    private void stepState(RunLeaseToken token, String stepCode, String state, UUID childRun, String value, String errorCode) {
        Boolean accepted = jdbc.sql("select akis.paket_adimini_guncelle(:run,:worker,:generation,:step,:state,:child,:value,:error)")
                .param("run", token.runUuid()).param("worker", token.workerReference()).param("generation", token.generation())
                .param("step", stepCode).param("state", state).param("child", childRun, java.sql.Types.OTHER)
                .param("value", value, java.sql.Types.VARCHAR).param("error", errorCode, java.sql.Types.VARCHAR).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("Paket adım durumu kaydedilemedi: " + stepCode + " -> " + state);
    }

    private void finish(RunLeaseToken token, String state, String errorCode) {
        try {
            jdbc.sql("select akis.paket_calistirmayi_bitir(:run,:worker,:generation,:state,:error)")
                    .param("run", token.runUuid()).param("worker", token.workerReference()).param("generation", token.generation())
                    .param("state", state).param("error", errorCode, java.sql.Types.VARCHAR).query(Boolean.class).single();
        } catch (RuntimeException failure) { LOG.warn("Package run {} terminal state {} could not be recorded: {}", token.runUuid(), state, failure.toString()); }
    }

    static boolean isPackage(JsonNode manifest) {
        return PackagePublicationPlanner.CAPABILITY.equals(manifest.path("runtimeCapability").asText());
    }

    static ObjectNode emptyPlan(ObjectMapper mapper) { return mapper.createObjectNode(); }
}

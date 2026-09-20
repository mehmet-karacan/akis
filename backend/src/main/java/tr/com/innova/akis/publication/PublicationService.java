package tr.com.innova.akis.publication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.execution.PilotRuntimePlanResolver;
import tr.com.innova.akis.execution.PilotRuntimePlanException;
import tr.com.innova.akis.execution.ProcedureRuntimePlanException;
import tr.com.innova.akis.execution.ProcedureRuntimePlanResolver;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import tr.com.innova.akis.publication.PublicationModels.ApprovalActor;
import tr.com.innova.akis.publication.PublicationModels.ApprovalResult;
import tr.com.innova.akis.publication.PublicationModels.ApprovalRow;
import tr.com.innova.akis.publication.PublicationModels.CreateResult;
import tr.com.innova.akis.publication.PublicationModels.PublicationContext;
import tr.com.innova.akis.publication.PublicationModels.PublicationDraft;
import tr.com.innova.akis.publication.PublicationModels.PublicationRow;
import tr.com.innova.akis.publication.PublicationModels.ResolvedBinding;

@Service
public class PublicationService {

    private static final int MANIFEST_VERSION = 2;
    private static final Set<String> DECISIONS = Set.of("ONAY", "RED", "GERI_CEK");

    private final PublicationStore store;
    private final ObjectMapper objectMapper;
    private final PilotRuntimePlanResolver runtimePlanResolver;
    private final ProcedureRuntimePlanResolver procedureRuntimePlanResolver;
    private final SecretValueSanitizer secretSanitizer;
    private StagedMappingPlanner stagedPlanner;
    private tr.com.innova.akis.execution.StagedRuntimePlanResolver stagedResolver;
    @org.springframework.beans.factory.annotation.Value("${akis.execution.staged-runtime-enabled:false}")
    private boolean stagedRuntimeEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    void configureStagedResolver(tr.com.innova.akis.execution.StagedRuntimePlanResolver resolver) { this.stagedResolver=resolver; }

    @org.springframework.beans.factory.annotation.Autowired
    void configureStagedPlanner(StagedMappingPlanner planner) { this.stagedPlanner = planner; }

    @Transactional
    public JsonNode previewStaged(UUID projectUuid, UUID scenarioUuid, UUID environmentUuid) {
        var context = store.lockContext(projectUuid, scenarioUuid, environmentUuid).orElseThrow(() -> notFound("Senaryo/ortam bulunamadı."));
        if (!Set.of(3,4).contains(context.definitionSchemaVersion()) || stagedPlanner == null) throw validation("KM plan önizlemesi için yürütme modüllü bir arayüz gerekir.");
        var bindings = store.resolveBindings(context);
        validateResolvedBindings(bindings);
        var result = objectMapper.createObjectNode();
        var plan = stagedPlanner.compile(context,bindings);
        result.set("plan", plan);
        result.set("sqlPreview", stagedPlanner.sqlPreview(context, plan));
        result.put("executionVerified",false);
        result.put("message","Plan üretildi. Canlı DB/PDB, şema, yetki ve çalışma alanı kontrolleri yürütmede ayrıca gerekir.");
        return result;
    }

    public PublicationService(
            PublicationStore store,
            ObjectMapper objectMapper,
            PilotRuntimePlanResolver runtimePlanResolver,
            ProcedureRuntimePlanResolver procedureRuntimePlanResolver,
            SecretValueSanitizer secretSanitizer) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.runtimePlanResolver = runtimePlanResolver;
        this.procedureRuntimePlanResolver = procedureRuntimePlanResolver;
        this.secretSanitizer = secretSanitizer;
    }

    @Transactional
    public CreateResult create(
            UUID projectUuid, UUID scenarioUuid, UUID environmentUuid) {
        return create(projectUuid, scenarioUuid, environmentUuid, null);
    }

    @Transactional
    public CreateResult create(UUID projectUuid, UUID scenarioUuid, UUID environmentUuid, String expectedPhysicalPlanHash) {
        PublicationContext context = store.lockContext(
                        projectUuid, scenarioUuid, environmentUuid)
                .orElseThrow(() -> notFound(
                        "Projeye ait Scenario veya aktif ortam bulunamadı."));
        List<ResolvedBinding> bindings = store.resolveBindings(context).stream()
                .sorted(Comparator.comparing(ResolvedBinding::nodeCode))
                .toList();
        validateResolvedBindings(bindings);
        if (!secretSanitizer.sensitivePaths(context.environmentPolicy()).isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "SENSITIVE_VALUE_REJECTED",
                    "Ortam politikası secret değer taşıyamaz; yalnız güvenli referans kullanın.");
        }

        boolean pilotExecutable = runtimePlanResolver.isPilotCandidate(context.scenarioPlan());
        boolean procedureExecutable = !pilotExecutable
                && procedureRuntimePlanResolver.isProcedureV2(context.scenarioPlan());
        boolean taskApprovalRequired = false;
        if (procedureExecutable) {
            try {
                taskApprovalRequired = procedureRuntimePlanResolver
                        .requiresApprovalForPublication(context.scenarioPlan());
            }
            catch (ProcedureRuntimePlanException exception) {
                throw procedurePlanRejected(exception);
            }
        }
        boolean stagedExecutable=stagedRuntimeEnabled && context.definitionSchemaVersion()==3;
        String runtimeCapability = stagedExecutable?tr.com.innova.akis.execution.StagedRuntimePlanResolver.CAPABILITY:pilotExecutable
                ? PilotRuntimePlanResolver.PILOT_CAPABILITY
                : procedureExecutable
                ? ProcedureRuntimePlanResolver.CAPABILITY
                : PilotRuntimePlanResolver.DEFINITION_ONLY_CAPABILITY;
        boolean approvalRequired = ApprovalPolicyEvaluator.requiresApproval(
                context.environmentRisk(), taskApprovalRequired);
        ObjectNode unsignedManifest = unsignedManifest(
                context, bindings, runtimeCapability, approvalRequired);
        if (Set.of(3,4).contains(context.definitionSchemaVersion()) && !java.util.Objects.equals(expectedPhysicalPlanHash,
                unsignedManifest.path("stagedPlan").path("physicalPlanHash").asText())) {
            throw conflict("PHYSICAL_PLAN_CHANGED", "Çalışma planı değişmiş veya önizleme yapılmamış; yeniden önizleyin.");
        }
        if(stagedExecutable) unsignedManifest.put("runtimePlanHash",unsignedManifest.path("stagedPlan").path("physicalPlanHash").asText());
        if (pilotExecutable) {
            try {
                String runtimePlanHash = runtimePlanResolver.compileHashForPublication(
                        context.planHash(), context.scenarioPlan(), unsignedManifest);
                unsignedManifest.put("runtimePlanHash", runtimePlanHash);
            }
            catch (PilotRuntimePlanException exception) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "PILOT_RUNTIME_PLAN_REJECTED",
                        "Yayın, Oracle tablo kopyalama pilotunun güvenli çalışma sözleşmesine uymuyor.");
            }
        }
        else if (procedureExecutable) {
            try {
                String runtimePlanHash = procedureRuntimePlanResolver.compileHashForPublication(
                        context.planHash(), context.scenarioPlan(), unsignedManifest);
                unsignedManifest.put("runtimePlanHash", runtimePlanHash);
            }
            catch (ProcedureRuntimePlanException exception) {
                throw procedurePlanRejected(exception);
            }
        }
        String releaseHash = sha256(canonicalize(unsignedManifest).toString());
        ObjectNode manifest = unsignedManifest.deepCopy();
        manifest.put("releaseHash", releaseHash);
        JsonNode canonicalManifest = canonicalize(manifest);
        if(stagedExecutable) {
            if(stagedResolver==null) throw validation("KM yürütme doğrulayıcısı hazır değil.");
            try { stagedResolver.validatePublication(releaseHash,context.planHash(),context.scenarioPlan(),canonicalManifest); }
            catch(IllegalArgumentException invalid) { throw validation("KM fiziksel planı yürütme sözleşmesiyle uyuşmuyor."); }
        }
        String status = approvalRequired ? "ONAY_BEKLIYOR" : "AKTIF";
        String dependencySummary = "scenario=" + context.scenarioUuid()
                + ";bindingCount=" + bindings.size()
                + ";releaseHash=" + releaseHash;

        return store.findByReleaseHash(
                        context.scenarioId(), context.environmentId(), releaseHash)
                .map(existing -> new CreateResult(existing, false))
                .orElseGet(() -> new CreateResult(store.create(
                        new PublicationDraft(
                                context, releaseHash, dependencySummary,
                                canonicalManifest, bindings, status),
                        UUID.randomUUID()), true));
    }

    @Transactional(readOnly = true)
    public List<PublicationRow> list(UUID projectUuid) {
        requireProject(projectUuid);
        return store.list(projectUuid);
    }

    @Transactional(readOnly = true)
    public PublicationRow get(UUID projectUuid, UUID publicationUuid) {
        return store.find(projectUuid, publicationUuid)
                .orElseThrow(() -> notFound("Yayın bulunamadı."));
    }

    @Transactional
    public ApprovalResult decide(
            UUID projectUuid,
            UUID publicationUuid,
            ApprovalActor actor,
            String decision,
            String reason) {
        String normalizedDecision = normalizeDecision(decision);
        String normalizedReason = normalizeReason(normalizedDecision, reason);
        PublicationRow publication = store.lockPublication(projectUuid, publicationUuid)
                .orElseThrow(() -> notFound("Yayın bulunamadı."));
        if (!approvalRequired(publication)) {
            throw conflict(
                    "APPROVAL_NOT_REQUIRED",
                    "Bu yayın onay akışına girmiyor.");
        }

        String expectedStatus = "GERI_CEK".equals(normalizedDecision)
                ? "AKTIF" : "ONAY_BEKLIYOR";
        String targetStatus = "ONAY".equals(normalizedDecision) ? "AKTIF" : "IPTAL";
        if (targetStatus.equals(publication.status())) {
            ApprovalRow existing = store.findLatestApproval(
                            publication.id(), actor.id(), normalizedDecision)
                    .orElseThrow(() -> conflict(
                            "INVALID_PUBLICATION_STATE",
                            "Yayın bu karar için uygun durumda değil."));
            return new ApprovalResult(publication, existing, false);
        }
        if (!expectedStatus.equals(publication.status())) {
            throw conflict(
                    "INVALID_PUBLICATION_STATE",
                    "Yayın " + normalizedDecision + " kararı için uygun durumda değil.");
        }

        ApprovalRow approval = store.createApproval(
                publication, actor, normalizedDecision, normalizedReason, UUID.randomUUID());
        PublicationRow transitioned = store.transition(
                publication.id(), expectedStatus, targetStatus);
        return new ApprovalResult(transitioned, approval, true);
    }

    private void requireProject(UUID projectUuid) {
        if (!store.projectExists(projectUuid)) {
            throw notFound("Proje bulunamadı.");
        }
    }

    private void validateResolvedBindings(List<ResolvedBinding> bindings) {
        for (ResolvedBinding binding : bindings) {
            if (binding.environmentSchemaBindingId() == null
                    || binding.physicalSchemaId() == null
                    || binding.connectionVersionId() == null) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "ENVIRONMENT_BINDING_MISSING",
                        "Tanım veri düğümü için aktif ortam şema eşlemesi yok: "
                                + binding.nodeCode());
            }
            if (!"AKTIF".equals(binding.dataObjectStatus())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "DATA_OBJECT_INACTIVE",
                        "Yayın yalnız aktif veri nesnelerini kullanabilir.");
            }
            if (!"AKTIF".equals(binding.modelStatus())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "MODEL_INACTIVE",
                        "Yayın yalnız aktif modelleri kullanabilir.");
            }
            if (!"AKTIF".equals(binding.logicalSchemaStatus())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "LOGICAL_SCHEMA_INACTIVE",
                        "Yayın yalnız aktif mantıksal şemaları kullanabilir.");
            }
            if (!"AKTIF".equals(binding.connectionStatus())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "CONNECTION_INACTIVE",
                        "Yayın yalnız aktif bağlantıları kullanabilir.");
            }
            if (binding.targetSnapshotId() == null) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "TARGET_SCHEMA_SNAPSHOT_MISSING",
                        "Ortamda veri düğümüne ait şema görüntüsü yok: "
                                + binding.nodeCode());
            }
        }
    }

    private ObjectNode unsignedManifest(
            PublicationContext context,
            List<ResolvedBinding> bindings,
            String runtimeCapability,
            boolean approvalRequired) {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("contentHash", context.definitionContentHash());
        definition.put("definitionUuid", context.definitionUuid().toString());
        definition.put("definitionVersionUuid", context.definitionVersionUuid().toString());
        definition.put("schemaVersion", context.definitionSchemaVersion());

        ObjectNode scenario = objectMapper.createObjectNode();
        scenario.put("planHash", context.planHash());
        scenario.put("scenarioUuid", context.scenarioUuid().toString());

        ObjectNode environment = objectMapper.createObjectNode();
        environment.put("code", context.environmentCode());
        environment.put("environmentUuid", context.environmentUuid().toString());
        environment.set("policy", canonicalize(context.environmentPolicy()));
        environment.put("policyVersion", context.environmentPolicyVersion());
        environment.put("risk", context.environmentRisk());

        ArrayNode bindingNodes = objectMapper.createArrayNode();
        for (ResolvedBinding binding : bindings) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("bindingVersion", binding.bindingVersion());
            node.put("connectionVersionUuid", binding.connectionVersionUuid().toString());
            node.put("databaseType", binding.databaseType());
            node.put("dataObjectReference", binding.dataObjectReference());
            node.put("dataObjectType", binding.dataObjectType());
            node.put("dataObjectUuid", binding.dataObjectUuid().toString());
            node.put("definitionDataObjectUuid", binding.definitionDataObjectUuid().toString());
            node.put("environmentSchemaBindingUuid",
                    binding.environmentSchemaBindingUuid().toString());
            node.put("nodeCode", binding.nodeCode());
            node.put("physicalIdentity", binding.physicalSchemaReference()
                    + "." + binding.dataObjectReference());
            node.put("physicalSchemaReference", binding.physicalSchemaReference());
            node.put("physicalSchemaUuid", binding.physicalSchemaUuid().toString());
            node.put("role", binding.role());
            node.put("schemaSnapshotFingerprint", binding.targetSnapshotFingerprint());
            node.put("schemaSnapshotUuid", binding.targetSnapshotUuid().toString());
            bindingNodes.add(node);
        }

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("approvalRequired", approvalRequired);
        manifest.set("bindings", bindingNodes);
        JsonNode variableBindings = store.resolveVariableBindings(context);
        if (variableBindings != null && variableBindings.size() > 0) manifest.set("variableBindings", variableBindings);
        manifest.set("definition", definition);
        manifest.set("environment", environment);
        manifest.put("manifestVersion", MANIFEST_VERSION);
        manifest.put("runtimeCapability", runtimeCapability);
        if (ProcedureRuntimePlanResolver.CAPABILITY.equals(runtimeCapability)) {
            manifest.set("policyVersions", tr.com.innova.akis.execution.ProcedurePolicyVersions.current(objectMapper));
        }
        manifest.set("scenario", scenario);
        if (Set.of(3,4).contains(context.definitionSchemaVersion())) {
            if (stagedPlanner == null) throw validation("KM plan servisi hazır değil.");
            manifest.set("stagedPlan", stagedPlanner.compile(context, bindings));
        }
        return manifest;
    }

    private boolean approvalRequired(PublicationRow publication) {
        JsonNode marker = publication.physicalManifest().get("approvalRequired");
        return ApprovalPolicyEvaluator.requiresApproval(publication.environmentRisk(),
                marker != null && marker.isBoolean() && marker.booleanValue());
    }

    private ApiException procedurePlanRejected(ProcedureRuntimePlanException exception) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "PROCEDURE_RUNTIME_PLAN_REJECTED",
                "Yayın, Procedure çalışma sözleşmesine uymuyor: " + exception.getMessage());
    }

    JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            var names = new ArrayList<String>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        return node.deepCopy();
    }

    private String normalizeDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw validation("Karar kodu gereklidir.");
        }
        String normalized = decision.trim().toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(normalized)) {
            throw validation("Desteklenmeyen karar kodu: " + decision);
        }
        return normalized;
    }

    private String normalizeReason(String decision, String reason) {
        String normalized = reason == null ? null : reason.trim();
        if (("RED".equals(decision) || "GERI_CEK".equals(decision))
                && (normalized == null || normalized.isBlank())) {
            throw validation("Red veya geri çekme kararı için gerekçe gereklidir.");
        }
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "PUBLICATION_VALIDATION_FAILED", message);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}

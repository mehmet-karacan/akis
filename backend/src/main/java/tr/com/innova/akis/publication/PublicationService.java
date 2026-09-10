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

import tr.com.innova.akis.metadata.ApiException;
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

    private static final int MANIFEST_VERSION = 1;
    private static final String HIGH_RISK = "URETIM";
    private static final Set<String> DECISIONS = Set.of("ONAY", "RED", "GERI_CEK");

    private final PublicationStore store;
    private final ObjectMapper objectMapper;

    public PublicationService(PublicationStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreateResult create(
            UUID projectUuid, UUID scenarioUuid, UUID environmentUuid) {
        PublicationContext context = store.lockContext(
                        projectUuid, scenarioUuid, environmentUuid)
                .orElseThrow(() -> notFound(
                        "Projeye ait Scenario veya aktif ortam bulunamadı."));
        List<ResolvedBinding> bindings = store.resolveBindings(context).stream()
                .sorted(Comparator.comparing(ResolvedBinding::nodeCode))
                .toList();
        validateResolvedBindings(bindings);

        ObjectNode unsignedManifest = unsignedManifest(context, bindings);
        String releaseHash = sha256(canonicalize(unsignedManifest).toString());
        ObjectNode manifest = unsignedManifest.deepCopy();
        manifest.put("releaseHash", releaseHash);
        JsonNode canonicalManifest = canonicalize(manifest);
        String status = HIGH_RISK.equals(context.environmentRisk())
                ? "ONAY_BEKLIYOR" : "AKTIF";
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
        if (!HIGH_RISK.equals(publication.environmentRisk())) {
            throw conflict(
                    "APPROVAL_NOT_REQUIRED",
                    "Yalnız yüksek riskli üretim ortamı yayınları onay akışına girer.");
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
            PublicationContext context, List<ResolvedBinding> bindings) {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("definitionUuid", context.definitionUuid().toString());
        definition.put("definitionVersionUuid", context.definitionVersionUuid().toString());

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
            node.put("dataObjectReference", binding.dataObjectReference());
            node.put("dataObjectUuid", binding.dataObjectUuid().toString());
            node.put("definitionDataObjectUuid", binding.definitionDataObjectUuid().toString());
            node.put("environmentSchemaBindingUuid",
                    binding.environmentSchemaBindingUuid().toString());
            node.put("nodeCode", binding.nodeCode());
            node.put("physicalIdentity", binding.physicalSchemaReference()
                    + "." + binding.dataObjectReference());
            node.put("physicalSchemaUuid", binding.physicalSchemaUuid().toString());
            node.put("role", binding.role());
            node.put("schemaSnapshotFingerprint", binding.targetSnapshotFingerprint());
            node.put("schemaSnapshotUuid", binding.targetSnapshotUuid().toString());
            bindingNodes.add(node);
        }

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.set("bindings", bindingNodes);
        manifest.set("definition", definition);
        manifest.set("environment", environment);
        manifest.put("manifestVersion", MANIFEST_VERSION);
        manifest.set("scenario", scenario);
        return manifest;
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

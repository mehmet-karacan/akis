package tr.com.innova.akis.publication;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.*;
import tr.com.innova.akis.metadata.ApiException;
import static tr.com.innova.akis.publication.PublicationModels.*;

/** Metadata-only physical plan preparation; never opens a business database connection. */
@Component
final class StagedMappingPlanner {
    private final KnowledgeModuleRegistry modules;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final WorkAreaPolicyService workAreas;
    StagedMappingPlanner(KnowledgeModuleRegistry modules, JdbcClient jdbc, ObjectMapper mapper,WorkAreaPolicyService workAreas) {
        this.modules=modules; this.jdbc=jdbc; this.mapper=mapper; this.workAreas=workAreas;
    }
    JsonNode compile(PublicationContext context, List<ResolvedBinding> bindings) {
        JsonNode content = context.scenarioPlan().path("executable").path("definition");
        if (!context.definitionContentHash().equals(KmCanonical.hash(mapper, content))
                || !context.planHash().equals(KmCanonical.hash(mapper, context.scenarioPlan()))) throw rejected("Senaryo bütünlük kontrolü başarısız.");
        StagedMappingDefinition definition;
        try { definition = StagedMappingDefinition.parse(content); }
        catch (IllegalArgumentException invalid) { throw rejected(invalid.getMessage()); }
        var bundle = modules.resolve(context.projectId(), definition);
        int expectedBindingCount = definition.sources().isEmpty() ? 2 : definition.sources().size() + 1;
        // Faz A: sources are Oracle; the target may be Oracle or PostgreSQL, and its provider decides the runtime bundle.
        if (bindings.size()!=expectedBindingCount || bindings.stream().anyMatch(b -> !Set.of("TABLE", "TABLO").contains(b.dataObjectType())
                || !("ORACLE".equals(b.databaseType()) || "POSTGRESQL".equals(b.databaseType()) && "HEDEF".equals(b.role()))))
            throw rejected("Kaynaklar Oracle tablosu, hedef Oracle veya PostgreSQL tablosu olmalıdır.");
        Set<String> expected = new HashSet<>();
        if (content.has("sources")) { content.path("sources").forEach(d -> expected.add(d.path("id").asText())); expected.add(content.path("target").path("id").asText()); }
        else content.path("datasets").forEach(d -> expected.add(d.path("id").asText()));
        if (!expected.equals(new HashSet<>(bindings.stream().map(ResolvedBinding::nodeCode).toList()))) throw rejected("Kaynak ve hedef referansları uyuşmuyor.");
        if (content.has("sources")) {
            for(var source:content.path("sources")) if(bindings.stream().noneMatch(b->b.nodeCode().equals(source.path("id").asText())&&"KAYNAK".equals(b.role()))) throw rejected("Kaynak rolü uyuşmuyor.");
            if(bindings.stream().noneMatch(b->b.nodeCode().equals(content.path("target").path("id").asText())&&"HEDEF".equals(b.role()))) throw rejected("Hedef rolü uyuşmuyor.");
        } else for (var dataset : content.path("datasets")) {
            var binding = bindings.stream().filter(b -> b.nodeCode().equals(dataset.path("id").asText())).findFirst().orElseThrow();
            String expectedRole = "SOURCE".equals(dataset.path("role").asText()) ? "KAYNAK" : "HEDEF";
            if (!binding.role().equals(expectedRole)) throw rejected("Kaynak/hedef rolü uyuşmuyor.");
        }
        var target=bindings.stream().filter(b->"HEDEF".equals(b.role())).findFirst().orElseThrow();
        KnowledgeModuleRegistry.requireCompatible(bundle,
                bindings.stream().filter(b -> "KAYNAK".equals(b.role())).map(ResolvedBinding::databaseType).collect(java.util.stream.Collectors.toSet()),
                target.databaseType());
        var staging = jdbc.sql("""
            select p.uuid project_uuid,fs.uuid physical_uuid,fs.calisma_sema_adi,b.uuid connection_uuid,
                   se.uuid binding_uuid,ms.uuid logical_uuid,
                   fs.yukleme_prefix,fs.entegrasyon_prefix,fs.hata_prefix
            from akis.proje p
            join akis.sema_eslemesi se on se.uuid=:binding
            join akis.mantiksal_sema ms on ms.id=se.mantiksal_sema_id
            join akis.fiziksel_sema fs on fs.id=se.fiziksel_sema_id and fs.uuid=:physical
            join akis.baglanti b on b.id=fs.baglanti_id and b.uuid=:connection
            where p.id=:project and fs.durum='ETKIN' and b.durum='ETKIN' and b.saglayici_turu=:provider
            """).param("provider",target.databaseType()).param("project",context.projectId()).param("binding",target.environmentSchemaBindingUuid())
            .param("physical",target.physicalSchemaUuid()).param("connection",target.connectionVersionUuid()).query((rs,n) -> {
                var node=mapper.createObjectNode();
                node.put("projectUuid",rs.getString("project_uuid"));
                node.put("physicalSchemaUuid",rs.getString("physical_uuid"));
                node.put("owner",StagedMappingDefinition.identifier(rs.getString("calisma_sema_adi")));
                node.put("connectionUuid",rs.getString("connection_uuid"));
                node.put("connectionVersionUuid",rs.getString("connection_uuid"));
                node.put("bindingUuid",rs.getString("binding_uuid")); node.put("bindingVersion",1L);
                node.put("logicalSchemaUuid",rs.getString("logical_uuid"));
                var prefix=mapper.createObjectNode();
                prefix.put("loading",rs.getString("yukleme_prefix")); prefix.put("integration",rs.getString("entegrasyon_prefix")); prefix.put("error",rs.getString("hata_prefix"));
                node.set("prefixes",prefix); node.put("prefixOrigin","PHYSICAL_SCHEMA"); node.put("prefixVersion",1L);
                return node;
            }).optional().orElseThrow(() -> rejected("Hedef fiziksel şeması hedefle aynı sağlayıcıya sahip etkin bir bağlantıya bağlı olmalıdır."));
        var workPolicy=workAreas.get(UUID.fromString(staging.path("projectUuid").asText()),UUID.fromString(staging.path("physicalSchemaUuid").asText()));
        // Same-name owners on different DBs are conservatively treated alike; live preflight proves actual DB/PDB.
        WorkAreaPolicyService.requireAllowed(workPolicy,definition.options(),staging.path("owner").asText().equals(target.physicalSchemaReference()));
        staging.set("workAreaPolicy",mapper.valueToTree(workPolicy));
        var integrationOptions = bundle.modules().get("integration").options();
        // PostgreSQL publishes APPEND, TRUNCATE_LOAD or MERGE in one transaction.
        if ("POSTGRESQL".equals(target.databaseType())) {
            Object writeMode = integrationOptions.get("WRITE_MODE");
            if (writeMode != null && !Set.of("APPEND", "TRUNCATE_LOAD", "MERGE").contains(String.valueOf(writeMode)))
                throw rejected("PostgreSQL hedefi APPEND, TRUNCATE_LOAD veya MERGE yazma modunu destekler: " + writeMode);
        }
        staging.put("nonReversibleDdl", "TRUNCATE_LOAD".equals(integrationOptions.get("WRITE_MODE"))
                && Boolean.TRUE.equals(integrationOptions.get("TRUNCATE_TARGET")));
        var plan=mapper.createObjectNode();
        plan.put("planVersion",1); plan.put("language",AkisKmLanguage.VERSION);
        plan.put("scenarioPlanHash",context.planHash()); plan.put("definitionVersionUuid",context.definitionVersionUuid().toString());
        plan.put("environmentUuid",context.environmentUuid().toString());
        plan.set("staging",staging); plan.set("options",mapper.valueToTree(definition.options()));
        plan.set("columns",content.path("columnMappings").deepCopy());
        var pins=mapper.createObjectNode();
        bundle.modules().forEach((role,module) -> {
            var pin=mapper.createObjectNode(); pin.put("versionUuid",module.versionUuid().toString());
            pin.put("contentHash",module.contentHash()); pin.put("source",module.source()); pin.put("kind",module.kind());
            pin.set("commands", mapper.valueToTree(AkisKmLanguage.parse(module.source()).commands()));
            pin.set("options", mapper.valueToTree(module.options()));
            pin.set("optionSchema", module.optionSchema());
            pins.set(role,pin);
        });
        plan.set("modules",pins); plan.set("steps",mapper.valueToTree(bundle.plan().steps()));
        var resolved=mapper.createArrayNode();
        bindings.stream().sorted(Comparator.comparing(ResolvedBinding::nodeCode)).forEach(binding -> {
            var node=mapper.createObjectNode(); node.put("nodeCode",binding.nodeCode()); node.put("role",binding.role());
            node.put("owner",StagedMappingDefinition.identifier(binding.physicalSchemaReference()));
            node.put("objectName",StagedMappingDefinition.identifier(binding.dataObjectReference()));
            node.put("connectionVersionUuid",binding.connectionVersionUuid().toString());
            node.put("physicalSchemaUuid",binding.physicalSchemaUuid().toString());
            node.put("schemaSnapshotUuid",binding.targetSnapshotUuid().toString());
            node.put("schemaSnapshotFingerprint",binding.targetSnapshotFingerprint()); resolved.add(node);
        });
        plan.set("bindings",resolved);
        plan.set("compiledCommands",StagedSqlPreview.render(mapper,jdbc,definition,plan));
        plan.put("physicalPlanHash",KmCanonical.hash(mapper,plan));
        return KmCanonical.normalize(mapper,plan);
    }
    /** Statement preview for the pre-run report; derived from the compiled plan, never executed. */
    JsonNode sqlPreview(PublicationContext context, JsonNode plan) {
        JsonNode content = context.scenarioPlan().path("executable").path("definition");
        StagedMappingDefinition definition;
        try { definition = StagedMappingDefinition.parse(content); }
        catch (IllegalArgumentException invalid) { throw rejected(invalid.getMessage()); }
        return plan.path("compiledCommands").isArray() ? plan.path("compiledCommands") : StagedSqlPreview.render(mapper, jdbc, definition, plan);
    }
    private static ApiException rejected(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"STAGED_PLAN_REJECTED",message); }
}

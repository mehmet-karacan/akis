package tr.com.innova.akis.publication;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.*;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.topology.WorkPrefixService;
import static tr.com.innova.akis.publication.PublicationModels.*;

/** Metadata-only physical plan preparation; never opens a business database connection. */
@Component
final class StagedMappingPlanner {
    private final KnowledgeModuleRegistry modules;
    private final WorkPrefixService prefixes;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final WorkAreaPolicyService workAreas;
    StagedMappingPlanner(KnowledgeModuleRegistry modules, WorkPrefixService prefixes, JdbcClient jdbc, ObjectMapper mapper,WorkAreaPolicyService workAreas) {
        this.modules=modules; this.prefixes=prefixes; this.jdbc=jdbc; this.mapper=mapper; this.workAreas=workAreas;
    }
    JsonNode compile(PublicationContext context, List<ResolvedBinding> bindings) {
        JsonNode content = context.scenarioPlan().path("executable").path("definition");
        if (!context.definitionContentHash().equals(KmCanonical.hash(mapper, content))
                || !context.planHash().equals(KmCanonical.hash(mapper, context.scenarioPlan()))) throw rejected("Senaryo bütünlük kontrolü başarısız.");
        StagedMappingDefinition definition;
        try { definition = StagedMappingDefinition.parse(content); }
        catch (IllegalArgumentException invalid) { throw rejected(invalid.getMessage()); }
        var bundle = modules.resolve(context.projectId(), definition);
        if (bindings.size()!=2 || bindings.stream().anyMatch(b -> !"ORACLE".equals(b.databaseType()) || !Set.of("TABLE", "TABLO").contains(b.dataObjectType())))
            throw rejected("Tek Oracle kaynak/hedef tablo bağı gerekir.");
        Set<String> expected = new HashSet<>();
        content.path("datasets").forEach(d -> expected.add(d.path("id").asText()));
        if (!expected.equals(new HashSet<>(bindings.stream().map(ResolvedBinding::nodeCode).toList()))) throw rejected("Mapping veri bağları uyuşmuyor.");
        for (var dataset : content.path("datasets")) {
            var binding = bindings.stream().filter(b -> b.nodeCode().equals(dataset.path("id").asText())).findFirst().orElseThrow();
            String expectedRole = "SOURCE".equals(dataset.path("role").asText()) ? "KAYNAK" : "HEDEF";
            if (!binding.role().equals(expectedRole)) throw rejected("Kaynak/hedef rolü uyuşmuyor.");
        }
        var staging = jdbc.sql("""
            select p.uuid project_uuid,fs.uuid physical_uuid,fs.sema_adi,bs.uuid connection_uuid,se.uuid binding_uuid,se.versiyon_no
            from akis.proje p
            join akis.mantiksal_sema ms on ms.proje_id=p.id and ms.uuid=:logical
            join akis.sema_eslemesi se on se.proje_id=p.id and se.mantiksal_sema_id=ms.id and se.ortam_id=:environment
            join akis.fiziksel_sema fs on fs.proje_id=p.id and fs.id=se.fiziksel_sema_id
            join akis.baglanti_surumu bs on bs.proje_id=p.id and bs.id=se.baglanti_surumu_id and bs.baglanti_id=fs.baglanti_id
            join akis.baglanti b on b.proje_id=p.id and b.id=bs.baglanti_id
            where p.id=:project and ms.arsivlenme_zamani is null and fs.arsivlenme_zamani is null
              and b.arsivlenme_zamani is null and bs.durum='ETKIN' and b.saglayici_turu='ORACLE'
            """).param("project",context.projectId()).param("logical",definition.logicalSchemaUuid())
            .param("environment",context.environmentId()).query((rs,n) -> {
                var node=mapper.createObjectNode();
                node.put("projectUuid",rs.getString("project_uuid"));
                node.put("physicalSchemaUuid",rs.getString("physical_uuid"));
                node.put("owner",StagedMappingDefinition.identifier(rs.getString("sema_adi")));
                node.put("connectionVersionUuid",rs.getString("connection_uuid"));
                node.put("bindingUuid",rs.getString("binding_uuid")); node.put("bindingVersion",rs.getLong("versiyon_no"));
                return node;
            }).optional().orElseThrow(() -> rejected("Çalışma mantıksal şeması için etkin Oracle ortam eşlemesi gerekir."));
        var prefix = prefixes.get(UUID.fromString(staging.path("projectUuid").asText()), null,
                UUID.fromString(staging.path("physicalSchemaUuid").asText()));
        staging.set("prefixes",mapper.valueToTree(prefix.prefixes()));
        staging.put("prefixOrigin",prefix.origin()); staging.put("prefixVersion",prefix.version());
        staging.put("logicalSchemaUuid",definition.logicalSchemaUuid().toString());
        var workPolicy=workAreas.get(UUID.fromString(staging.path("projectUuid").asText()),UUID.fromString(staging.path("physicalSchemaUuid").asText()));
        var target=bindings.stream().filter(b->"HEDEF".equals(b.role())).findFirst().orElseThrow();
        // Same-name owners on different DBs are conservatively treated alike; live preflight proves actual DB/PDB.
        WorkAreaPolicyService.requireAllowed(workPolicy,definition.options(),staging.path("owner").asText().equals(target.physicalSchemaReference()));
        staging.set("workAreaPolicy",mapper.valueToTree(workPolicy));
        var plan=mapper.createObjectNode();
        plan.put("planVersion",1); plan.put("language",AkisKmLanguage.VERSION);
        plan.put("scenarioPlanHash",context.planHash()); plan.put("definitionVersionUuid",context.definitionVersionUuid().toString());
        plan.put("environmentUuid",context.environmentUuid().toString());
        plan.set("staging",staging); plan.set("options",mapper.valueToTree(definition.options()));
        plan.set("columns",content.path("columnMappings").deepCopy());
        var pins=mapper.createObjectNode();
        bundle.modules().forEach((role,module) -> {
            var pin=mapper.createObjectNode(); pin.put("versionUuid",module.versionUuid().toString());
            pin.put("contentHash",module.contentHash()); pin.put("source",module.source()); pin.put("kind",module.kind()); pins.set(role,pin);
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
        plan.put("physicalPlanHash",KmCanonical.hash(mapper,plan));
        return KmCanonical.normalize(mapper,plan);
    }
    private static ApiException rejected(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"STAGED_PLAN_REJECTED",message); }
}

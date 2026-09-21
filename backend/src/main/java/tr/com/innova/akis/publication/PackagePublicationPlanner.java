package tr.com.innova.akis.publication;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.publication.PublicationModels.PublicationContext;

/**
 * Pins what a package run needs: for every MAPPING/PROCEDURE step the step object's newest active publication
 * in the same environment (ODI: a package scenario references generated scenarios), for every variable step the
 * variable's refresh contract and its Oracle binding in the environment. Nothing is resolved again at run time.
 */
@Component
public class PackagePublicationPlanner {
    public static final String CAPABILITY = "ORACLE_PACKAGE_V1";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    PackagePublicationPlanner(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public ObjectNode compile(PublicationContext context) {
        JsonNode content = context.scenarioPlan().path("executable").path("definition");
        ObjectNode plan = mapper.createObjectNode();
        plan.put("planVersion", 1);
        plan.put("firstStepId", content.path("firstStepId").asText());
        ArrayNode steps = mapper.createArrayNode();
        Set<String> ids = new HashSet<>();
        for (JsonNode step : content.path("steps")) {
            String id = step.path("id").asText();
            String type = step.path("type").asText();
            if (id.isBlank() || !ids.add(id)) throw validation("Paket adım kimlikleri benzersiz olmalıdır.");
            ObjectNode pinned = mapper.createObjectNode();
            pinned.put("id", id);
            pinned.put("type", type);
            pinned.put("name", step.path("name").asText(id));
            UUID definitionUuid = uuid(step.path("definitionUuid").asText(), "Paket adımı '" + pinned.path("name").asText() + "' bir nesneye bağlı değil.");
            pinned.put("definitionUuid", definitionUuid.toString());
            switch (type) {
                case "MAPPING", "PROCEDURE" -> pinned.set("publication", childPublication(context, definitionUuid, pinned.path("name").asText()));
                case "VARIABLE_REFRESH", "VARIABLE_EVALUATE" -> {
                    pinned.set("variable", variableBinding(context, definitionUuid, pinned.path("name").asText()));
                    if (step.has("evaluate")) pinned.set("evaluate", step.get("evaluate").deepCopy());
                }
                default -> throw validation("Paket adım türü çalıştırılamaz: " + type);
            }
            steps.add(pinned);
        }
        if (!ids.contains(plan.path("firstStepId").asText())) throw validation("Paketin ilk adımı seçilmemiş.");
        plan.set("steps", steps);
        ArrayNode transitions = mapper.createArrayNode();
        for (JsonNode edge : content.path("transitions")) {
            if (!ids.contains(edge.path("fromStepId").asText()) || !ids.contains(edge.path("toStepId").asText()))
                throw validation("Paket geçişi bilinmeyen bir adıma işaret ediyor.");
            ObjectNode t = mapper.createObjectNode();
            t.put("fromStepId", edge.path("fromStepId").asText());
            t.put("toStepId", edge.path("toStepId").asText());
            t.put("outcome", edge.path("outcome").asText("SUCCESS"));
            transitions.add(t);
        }
        plan.set("transitions", transitions);
        return plan;
    }

    private ObjectNode childPublication(PublicationContext context, UUID definitionUuid, String stepName) {
        return jdbc.sql("""
                select y.uuid as publication_uuid, y.yayin_no, t.ad, t.tur,
                       y.fiziksel_manifesto->>'releaseHash' as release_hash,
                       y.fiziksel_manifesto->>'runtimeCapability' as capability,
                       y.fiziksel_manifesto->>'runtimePlanHash' as runtime_plan_hash,
                       tsv.uuid as version_uuid
                  from akis.yayin y
                  join akis.senaryo s on s.id = y.senaryo_id
                  join akis.tanim_surumu tsv on tsv.id = s.tanim_surumu_id
                  join akis.tanim t on t.id = tsv.tanim_id
                 where y.proje_id = :project and y.ortam_id = :environment and y.durum = 'AKTIF'
                   and t.uuid = :definition and t.arsivlenme_zamani is null
                 order by y.yayin_no desc limit 1
                """).param("project", context.projectId()).param("environment", context.environmentId()).param("definition", definitionUuid)
                .query((rs, n) -> {
                    String capability = rs.getString("capability");
                    if (capability == null || capability.endsWith("DEFINITION_ONLY")) throw validation("'" + rs.getString("ad") + "' yayını çalıştırılabilir değil.");
                    ObjectNode node = mapper.createObjectNode();
                    node.put("publicationUuid", rs.getString("publication_uuid"));
                    node.put("publicationNumber", rs.getInt("yayin_no"));
                    node.put("definitionName", rs.getString("ad"));
                    node.put("definitionVersionUuid", rs.getString("version_uuid"));
                    node.put("releaseHash", rs.getString("release_hash"));
                    node.put("runtimeCapability", capability);
                    node.put("runtimePlanHash", rs.getString("runtime_plan_hash"));
                    return node;
                }).optional().orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PACKAGE_STEP_NOT_PUBLISHED",
                        "'" + stepName + "' adımının nesnesi için bu ortamda aktif bir yayın yok; önce o nesneyi hazırlayın (Sürümler → Çalıştırılabilir Sürüm Hazırla)."));
    }

    private ObjectNode variableBinding(PublicationContext context, UUID definitionUuid, String stepName) {
        return jdbc.sql("""
                select t.ad, v.icerik, p.uuid as project_uuid, b.uuid as connection_uuid, fs.uuid as physical_uuid, fs.sema_adi, ms.uuid as logical_uuid
                  from akis.proje p
                  join akis.tanim t on t.proje_id = p.id and t.uuid = :definition and t.tur = 'DEGISKEN' and t.arsivlenme_zamani is null
                  join lateral (select icerik from akis.tanim_surumu s where s.tanim_id = t.id order by s.surum_no desc limit 1) v on true
                  left join akis.mantiksal_sema ms on ms.uuid = (v.icerik->>'logicalSchemaUuid')::uuid and ms.durum = 'ETKIN'
                  left join akis.sema_eslemesi se on se.mantiksal_sema_id = ms.id and se.ortam_id = :environment
                  left join akis.fiziksel_sema fs on fs.id = se.fiziksel_sema_id and fs.durum = 'ETKIN'
                  left join akis.baglanti b on b.id = fs.baglanti_id and b.durum = 'ETKIN' and b.saglayici_turu = 'ORACLE'
                 where p.id = :project
                """).param("project", context.projectId()).param("environment", context.environmentId()).param("definition", definitionUuid)
                .query((rs, n) -> {
                    JsonNode content = mapper.readTree(rs.getString("icerik"));
                    ObjectNode node = mapper.createObjectNode();
                    node.put("definitionName", rs.getString("ad"));
                    node.put("definitionUuid", definitionUuid.toString());
                    node.put("type", content.path("dataType").asText());
                    node.put("valueSource", content.path("valueSource").asText());
                    node.put("historyMode", content.path("historyMode").asText("LATEST"));
                    if ("REFRESH_QUERY".equals(content.path("valueSource").asText())) {
                        if (rs.getString("connection_uuid") == null) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VARIABLE_MAPPING_REQUIRED",
                                "'" + stepName + "' değişkeninin mantıksal şeması için bu ortamda etkin Oracle bağlantısı yok.");
                        node.put("query", content.path("query").asText());
                        node.put("projectUuid", rs.getString("project_uuid"));
                        node.put("environmentUuid", context.environmentUuid().toString());
                        node.put("logicalSchemaUuid", rs.getString("logical_uuid"));
                        node.put("connectionVersionUuid", rs.getString("connection_uuid"));
                        node.put("physicalSchemaUuid", rs.getString("physical_uuid"));
                        node.put("owner", rs.getString("sema_adi"));
                    } else {
                        node.set("value", content.path("defaultValue").isMissingNode() ? content.path("value").deepCopy() : content.path("defaultValue").deepCopy());
                    }
                    return node;
                }).optional().orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PACKAGE_VARIABLE_NOT_VERSIONED",
                        "'" + stepName + "' değişkeninin sürümü yok; önce Sürüm Oluştur."));
    }

    private static UUID uuid(String value, String message) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { throw validation(message); }
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PACKAGE_PLAN_REJECTED", message);
    }
}

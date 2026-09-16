package tr.com.innova.akis.knowledge;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;

@Service
public class KnowledgeModuleRegistry {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final DefinitionContentValidator validator;
    public KnowledgeModuleRegistry(JdbcClient jdbc, ObjectMapper mapper, DefinitionContentValidator validator) {
        this.jdbc = jdbc; this.mapper = mapper; this.validator = validator;
    }
    public record Module(long id, UUID versionUuid, String contentHash, String source, String kind) { }
    public record Bundle(Map<String, Module> modules, AkisKmInterpreter.Plan plan) {
        public Bundle { modules = Map.copyOf(modules); }
    }
    @Transactional(readOnly = true)
    public Bundle resolve(long projectId, StagedMappingDefinition definition) {
        Map<String, Module> modules = new LinkedHashMap<>();
        for (var entry : definition.modules().entrySet()) {
            String kind = switch (entry.getKey()) { case "loading" -> "LKM"; case "integration" -> "IKM"; case "checking" -> "CKM"; default -> throw rejected(); };
            var pin = entry.getValue();
            Module module = jdbc.sql("""
                select v.id,v.uuid,v.icerik_ozeti,v.icerik,v.sema_surumu from akis.tanim_surumu v
                join akis.tanim t on t.id=v.tanim_id and t.proje_id=v.proje_id
                where v.proje_id=:project and v.uuid=:version and t.tur='KNOWLEDGE_MODULE'
                  and t.arsivlenme_zamani is null
                """).param("project", projectId).param("version", pin.versionUuid()).query((rs, n) -> {
                    JsonNode content = mapper.readTree(rs.getString("icerik"));
                    String hash = rs.getString("icerik_ozeti");
                    if (rs.getInt("sema_surumu") != 2 || !hash.equals(pin.contentHash()) || !hash.equals(KmCanonical.hash(mapper, content))) throw rejected();
                    validator.validate(DefinitionType.KNOWLEDGE_MODULE, 2, content);
                    if (!kind.equals(content.path("kmType").asText())) throw rejected();
                    return new Module(rs.getLong("id"), pin.versionUuid(), hash, content.path("source").asText(), kind);
                }).optional().orElseThrow(KnowledgeModuleRegistry::rejected);
            modules.put(entry.getKey(), module);
        }
        var checking = modules.get("checking");
        var plan = AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(modules.get("loading").source(),
                checking == null ? null : checking.source(), modules.get("integration").source()));
        if (!plan.slots().equals(Set.of("WORK_SOURCE_1"))) throw rejected();
        return new Bundle(modules, plan);
    }
    @Transactional
    public void link(long projectId, UUID mappingVersionUuid, Bundle bundle) {
        for (var module : bundle.modules().values()) jdbc.sql("""
            insert into akis.tanim_bagimliligi(proje_id,kaynak_tanim_surumu_id,hedef_tanim_surumu_id,iliski_turu)
            select :project,id,:target,'MODUL_KULLANIR' from akis.tanim_surumu where proje_id=:project and uuid=:source
            on conflict(kaynak_tanim_surumu_id,hedef_tanim_surumu_id,iliski_turu) do nothing
            """).param("project", projectId).param("source", mappingVersionUuid).param("target", module.id()).update();
    }
    private static ApiException rejected() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "KM_VERSION_REJECTED",
                "KM sürümü, türü, proje kapsamı, slotu veya içerik özeti geçersiz. Aynı projede AKIS_KM/1 sürümü seçin.");
    }
}

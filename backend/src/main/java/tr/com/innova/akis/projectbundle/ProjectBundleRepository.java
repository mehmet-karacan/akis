package tr.com.innova.akis.projectbundle;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionType;

@Repository
class ProjectBundleRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    ProjectBundleRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ProjectRow> findProject(UUID projectUuid) {
        return jdbc.sql("""
                        select id, uuid, kod,
                               case when arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                               ad, aciklama
                          from akis.proje
                         where uuid = :uuid
                        """)
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRow(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getString("durum_kodu"),
                        rs.getString("ad"),
                        rs.getString("aciklama")))
                .optional();
    }

    boolean projectCodeExists(String code) {
        return jdbc.sql("select exists(select 1 from akis.proje where kod = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    /** Serializes project-code allocation across every application instance. */
    void lockProjectCodeNamespace() {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('akis.projectbundle.project-code'))")
                .query((rs, rowNum) -> 1)
                .single();
    }

    ExportSnapshot loadSnapshot(UUID projectUuid) {
        ProjectRow project = findProject(projectUuid).orElseThrow();
        List<FolderRow> folders = jdbc.sql("""
                        select id, ust_klasor_id, kod,
                               case when arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                               ad, aciklama
                          from akis.klasor
                         where proje_id = :projectId
                         order by id
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new FolderRow(
                        rs.getLong("id"),
                        rs.getObject("ust_klasor_id", Long.class),
                        rs.getString("kod"),
                        rs.getString("durum_kodu"),
                        rs.getString("ad"),
                        rs.getString("aciklama")))
                .list();
        List<DefinitionRow> definitions = jdbc.sql("""
                        select id, klasor_id, tur, kod,
                               case when arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                               ad, aciklama
                          from akis.tanim
                         where proje_id = :projectId
                         order by tur, kod
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new DefinitionRow(
                        rs.getLong("id"),
                        rs.getObject("klasor_id", Long.class),
                        apiDefinitionType(rs.getString("tur")),
                        rs.getString("kod"),
                        rs.getString("durum_kodu"),
                        rs.getString("ad"),
                        rs.getString("aciklama")))
                .list();
        List<DraftRow> drafts = jdbc.sql("""
                        select tt.tanim_id, tt.sema_surumu, tt.icerik
                          from akis.tanim_taslagi tt
                          join akis.tanim t on t.id = tt.tanim_id
                         where t.proje_id = :projectId
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new DraftRow(
                        rs.getLong("tanim_id"),
                        rs.getInt("sema_surumu"),
                        json(rs.getString("icerik"))))
                .list();
        List<VersionRow> versions = jdbc.sql("""
                        select ts.tanim_id, ts.surum_no, ts.sema_surumu, ts.icerik_ozeti,
                               ts.icerik, ts.aciklama, ts.olusturulma_zamani
                          from akis.tanim_surumu ts
                          join akis.tanim t on t.id = ts.tanim_id
                         where t.proje_id = :projectId
                         order by ts.tanim_id, ts.surum_no
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new VersionRow(
                        rs.getLong("tanim_id"),
                        rs.getInt("surum_no"),
                        rs.getInt("sema_surumu"),
                        rs.getString("icerik_ozeti"),
                        json(rs.getString("icerik")),
                        rs.getString("aciklama"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .list();
        return new ExportSnapshot(project, folders, definitions, drafts, versions);
    }

    JsonNode loadPortableTopology(long projectId) {
        String value = jdbc.sql("""
                select jsonb_build_object(
                  'models',coalesce((select jsonb_agg(jsonb_build_object('code',mo.kod,'name',mo.ad,'description',mo.aciklama,'logicalSchemaCode',m.kod,'technology',mo.teknoloji_kodu,'reverseEnvironmentCode',o.kod,'reverseMode',mo.tersine_muhendislik_modu,'rkmCode',r.kod,'reverseOptions',mo.tersine_muhendislik_secenekleri) order by mo.kod) from akis.model mo join akis.mantiksal_sema m on m.id=mo.mantiksal_sema_id left join akis.ortam o on o.id=mo.tersine_muhendislik_ortam_id left join akis.tanim r on r.id=mo.rkm_tanim_id where mo.proje_id=:p),'[]'::jsonb),
                  'submodels',coalesce((select jsonb_agg(jsonb_build_object('code',a.kod,'name',a.ad,'description',a.aciklama,'modelCode',mo.kod,'parentCode',u.kod) order by mo.kod,a.id) from akis.alt_model a join akis.model mo on mo.id=a.model_id left join akis.alt_model u on u.id=a.ust_alt_model_id where a.proje_id=:p),'[]'::jsonb),
                  'dataObjects',coalesce((select jsonb_agg(jsonb_build_object('code',v.kod,'name',v.ad,'reference',v.nesne_referansi,'type',v.tur,'modelCode',mo.kod,'submodelCode',a.kod,'querySchemaVersion',v.sorgu_sema_surumu,'queryDefinition',v.sorgu_tanimi) order by mo.kod,v.kod) from akis.veri_nesnesi v join akis.model mo on mo.id=v.model_id left join akis.alt_model a on a.id=v.alt_model_id where v.proje_id=:p),'[]'::jsonb)
                )::text
                """).param("p", projectId).query(String.class).single();
        return json(value);
    }

    void importPortableTopology(long projectId, JsonNode topology) {
        if (topology == null || !topology.isObject()) return;
        // Topology (connections, schemas, environments) is global and is referenced by code, never imported.
        Map<String,Long> logical=new HashMap<>(), environments=new HashMap<>(), models=new HashMap<>(), submodels=new HashMap<>();
        for(var row: jdbc.sql("select id, kod from akis.mantiksal_sema").query((r,n)->Map.entry(r.getString("kod"), r.getLong("id"))).list()) logical.put(row.getKey(), row.getValue());
        for(var row: jdbc.sql("select id, kod from akis.ortam").query((r,n)->Map.entry(r.getString("kod"), r.getLong("id"))).list()) environments.put(row.getKey(), row.getValue());
        for(JsonNode item:array(topology,"models")) if(!logical.containsKey(text(item,"logicalSchemaCode"))) throw new IllegalStateException("Mantıksal şema tanımlı değil: "+text(item,"logicalSchemaCode"));
        for(JsonNode item:array(topology,"models")){String mode=nullable(item,"reverseMode");if(mode==null)mode="STANDARD";String options=item.hasNonNull("reverseOptions")?item.get("reverseOptions").toString():"{}";long id=jdbc.sql("insert into akis.model(proje_id,mantiksal_sema_id,teknoloji_kodu,tersine_muhendislik_ortam_id,tersine_muhendislik_modu,rkm_tanim_id,tersine_muhendislik_secenekleri,kod,ad,aciklama) values(:p,:m,:technology,:environment,:mode,(select id from akis.tanim where kod=:rkm and tur='KNOWLEDGE_MODULE' and (proje_id=:p or proje_id is null) order by proje_id nulls last limit 1),cast(:options as jsonb),:c,:n,:d) returning id").param("p",projectId).param("m",logical.get(text(item,"logicalSchemaCode"))).param("technology",item.hasNonNull("technology")?text(item,"technology"):"ORACLE").param("environment",environments.get(nullable(item,"reverseEnvironmentCode")),Types.BIGINT).param("mode",mode).param("rkm",nullable(item,"rkmCode"),Types.VARCHAR).param("options",options).param("c",text(item,"code")).param("n",text(item,"name")).param("d",nullable(item,"description"),Types.VARCHAR).query(Long.class).single();models.put(text(item,"code"),id);}
        for(JsonNode item:array(topology,"submodels")){Long parent=item.get("parentCode")==null||item.get("parentCode").isNull()?null:submodels.get(text(item,"modelCode")+":"+text(item,"parentCode"));long id=jdbc.sql("insert into akis.alt_model(proje_id,model_id,ust_alt_model_id,kod,ad,aciklama) values(:p,:m,:u,:c,:n,:d) returning id").param("p",projectId).param("m",models.get(text(item,"modelCode"))).param("u",parent,Types.BIGINT).param("c",text(item,"code")).param("n",text(item,"name")).param("d",nullable(item,"description"),Types.VARCHAR).query(Long.class).single();submodels.put(text(item,"modelCode")+":"+text(item,"code"),id);}
        for(JsonNode item:array(topology,"dataObjects")) jdbc.sql("insert into akis.veri_nesnesi(proje_id,model_id,alt_model_id,kod,ad,nesne_referansi,tur,sorgu_sema_surumu,sorgu_tanimi) values(:p,:m,:a,:c,:n,:r,:t,:qv,cast(:q as jsonb))").param("p",projectId).param("m",models.get(text(item,"modelCode"))).param("a",item.get("submodelCode")==null||item.get("submodelCode").isNull()?null:submodels.get(text(item,"modelCode")+":"+text(item,"submodelCode")),Types.BIGINT).param("c",text(item,"code")).param("n",text(item,"name")).param("r",text(item,"reference")).param("t",text(item,"type")).param("qv",integer(item,"querySchemaVersion"),Types.INTEGER).param("q",item.get("queryDefinition")==null||item.get("queryDefinition").isNull()?null:item.get("queryDefinition").toString(),Types.VARCHAR).update();
    }

    private Iterable<JsonNode> array(JsonNode parent,String name){JsonNode value=parent.get(name);return value!=null&&value.isArray()?value:List.of();}
    private String text(JsonNode node,String name){return node.get(name).asString();}
    private String nullable(JsonNode node,String name){JsonNode value=node.get(name);return value==null||value.isNull()?null:value.asString();}
    private Integer integer(JsonNode node,String name){JsonNode value=node.get(name);return value==null||value.isNull()?null:value.asInt();}

    ProjectRow insertProject(
            UUID uuid, String code, String status, String name, String description) {
        return jdbc.sql("""
                        insert into akis.proje(uuid, kod, ad, aciklama, arsivlenme_zamani)
                        values (:uuid, :code, :name, :description,
                                case when :status='AKTIF' then null else current_timestamp end)
                        returning id, uuid, kod,
                                  case when arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                                  ad, aciklama
                        """)
                .param("uuid", uuid)
                .param("code", code)
                .param("status", status)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .query((rs, rowNum) -> new ProjectRow(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getString("kod"), rs.getString("durum_kodu"),
                        rs.getString("ad"), rs.getString("aciklama")))
                .single();
    }

    long insertFolder(
            long projectId, Long parentId, String code,
            String status, String name, String description) {
        return jdbc.sql("""
                        insert into akis.klasor(
                            proje_id, ust_klasor_id, kod, ad, aciklama, arsivlenme_zamani)
                        values (:projectId, :parentId, :code, :name, :description,
                                case when :status='AKTIF' then null else current_timestamp end)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("parentId", parentId, Types.BIGINT)
                .param("code", code)
                .param("status", status)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    long insertDefinition(
            long projectId, Long folderId, DefinitionType type, String code,
            String status, String name, String description) {
        return jdbc.sql("""
                        insert into akis.tanim(
                            proje_id, klasor_id, kapsam, tur,
                            kod, ad, aciklama, arsivlenme_zamani)
                        values (:projectId, :folderId, 'PROJE', :type,
                                :code, :name, :description,
                                case when :status='AKTIF' then null else current_timestamp end)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("folderId", folderId, Types.BIGINT)
                .param("type", storedDefinitionType(type))
                .param("code", code)
                .param("status", status)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    void insertDraft(long definitionId, int schemaVersion, JsonNode content) {
        jdbc.sql("""
                        insert into akis.tanim_taslagi(proje_id, tanim_id, sema_surumu, icerik)
                        select proje_id, id, :schemaVersion, cast(:content as jsonb)
                          from akis.tanim where id=:definitionId
                        """)
                .param("definitionId", definitionId)
                .param("schemaVersion", schemaVersion)
                .param("content", content.toString())
                .update();
    }

    void insertVersion(long definitionId, VersionRow version) {
        jdbc.sql("""
                        insert into akis.tanim_surumu(
                            proje_id, tanim_id, surum_no, sema_surumu, icerik_ozeti,
                            icerik, aciklama, olusturulma_zamani)
                        select proje_id, id, :versionNumber, :schemaVersion, :contentHash,
                               cast(:content as jsonb), :description, :createdAt
                          from akis.tanim where id=:definitionId
                        """)
                .param("definitionId", definitionId)
                .param("versionNumber", version.versionNumber())
                .param("schemaVersion", version.schemaVersion())
                .param("contentHash", version.contentHash())
                .param("content", version.content().toString())
                .param("description", version.description(), Types.VARCHAR)
                .param("createdAt", version.createdAt(), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored bundle JSON could not be read.", exception);
        }
    }

    private String storedDefinitionType(DefinitionType type) {
        return switch(type) {
            case MAPPING -> "MAPPING"; case REUSABLE_MAPPING -> "YENIDEN_KULLANILABILIR_MAPPING";
            case PACKAGE -> "PAKET"; case PROCEDURE -> "PROSEDUR"; case VARIABLE -> "DEGISKEN";
            case SEQUENCE -> "SEQUENCE";
            case KNOWLEDGE_MODULE -> "KNOWLEDGE_MODULE"; case LOAD_PLAN -> "LOAD_PLAN";
        };
    }

    private DefinitionType apiDefinitionType(String type) {
        return switch(type) {
            case "MAPPING" -> DefinitionType.MAPPING; case "YENIDEN_KULLANILABILIR_MAPPING" -> DefinitionType.REUSABLE_MAPPING;
            case "PAKET" -> DefinitionType.PACKAGE; case "PROSEDUR" -> DefinitionType.PROCEDURE;
            case "DEGISKEN" -> DefinitionType.VARIABLE; case "SEQUENCE" -> DefinitionType.SEQUENCE;
            case "KNOWLEDGE_MODULE" -> DefinitionType.KNOWLEDGE_MODULE;
            case "LOAD_PLAN" -> DefinitionType.LOAD_PLAN; default -> throw new IllegalStateException("Unknown definition type: "+type);
        };
    }

    record ProjectRow(long id, UUID uuid, String code, String status, String name, String description) {
    }

    record FolderRow(
            long id, Long parentId, String code,
            String status, String name, String description) {
    }

    record DefinitionRow(
            long id, Long folderId, DefinitionType type, String code,
            String status, String name, String description) {
    }

    record DraftRow(long definitionId, int schemaVersion, JsonNode content) {
    }

    record VersionRow(
            long definitionId, int versionNumber, int schemaVersion, String contentHash,
            JsonNode content, String description, OffsetDateTime createdAt) {
    }

    record ExportSnapshot(
            ProjectRow project,
            List<FolderRow> folders,
            List<DefinitionRow> definitions,
            List<DraftRow> drafts,
            List<VersionRow> versions) {
    }
}

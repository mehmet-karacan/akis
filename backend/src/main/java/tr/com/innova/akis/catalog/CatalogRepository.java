package tr.com.innova.akis.catalog;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.catalog.CatalogModels.DataObjectRow;
import tr.com.innova.akis.catalog.CatalogModels.LogicalSchemaRef;
import tr.com.innova.akis.catalog.CatalogModels.ModelRow;
import tr.com.innova.akis.catalog.CatalogModels.ProjectRef;
import tr.com.innova.akis.catalog.CatalogModels.SubmodelRow;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CatalogRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ProjectRef> findProject(UUID projectUuid) {
        return jdbc.sql("select id from akis.proje where uuid = :uuid")
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRef(rs.getLong("id")))
                .optional();
    }

    Optional<LogicalSchemaRef> findLogicalSchema(long projectId, UUID uuid) {
        return jdbc.sql("""
                        select id, uuid
                          from akis.mantiksal_sema
                         where uuid = :uuid
                        """)
                .param("uuid", uuid)
                .query((rs, rowNum) -> new LogicalSchemaRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class)))
                .optional();
    }

    ModelRow createModel(
            long projectId,
            long logicalSchemaId,
            UUID uuid,
            String technologyCode,
            Long reverseEnvironmentId,
            String reverseMode,
            Long rkmDefinitionId,
            JsonNode reverseOptions,
            String code,
            String name,
            String description) {
        jdbc.sql("""
                        insert into akis.model(
                            proje_id, mantiksal_sema_id, uuid, teknoloji_kodu,
                            tersine_muhendislik_ortam_id, tersine_muhendislik_modu,
                            rkm_tanim_id, tersine_muhendislik_secenekleri, kod, ad, aciklama)
                        values (:projectId, :logicalSchemaId, :uuid, :technologyCode,
                                :reverseEnvironmentId, :reverseMode, :rkmDefinitionId,
                                cast(:reverseOptions as jsonb), :code, :name, :description)
                        """)
                .param("projectId", projectId)
                .param("logicalSchemaId", logicalSchemaId)
                .param("uuid", uuid)
                .param("technologyCode", technologyCode)
                .param("reverseEnvironmentId", reverseEnvironmentId, Types.BIGINT)
                .param("reverseMode", reverseMode)
                .param("rkmDefinitionId", rkmDefinitionId, Types.BIGINT)
                .param("reverseOptions", reverseOptions.toString())
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .update();
        return findModel(projectId, uuid).orElseThrow();
    }

    /** Compatibility entry point for repository-level callers that do not configure reverse engineering. */
    ModelRow createModel(long projectId, long logicalSchemaId, UUID uuid,
            String code, String name, String description) {
        return createModel(projectId, logicalSchemaId, uuid, "ORACLE", null,
                "STANDARD", null, objectMapper.createObjectNode(), code, name, description);
    }

    ModelRow updateModel(
            long projectId, UUID uuid, long logicalSchemaId, String technologyCode,
            Long reverseEnvironmentId, String reverseMode, Long rkmDefinitionId,
            JsonNode reverseOptions, String name, String description, long expectedVersion) {
        int updated = jdbc.sql("""
                        update akis.model
                           set mantiksal_sema_id = :logicalSchemaId,
                               teknoloji_kodu = :technologyCode,
                               tersine_muhendislik_ortam_id = :reverseEnvironmentId,
                               tersine_muhendislik_modu = :reverseMode,
                               rkm_tanim_id = :rkmDefinitionId,
                               tersine_muhendislik_secenekleri = cast(:reverseOptions as jsonb),
                               ad = :name, aciklama = :description,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId and uuid = :uuid
                           and arsivlenme_zamani is null and versiyon_no = :expectedVersion
                        """)
                .param("projectId", projectId).param("uuid", uuid)
                .param("logicalSchemaId", logicalSchemaId)
                .param("technologyCode", technologyCode)
                .param("reverseEnvironmentId", reverseEnvironmentId, Types.BIGINT)
                .param("reverseMode", reverseMode)
                .param("rkmDefinitionId", rkmDefinitionId, Types.BIGINT)
                .param("reverseOptions", reverseOptions.toString())
                .param("name", name).param("description", description, Types.VARCHAR)
                .param("expectedVersion", expectedVersion).update();
        if (updated != 1) throw new IllegalStateException("MODEL_VERSION_CONFLICT");
        return findModel(projectId, uuid).orElseThrow();
    }

    Optional<Long> findEnvironmentId(long projectId, UUID uuid) {
        if (uuid == null) return Optional.empty();
        return jdbc.sql("select id from akis.ortam where uuid=:uuid and durum='ETKIN'")
                .param("uuid", uuid).query(Long.class).optional();
    }

    Optional<Long> findRkmDefinitionId(long projectId, UUID uuid) {
        if (uuid == null) return Optional.empty();
        return jdbc.sql("""
                        select t.id from akis.tanim t
                         where t.uuid=:uuid and t.tur='KNOWLEDGE_MODULE'
                           and t.arsivlenme_zamani is null and (t.proje_id=:projectId or t.proje_id is null)
                           and exists (
                               select 1 from akis.tanim_taslagi d
                                where d.tanim_id=t.id and d.icerik->>'kmType'='RKM'
                               union all
                               select 1 from akis.tanim_surumu v
                                where v.tanim_id=t.id and v.icerik->>'kmType'='RKM'
                           )
                        """).param("uuid", uuid).param("projectId", projectId).query(Long.class).optional();
    }

    List<String> logicalSchemaProviders(long projectId, long logicalSchemaId) {
        return jdbc.sql("""
                        select distinct e.saglayici_turu
                          from akis.sema_eslemesi e
                         where e.mantiksal_sema_id=:logicalSchemaId
                        """).param("logicalSchemaId", logicalSchemaId)
                .query(String.class).list();
    }

    List<ModelRow> listModels(long projectId) {
        return jdbc.sql(modelSelect() + " where m.proje_id = :projectId and m.arsivlenme_zamani is null order by m.kod")
                .param("projectId", projectId)
                .query(this::mapModel)
                .list();
    }

    Optional<ModelRow> findModel(long projectId, UUID uuid) {
        return jdbc.sql(modelSelect() + " where m.proje_id = :projectId and m.uuid = :uuid and m.arsivlenme_zamani is null")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapModel)
                .optional();
    }

    boolean modelHasSubmodels(long modelId) {
        return Boolean.TRUE.equals(jdbc.sql("select exists(select 1 from akis.alt_model where model_id=:modelId and arsivlenme_zamani is null)")
                .param("modelId", modelId).query(Boolean.class).single());
    }

    boolean archiveModel(long projectId, UUID uuid, long expectedVersion) {
        return jdbc.sql("""
                        update akis.model
                           set arsivlenme_zamani=current_timestamp,
                               guncellenme_zamani=current_timestamp,
                               versiyon_no=versiyon_no+1
                         where proje_id=:projectId and uuid=:uuid
                           and arsivlenme_zamani is null and versiyon_no=:expectedVersion
                        """)
                .param("projectId", projectId).param("uuid", uuid)
                .param("expectedVersion", expectedVersion).update() == 1;
    }

    SubmodelRow createSubmodel(
            long projectId,
            long modelId,
            Long parentId,
            UUID uuid,
            String code,
            String name) {
        jdbc.sql("""
                        insert into akis.alt_model(
                            proje_id, model_id, ust_alt_model_id, uuid, kod, ad)
                        values (:projectId, :modelId, :parentId, :uuid, :code, :name)
                        """)
                .param("projectId", projectId)
                .param("modelId", modelId)
                .param("parentId", parentId, Types.BIGINT)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .update();
        return findSubmodel(projectId, uuid).orElseThrow();
    }

    List<SubmodelRow> listSubmodels(long projectId, long modelId) {
        return jdbc.sql(submodelSelect()
                        + " where s.proje_id = :projectId and s.model_id = :modelId and s.arsivlenme_zamani is null order by s.kod")
                .param("projectId", projectId)
                .param("modelId", modelId)
                .query(this::mapSubmodel)
                .list();
    }

    Optional<SubmodelRow> findSubmodel(long projectId, UUID uuid) {
        return jdbc.sql(submodelSelect()
                        + " where s.proje_id = :projectId and s.uuid = :uuid and s.arsivlenme_zamani is null")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapSubmodel)
                .optional();
    }

    DataObjectRow createDataObject(
            long projectId,
            long modelId,
            Long submodelId,
            UUID uuid,
            String code,
            String objectReference,
            String type,
            Integer querySchemaVersion,
            JsonNode queryDefinition,
            String name) {
        jdbc.sql("""
                        insert into akis.veri_nesnesi(
                            proje_id, model_id, alt_model_id, uuid, kod, nesne_referansi,
                            tur, sorgu_sema_surumu, sorgu_tanimi, ad)
                        values (:projectId, :modelId, :submodelId, :uuid, :code, :objectReference,
                                :type, :querySchemaVersion, cast(:queryDefinition as jsonb), :name)
                        """)
                .param("projectId", projectId)
                .param("modelId", modelId)
                .param("submodelId", submodelId, Types.BIGINT)
                .param("uuid", uuid)
                .param("code", code)
                .param("objectReference", objectReference)
                .param("type", databaseDataObjectType(type))
                .param("querySchemaVersion", querySchemaVersion, Types.INTEGER)
                .param("queryDefinition",
                        queryDefinition == null ? null : queryDefinition.toString(), Types.VARCHAR)
                .param("name", name)
                .update();
        return findDataObject(projectId, uuid).orElseThrow();
    }

    Optional<DataObjectRow> moveDataObject(long projectId, long modelId, UUID uuid, Long submodelId, long expectedVersion) {
        int updated = jdbc.sql("""
                update akis.veri_nesnesi d
                   set alt_model_id = :submodelId, versiyon_no = versiyon_no + 1,
                       guncellenme_zamani = current_timestamp
                 where d.proje_id = :projectId and d.model_id = :modelId and d.uuid = :uuid
                   and d.versiyon_no = :expectedVersion and d.arsivlenme_zamani is null
                   and exists (select 1 from akis.model m where m.id = d.model_id
                       and m.proje_id = :projectId and m.arsivlenme_zamani is null)
                   and (:submodelId is null or exists (select 1 from akis.alt_model s
                       where s.id = :submodelId and s.model_id = :modelId and s.proje_id = :projectId
                         and s.arsivlenme_zamani is null))
                """)
                .param("projectId", projectId).param("modelId", modelId).param("uuid", uuid)
                .param("submodelId", submodelId, Types.BIGINT).param("expectedVersion", expectedVersion).update();
        return updated == 1 ? findDataObject(projectId, uuid) : Optional.empty();
    }

    /** Names of active (non-archived) definitions still bound to the data object; archiving is refused while any exist. */
    List<String> definitionsReferencingDataObject(long projectId, long dataObjectId) {
        return jdbc.sql("""
                        select distinct t.ad from akis.tanim_veri_nesnesi b
                          join akis.tanim_surumu v on v.proje_id = b.proje_id and v.id = b.tanim_surumu_id
                          join akis.tanim t on t.proje_id = v.proje_id and t.id = v.tanim_id
                         where b.proje_id = :projectId and b.veri_nesnesi_id = :dataObjectId and t.arsivlenme_zamani is null
                         order by t.ad
                        """)
                .param("projectId", projectId).param("dataObjectId", dataObjectId).query(String.class).list();
    }

    boolean archiveDataObject(long projectId, UUID uuid, long expectedVersion) {
        return jdbc.sql("""
                        update akis.veri_nesnesi
                           set arsivlenme_zamani=current_timestamp, guncellenme_zamani=current_timestamp, versiyon_no=versiyon_no+1
                         where proje_id=:projectId and uuid=:uuid and arsivlenme_zamani is null and versiyon_no=:expectedVersion
                        """)
                .param("projectId", projectId).param("uuid", uuid).param("expectedVersion", expectedVersion).update() == 1;
    }

    boolean submodelHasContent(long submodelId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists(select 1 from akis.veri_nesnesi d where d.alt_model_id=:id and d.arsivlenme_zamani is null)
                            or exists(select 1 from akis.alt_model s where s.ust_alt_model_id=:id and s.arsivlenme_zamani is null)
                        """)
                .param("id", submodelId).query(Boolean.class).single());
    }

    boolean archiveSubmodel(long projectId, UUID uuid, long expectedVersion) {
        return jdbc.sql("""
                        update akis.alt_model
                           set arsivlenme_zamani=current_timestamp, guncellenme_zamani=current_timestamp, versiyon_no=versiyon_no+1
                         where proje_id=:projectId and uuid=:uuid and arsivlenme_zamani is null and versiyon_no=:expectedVersion
                        """)
                .param("projectId", projectId).param("uuid", uuid).param("expectedVersion", expectedVersion).update() == 1;
    }

    /** Sensitive (protected) column names of a data object, upper-case, ordered. */
    List<String> sensitiveColumns(long projectId, long dataObjectId) {
        return jdbc.sql("select kolon_adi from akis.veri_nesnesi_kolon_politikasi where proje_id=:project and veri_nesnesi_id=:object order by kolon_adi")
                .param("project", projectId).param("object", dataObjectId).query(String.class).list();
    }

    void replaceSensitiveColumns(long projectId, long dataObjectId, List<String> columns) {
        jdbc.sql("delete from akis.veri_nesnesi_kolon_politikasi where proje_id=:project and veri_nesnesi_id=:object")
                .param("project", projectId).param("object", dataObjectId).update();
        for (String column : columns) {
            jdbc.sql("insert into akis.veri_nesnesi_kolon_politikasi(proje_id,veri_nesnesi_id,kolon_adi) values(:project,:object,:column)")
                    .param("project", projectId).param("object", dataObjectId).param("column", column).update();
        }
    }

    List<DataObjectRow> listDataObjects(long projectId, long modelId) {
        return jdbc.sql(dataObjectSelect()
                        + " where d.proje_id = :projectId and d.model_id = :modelId order by d.kod")
                .param("projectId", projectId)
                .param("modelId", modelId)
                .query(this::mapDataObject)
                .list();
    }

    Optional<DataObjectRow> findDataObject(long projectId, UUID uuid) {
        return jdbc.sql(dataObjectSelect()
                        + " where d.proje_id = :projectId and d.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapDataObject)
                .optional();
    }

    private String modelSelect() {
        return """
                select m.id, m.uuid, m.mantiksal_sema_id,
                       l.uuid as logical_schema_uuid, m.teknoloji_kodu,
                       o.uuid as reverse_environment_uuid,
                       m.tersine_muhendislik_modu, r.uuid as rkm_definition_uuid,
                       m.tersine_muhendislik_secenekleri, m.kod,
                       case when m.arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                       m.ad, m.aciklama,
                       (select count(*) from akis.veri_nesnesi d
                         where d.model_id = m.id and d.arsivlenme_zamani is null) as nesne_sayisi,
                       (select max(g.kesif_zamani)
                          from akis.sema_goruntusu g
                          join akis.veri_nesnesi d on d.id = g.veri_nesnesi_id
                         where d.model_id = m.id) as son_metadata_guncellemesi,
                       m.versiyon_no
                  from akis.model m
                  join akis.mantiksal_sema l on l.id = m.mantiksal_sema_id
                  left join akis.ortam o on o.id = m.tersine_muhendislik_ortam_id
                  left join akis.tanim r on r.id = m.rkm_tanim_id
                """;
    }

    private ModelRow mapModel(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ModelRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("mantiksal_sema_id"),
                rs.getObject("logical_schema_uuid", UUID.class),
                rs.getString("teknoloji_kodu"),
                rs.getObject("reverse_environment_uuid", UUID.class),
                rs.getString("tersine_muhendislik_modu"),
                rs.getObject("rkm_definition_uuid", UUID.class),
                jsonOrNull(rs.getString("tersine_muhendislik_secenekleri")),
                rs.getString("kod"), rs.getString("durum_kodu"), rs.getString("ad"),
                rs.getString("aciklama"), rs.getLong("nesne_sayisi"),
                rs.getObject("son_metadata_guncellemesi", java.time.OffsetDateTime.class),
                rs.getLong("versiyon_no"));
    }

    private String submodelSelect() {
        return """
                select s.id, s.uuid, s.model_id, m.uuid as model_uuid,
                       p.uuid as parent_uuid, s.kod, s.ad, s.versiyon_no
                  from akis.alt_model s
                  join akis.model m on m.id = s.model_id
                  left join akis.alt_model p on p.id = s.ust_alt_model_id
                """;
    }

    private SubmodelRow mapSubmodel(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SubmodelRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getLong("model_id"),
                rs.getObject("model_uuid", UUID.class), rs.getObject("parent_uuid", UUID.class),
                rs.getString("kod"), rs.getString("ad"), rs.getLong("versiyon_no"));
    }

    private String dataObjectSelect() {
        return """
                select d.id, d.uuid, d.model_id, m.uuid as model_uuid,
                       d.alt_model_id, s.uuid as submodel_uuid, d.kod,
                       d.nesne_referansi,
                       case d.tur when 'GORUNUM' then 'VIEW' else d.tur end as tur_kodu,
                       case when d.arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum_kodu,
                       d.sorgu_sema_surumu as sorgu_tanim_surumu,
                       d.sorgu_tanimi, d.ad, d.versiyon_no
                  from akis.veri_nesnesi d
                  join akis.model m on m.id = d.model_id
                  left join akis.alt_model s on s.id = d.alt_model_id
                """;
    }

    private DataObjectRow mapDataObject(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new DataObjectRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getLong("model_id"),
                rs.getObject("model_uuid", UUID.class), rs.getObject("alt_model_id", Long.class),
                rs.getObject("submodel_uuid", UUID.class), rs.getString("kod"),
                rs.getString("nesne_referansi"), rs.getString("tur_kodu"),
                rs.getString("durum_kodu"),
                rs.getObject("sorgu_tanim_surumu", Integer.class),
                jsonOrNull(rs.getString("sorgu_tanimi")), rs.getString("ad"),
                rs.getLong("versiyon_no"));
    }

    private JsonNode jsonOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored JSON could not be read.", exception);
        }
    }

    private String databaseDataObjectType(String type) {
        return "VIEW".equals(type) ? "GORUNUM" : type;
    }
}

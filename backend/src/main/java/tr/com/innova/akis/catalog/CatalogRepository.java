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
                         where proje_id = :projectId and uuid = :uuid
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query((rs, rowNum) -> new LogicalSchemaRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class)))
                .optional();
    }

    ModelRow createModel(
            long projectId,
            long logicalSchemaId,
            UUID uuid,
            String code,
            String name,
            String description) {
        jdbc.sql("""
                        insert into akis.model(
                            proje_id, mantiksal_sema_id, uuid, kod, ad, aciklama)
                        values (:projectId, :logicalSchemaId, :uuid, :code, :name, :description)
                        """)
                .param("projectId", projectId)
                .param("logicalSchemaId", logicalSchemaId)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .update();
        return findModel(projectId, uuid).orElseThrow();
    }

    List<ModelRow> listModels(long projectId) {
        return jdbc.sql(modelSelect() + " where m.proje_id = :projectId order by m.kod")
                .param("projectId", projectId)
                .query(this::mapModel)
                .list();
    }

    Optional<ModelRow> findModel(long projectId, UUID uuid) {
        return jdbc.sql(modelSelect() + " where m.proje_id = :projectId and m.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapModel)
                .optional();
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
                        + " where s.proje_id = :projectId and s.model_id = :modelId order by s.kod")
                .param("projectId", projectId)
                .param("modelId", modelId)
                .query(this::mapSubmodel)
                .list();
    }

    Optional<SubmodelRow> findSubmodel(long projectId, UUID uuid) {
        return jdbc.sql(submodelSelect()
                        + " where s.proje_id = :projectId and s.uuid = :uuid")
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
                       l.uuid as logical_schema_uuid, m.kod,
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
                """;
    }

    private ModelRow mapModel(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ModelRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("mantiksal_sema_id"),
                rs.getObject("logical_schema_uuid", UUID.class),
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

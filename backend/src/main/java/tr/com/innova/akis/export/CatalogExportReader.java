package tr.com.innova.akis.export;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Export-local, read-only access to the data-object catalog.
 *
 * <p>Deliberately does not reuse {@code CatalogService} / {@code CatalogModels},
 * which intentionally stay package-private to the {@code catalog} package. All
 * queries are scoped by project so a caller can never read rows outside the
 * authorized project. {@code queryDefinition}/{@code sorgu_tanimi} and any
 * connection details are never surfaced here (they can embed credentials).
 */
@Component
class CatalogExportReader {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    CatalogExportReader(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Export view of a catalog model (no reverse-engineering options). */
    record ModelRef(
            UUID uuid,
            String code,
            String name,
            String description,
            String technologyCode,
            String status,
            long dataObjectCount,
            OffsetDateTime lastMetadataUpdate,
            UUID logicalSchemaUuid) {
    }

    record SubmodelRef(
            UUID uuid,
            UUID modelUuid,
            UUID parentUuid,
            String code,
            String name) {
    }

    record DataObjectRef(
            UUID uuid,
            UUID modelUuid,
            UUID submodelUuid,
            String code,
            String objectReference,
            String type,
            String status,
            String name) {
    }

    List<ModelRef> listModels(UUID projectUuid) {
        return jdbc.sql("""
                        select m.uuid, m.kod, m.ad, m.aciklama, m.teknoloji_kodu,
                               case when m.arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                               (select count(*) from akis.veri_nesnesi d
                                 where d.model_id = m.id and d.arsivlenme_zamani is null) as nesne_sayisi,
                               (select max(g.kesif_zamani)
                                  from akis.sema_goruntusu g
                                  join akis.veri_nesnesi d on d.id = g.veri_nesnesi_id
                                 where d.model_id = m.id) as son_metadata_guncellemesi,
                               l.uuid as logical_schema_uuid
                          from akis.model m
                          join akis.mantiksal_sema l on l.id = m.mantiksal_sema_id
                         where m.proje_id = :projectId
                           and m.arsivlenme_zamani is null
                         order by m.kod
                        """)
                .param("projectId", projectId(projectUuid))
                .query((rs, n) -> new ModelRef(
                        rs.getObject("uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getString("ad"),
                        rs.getString("aciklama"),
                        rs.getString("teknoloji_kodu"),
                        rs.getString("durum_kodu"),
                        rs.getLong("nesne_sayisi"),
                        rs.getObject("son_metadata_guncellemesi", OffsetDateTime.class),
                        rs.getObject("logical_schema_uuid", UUID.class)))
                .list();
    }

    void forEachModel(UUID projectUuid, Consumer<ModelRef> consumer) {
        try (var rows = jdbc.sql("""
                        select m.uuid, m.kod, m.ad, m.aciklama, m.teknoloji_kodu,
                               case when m.arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                               (select count(*) from akis.veri_nesnesi d where d.model_id = m.id and d.arsivlenme_zamani is null) as nesne_sayisi,
                               (select max(g.kesif_zamani) from akis.sema_goruntusu g join akis.veri_nesnesi d on d.id = g.veri_nesnesi_id where d.model_id = m.id) as son_metadata_guncellemesi,
                               l.uuid as logical_schema_uuid
                          from akis.model m join akis.mantiksal_sema l on l.id = m.mantiksal_sema_id
                         where m.proje_id = :projectId and m.arsivlenme_zamani is null order by m.kod
                        """).param("projectId", projectId(projectUuid))
                .query((rs, n) -> new ModelRef(rs.getObject("uuid", UUID.class), rs.getString("kod"), rs.getString("ad"),
                        rs.getString("aciklama"), rs.getString("teknoloji_kodu"), rs.getString("durum_kodu"),
                        rs.getLong("nesne_sayisi"), rs.getObject("son_metadata_guncellemesi", OffsetDateTime.class),
                        rs.getObject("logical_schema_uuid", UUID.class))).stream()) {
            rows.forEach(consumer);
        }
    }

    List<SubmodelRef> listSubmodels(UUID projectUuid, UUID modelUuid) {
        return jdbc.sql("""
                        select s.uuid, m.uuid as model_uuid, p.uuid as parent_uuid, s.kod, s.ad
                          from akis.alt_model s
                          join akis.model m on m.id = s.model_id
                          left join akis.alt_model p on p.id = s.ust_alt_model_id
                         where m.proje_id = :projectId
                           and m.uuid = :modelUuid
                           and s.arsivlenme_zamani is null
                         order by s.kod
                        """)
                .param("projectId", projectId(projectUuid))
                .param("modelUuid", modelUuid)
                .query((rs, n) -> new SubmodelRef(
                        rs.getObject("uuid", UUID.class),
                        rs.getObject("model_uuid", UUID.class),
                        rs.getObject("parent_uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getString("ad")))
                .list();
    }

    void forEachSubmodel(UUID projectUuid, UUID modelUuid, Consumer<SubmodelRef> consumer) {
        try (var rows = jdbc.sql("""
                        select s.uuid, m.uuid as model_uuid, p.uuid as parent_uuid, s.kod, s.ad
                          from akis.alt_model s join akis.model m on m.id = s.model_id left join akis.alt_model p on p.id = s.ust_alt_model_id
                         where m.proje_id = :projectId and m.uuid = :modelUuid and s.arsivlenme_zamani is null order by s.kod
                        """).param("projectId", projectId(projectUuid)).param("modelUuid", modelUuid)
                .query((rs, n) -> new SubmodelRef(rs.getObject("uuid", UUID.class), rs.getObject("model_uuid", UUID.class),
                        rs.getObject("parent_uuid", UUID.class), rs.getString("kod"), rs.getString("ad"))).stream()) {
            rows.forEach(consumer);
        }
    }

    List<DataObjectRef> listDataObjects(UUID projectUuid, UUID modelUuid) {
        return jdbc.sql("""
                        select d.uuid, m.uuid as model_uuid, s.uuid as submodel_uuid,
                               d.kod, d.nesne_referansi,
                               case d.tur when 'GORUNUM' then 'VIEW' else d.tur end as tur_kodu,
                               case when d.arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum_kodu,
                               d.ad
                          from akis.veri_nesnesi d
                          join akis.model m on m.id = d.model_id
                          left join akis.alt_model s on s.id = d.alt_model_id
                         where m.proje_id = :projectId
                           and m.uuid = :modelUuid
                         order by d.kod
                        """)
                .param("projectId", projectId(projectUuid))
                .param("modelUuid", modelUuid)
                .query((rs, n) -> new DataObjectRef(
                        rs.getObject("uuid", UUID.class),
                        rs.getObject("model_uuid", UUID.class),
                        rs.getObject("submodel_uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getString("nesne_referansi"),
                        rs.getString("tur_kodu"),
                        rs.getString("durum_kodu"),
                        rs.getString("ad")))
                .list();
    }

    void forEachDataObject(UUID projectUuid, UUID modelUuid, Consumer<DataObjectRef> consumer) {
        try (var rows = jdbc.sql("""
                        select d.uuid, m.uuid as model_uuid, s.uuid as submodel_uuid, d.kod, d.nesne_referansi,
                               case d.tur when 'GORUNUM' then 'VIEW' else d.tur end as tur_kodu,
                               case when d.arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum_kodu, d.ad
                          from akis.veri_nesnesi d join akis.model m on m.id = d.model_id left join akis.alt_model s on s.id = d.alt_model_id
                         where m.proje_id = :projectId and m.uuid = :modelUuid order by d.kod
                        """).param("projectId", projectId(projectUuid)).param("modelUuid", modelUuid)
                .query((rs, n) -> new DataObjectRef(rs.getObject("uuid", UUID.class), rs.getObject("model_uuid", UUID.class),
                        rs.getObject("submodel_uuid", UUID.class), rs.getString("kod"), rs.getString("nesne_referansi"),
                        rs.getString("tur_kodu"), rs.getString("durum_kodu"), rs.getString("ad"))).stream()) {
            rows.forEach(consumer);
        }
    }

    /** Sensitive-column markings for a data object (project and object scoped). */
    List<String> sensitiveColumns(UUID projectUuid, UUID modelUuid, UUID objectUuid) {
        return jdbc.sql("""
                        select c.kolon_adi
                          from akis.veri_nesnesi_kolon_politikasi c
                          join akis.veri_nesnesi d on d.id = c.veri_nesnesi_id
                          join akis.model m on m.id = d.model_id
                         where m.proje_id = :projectId
                           and m.uuid = :modelUuid
                           and d.uuid = :objectUuid
                         order by c.kolon_adi
                        """)
                .param("projectId", projectId(projectUuid))
                .param("modelUuid", modelUuid)
                .param("objectUuid", objectUuid)
                .query(String.class)
                .list();
    }

    JsonNode readQueryDefinition(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored catalog JSON could not be read.", exception);
        }
    }

    private long projectId(UUID projectUuid) {
        Long id = jdbc.sql("select id from akis.proje where uuid = :uuid")
                .param("uuid", projectUuid)
                .query(Long.class)
                .single();
        if (id == null) {
            throw new IllegalArgumentException("Project not found: " + projectUuid);
        }
        return id;
    }
}

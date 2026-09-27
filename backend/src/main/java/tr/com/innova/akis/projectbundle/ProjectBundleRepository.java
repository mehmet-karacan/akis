package tr.com.innova.akis.projectbundle;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
                        select id, uuid, kod, versiyon_no,
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
                        rs.getLong("versiyon_no"),
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
                        select id, uuid, klasor_id, tur, kod,
                               case when arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                               ad, aciklama
                          from akis.tanim
                         where proje_id = :projectId
                         order by tur, kod
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new DefinitionRow(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
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
                        select ts.tanim_id, ts.uuid, ts.surum_no, ts.sema_surumu, ts.icerik_ozeti,
                               ts.icerik, ts.aciklama, ts.olusturulma_zamani
                          from akis.tanim_surumu ts
                          join akis.tanim t on t.id = ts.tanim_id
                         where t.proje_id = :projectId
                         order by ts.tanim_id, ts.surum_no
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new VersionRow(
                        rs.getLong("tanim_id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getInt("surum_no"),
                        rs.getInt("sema_surumu"),
                        rs.getString("icerik_ozeti"),
                        json(rs.getString("icerik")),
                        rs.getString("aciklama"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .list();
        List<PublicationRow> publications = jdbc.sql("""
                        select distinct t.tur, t.kod as definition_code,
                               ts.surum_no as definition_version_number, o.kod as environment_code
                          from akis.yayin y
                          join akis.senaryo s on s.id = y.senaryo_id
                          join akis.tanim_surumu ts on ts.id = s.tanim_surumu_id
                          join akis.tanim t on t.id = ts.tanim_id
                          join akis.ortam o on o.id = y.ortam_id
                         where y.proje_id = :projectId and y.durum <> 'IPTAL'
                         order by t.tur, t.kod, ts.surum_no, o.kod
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new PublicationRow(
                        apiDefinitionType(rs.getString("tur")),
                        rs.getString("definition_code"),
                        rs.getInt("definition_version_number"),
                        rs.getString("environment_code")))
                .list();
        List<ScheduleRow> schedules = jdbc.sql("""
                        select z.kod, z.ad, z.cron_ifadesi, z.zaman_dilimi,
                               z.cakisma_politikasi, z.kacirma_politikasi, z.yayin_gorunum_kodu,
                               z.baslangic_zamani, z.bitis_zamani,
                               t.tur, t.kod as definition_code,
                               ts.surum_no as definition_version_number, o.kod as environment_code
                          from akis.zamanlama z
                          join akis.yayin y on y.id = z.yayin_id
                          join akis.senaryo s on s.id = y.senaryo_id
                          join akis.tanim_surumu ts on ts.id = s.tanim_surumu_id
                          join akis.tanim t on t.id = ts.tanim_id
                          join akis.ortam o on o.id = y.ortam_id
                         where z.proje_id = :projectId and z.arsivlenme_zamani is null
                         order by z.kod
                        """)
                .param("projectId", project.id())
                .query((rs, rowNum) -> new ScheduleRow(
                        rs.getString("kod"), rs.getString("ad"),
                        rs.getString("cron_ifadesi"), rs.getString("zaman_dilimi"),
                        rs.getString("cakisma_politikasi"), rs.getString("kacirma_politikasi"),
                        rs.getString("yayin_gorunum_kodu"),
                        rs.getObject("baslangic_zamani", java.time.OffsetDateTime.class),
                        rs.getObject("bitis_zamani", java.time.OffsetDateTime.class),
                        apiDefinitionType(rs.getString("tur")), rs.getString("definition_code"),
                        rs.getInt("definition_version_number"), rs.getString("environment_code")))
                .list();
        return new ExportSnapshot(project, folders, definitions, drafts, versions, publications, schedules);
    }

    JsonNode loadPortableTopology(long projectId) {
        PortableReferenceUuids references = portableReferenceUuids(projectId);
        ObjectNode topology = objectMapper.createObjectNode();
        topology.set("connections", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'code', q.kod,
                           'name', q.ad,
                           'description', q.aciklama,
                           'provider', q.saglayici_turu,
                           'status', q.durum,
                           'credentialRequired', true)
                       order by q.kod)
                  from (
                        select distinct b.kod, b.ad, b.aciklama,
                               b.saglayici_turu, b.durum
                          from akis.sema_eslemesi se
                          join akis.mantiksal_sema l
                            on l.id = se.mantiksal_sema_id
                          join akis.fiziksel_sema fs on fs.id = se.fiziksel_sema_id
                          join akis.baglanti b on b.id = fs.baglanti_id
                         where l.uuid in (:logicalRefs)
                            or exists (select 1 from akis.model mo
                                        where mo.proje_id = :p
                                          and mo.mantiksal_sema_id = l.id)
                       ) q
                """, projectId, references));
        topology.set("physicalSchemas", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'code', q.kod,
                           'name', q.ad,
                           'description', q.aciklama,
                           'provider', q.saglayici_turu,
                           'connectionCode', q.baglanti_kodu,
                           'catalogName', q.katalog_adi,
                           'schemaName', q.sema_adi,
                           'workCatalogName', q.calisma_katalog_adi,
                           'workSchemaName', q.calisma_sema_adi,
                           'loadPrefix', q.yukleme_prefix,
                           'integrationPrefix', q.entegrasyon_prefix,
                           'errorPrefix', q.hata_prefix,
                           'temporaryPrefix', q.gecici_prefix,
                           'objectPattern', q.nesne_deseni,
                           'remoteObjectPattern', q.uzak_nesne_deseni,
                           'sequencePattern', q.sira_deseni,
                           'status', q.durum)
                       order by q.baglanti_kodu, q.kod)
                  from (
                        select distinct fs.kod, fs.ad, fs.aciklama,
                               fs.saglayici_turu, b.kod as baglanti_kodu,
                               fs.katalog_adi, fs.sema_adi,
                               fs.calisma_katalog_adi, fs.calisma_sema_adi,
                               fs.yukleme_prefix, fs.entegrasyon_prefix,
                               fs.hata_prefix, fs.gecici_prefix,
                               fs.nesne_deseni, fs.uzak_nesne_deseni,
                               fs.sira_deseni, fs.durum
                          from akis.sema_eslemesi se
                          join akis.mantiksal_sema l
                            on l.id = se.mantiksal_sema_id
                          join akis.fiziksel_sema fs on fs.id = se.fiziksel_sema_id
                          join akis.baglanti b on b.id = fs.baglanti_id
                         where l.uuid in (:logicalRefs)
                            or exists (select 1 from akis.model mo
                                        where mo.proje_id = :p
                                          and mo.mantiksal_sema_id = l.id)
                       ) q
                """, projectId, references));
        topology.set("logicalSchemas", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'code', q.kod,
                           'name', q.ad,
                           'description', q.aciklama,
                           'provider', q.saglayici_turu,
                           'status', q.durum,
                           'sourceUuid', q.uuid)
                       order by q.kod)
                  from (
                        select distinct l.kod, l.ad, l.aciklama,
                               l.saglayici_turu, l.durum, l.uuid
                          from akis.mantiksal_sema l
                         where l.uuid in (:logicalRefs)
                            or exists (select 1 from akis.model mo
                                        where mo.proje_id = :p
                                          and mo.mantiksal_sema_id = l.id)
                       ) q
                """, projectId, references));
        topology.set("environments", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'code', q.kod,
                           'name', q.ad,
                           'description', q.aciklama,
                           'production', q.uretim_mi,
                           'risk', q.risk,
                           'policySchemaVersion', q.politika_sema_surumu,
                           'policy', q.politika,
                           'status', q.durum,
                           'sourceUuid', q.uuid)
                       order by q.kod)
                  from (
                        select distinct o.kod, o.ad, o.aciklama, o.uretim_mi,
                               o.risk, o.politika_sema_surumu, o.politika, o.durum, o.uuid
                          from akis.ortam o
                         where o.uuid in (:environmentRefs)
                            or exists (select 1 from akis.sema_eslemesi se
                                        join akis.mantiksal_sema l
                                          on l.id = se.mantiksal_sema_id
                                       where se.ortam_id = o.id
                                         and l.uuid in (:logicalRefs))
                            or o.id in (
                               select se.ortam_id
                                 from akis.model mo
                                 join akis.sema_eslemesi se
                                   on se.mantiksal_sema_id = mo.mantiksal_sema_id
                                where mo.proje_id = :p
                               union
                               select mo.tersine_muhendislik_ortam_id
                                 from akis.model mo
                                where mo.proje_id = :p
                                  and mo.tersine_muhendislik_ortam_id is not null)
                       ) q
                """, projectId, references));
        topology.set("schemaBindings", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'environmentCode', q.ortam_kodu,
                           'logicalSchemaCode', q.mantiksal_sema_kodu,
                           'connectionCode', q.baglanti_kodu,
                           'physicalSchemaCode', q.fiziksel_sema_kodu,
                           'provider', q.saglayici_turu)
                       order by q.ortam_kodu, q.mantiksal_sema_kodu)
                  from (
                        select distinct o.kod as ortam_kodu,
                               l.kod as mantiksal_sema_kodu,
                               b.kod as baglanti_kodu,
                               fs.kod as fiziksel_sema_kodu,
                               se.saglayici_turu
                          from akis.mantiksal_sema l
                          join akis.sema_eslemesi se on se.mantiksal_sema_id = l.id
                          join akis.ortam o on o.id = se.ortam_id
                          join akis.fiziksel_sema fs on fs.id = se.fiziksel_sema_id
                          join akis.baglanti b on b.id = fs.baglanti_id
                         where l.uuid in (:logicalRefs)
                            or exists (select 1 from akis.model mo
                                        where mo.proje_id = :p
                                          and mo.mantiksal_sema_id = l.id)
                       ) q
                """, projectId, references));
        topology.set("models", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'code', mo.kod,
                           'name', mo.ad,
                           'description', mo.aciklama,
                           'logicalSchemaCode', m.kod,
                           'technology', mo.teknoloji_kodu,
                           'reverseEnvironmentCode', o.kod,
                           'reverseMode', mo.tersine_muhendislik_modu,
                           'rkmCode', r.kod,
                           'rkmScope', r.kapsam,
                           'reverseOptions', mo.tersine_muhendislik_secenekleri)
                       order by mo.kod)
                  from akis.model mo
                  join akis.mantiksal_sema m on m.id = mo.mantiksal_sema_id
                  left join akis.ortam o on o.id = mo.tersine_muhendislik_ortam_id
                  left join akis.tanim r on r.id = mo.rkm_tanim_id
                 where mo.proje_id = :p
                """, projectId));
        topology.set("submodels", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'code', a.kod,
                           'name', a.ad,
                           'description', a.aciklama,
                           'modelCode', mo.kod,
                           'parentCode', u.kod)
                       order by mo.kod, a.id)
                  from akis.alt_model a
                  join akis.model mo on mo.id = a.model_id
                  left join akis.alt_model u on u.id = a.ust_alt_model_id
                 where a.proje_id = :p
                """, projectId));
        topology.set("dataObjects", jsonArray("""
                select jsonb_agg(jsonb_build_object(
                           'code', v.kod,
                           'name', v.ad,
                           'reference', v.nesne_referansi,
                           'type', v.tur,
                           'modelCode', mo.kod,
                           'submodelCode', a.kod,
                           'querySchemaVersion', v.sorgu_sema_surumu,
                           'queryDefinition', v.sorgu_tanimi,
                           'metadataVerification', 'IMPORTED_UNVERIFIED',
                           'sourceUuid', v.uuid,
                           'sourceSchemaSnapshotUuid', snap.uuid,
                           'sourcePhysicalSchemaCode', snap.physical_schema_code,
                           'sourceConnectionCode', snap.connection_code,
                           'sourceSnapshotFingerprint', snap.parmak_izi,
                           'sourceSnapshotEngineVersion', snap.motor_surumu,
                           'sourceSnapshotPropertyVersion', snap.ozellik_sema_surumu,
                           'sourceSnapshotProperties', coalesce(snap.ozellik, '{}'::jsonb),
                           'sourceSnapshotDiscoveredAt', snap.kesif_zamani,
                           'sourceSnapshotTechnology', snap.teknoloji_kodu,
                           'sourceSnapshotColumns', coalesce(snap.columns, '[]'::jsonb),
                           'sourceSnapshotConstraints', coalesce(snap.constraints, '[]'::jsonb),
                           'columns', coalesce(columns.items, imported.kolonlar, '[]'::jsonb))
                       order by mo.kod, v.kod)
                  from akis.veri_nesnesi v
                  join akis.model mo on mo.id = v.model_id
                  left join akis.alt_model a on a.id = v.alt_model_id
                 left join lateral (
                        select s.uuid, s.parmak_izi, s.motor_surumu,
                               s.ozellik_sema_surumu, s.ozellik, s.kesif_zamani,
                               s.teknoloji_kodu, fs.kod as physical_schema_code,
                               b.kod as connection_code,
                               (select coalesce(jsonb_agg(jsonb_build_object(
                                          'reference', c.kolon_referansi,
                                          'name', c.ad,
                                          'producerType', c.uretici_tipi,
                                          'canonicalType', c.kanonik_tip,
                                          'ordinal', c.sira_no,
                                          'precision', c.hassasiyet,
                                          'scale', c.olcek,
                                          'length', c.uzunluk,
                                          'timePrecision', c.zaman_hassasiyeti,
                                          'nullable', c.null_olabilir,
                                          'defaultExpression', c.varsayilan_ifade)
                                      order by c.sira_no), '[]'::jsonb)
                                  from akis.kolon_goruntusu c
                                 where c.sema_goruntusu_id = s.id) as columns,
                               (select coalesce(jsonb_agg(jsonb_build_object(
                                          'externalReference', k.dis_referans,
                                          'databaseType', k.tur,
                                          'enabled', k.etkin_mi,
                                          'detailVersion', k.ayrinti_sema_surumu,
                                          'details', k.ayrinti,
                                          'name', k.ad,
                                          'columnReferences', coalesce(kc.refs, '[]'::jsonb))
                                      order by k.dis_referans), '[]'::jsonb)
                                  from akis.kisit_goruntusu k
                                  left join lateral (
                                        select jsonb_agg(c.kolon_referansi order by kk.sira_no) as refs
                                          from akis.kisit_kolonu kk
                                          join akis.kolon_goruntusu c on c.id = kk.kolon_goruntusu_id
                                         where kk.kisit_goruntusu_id = k.id
                                       ) kc on true
                                 where k.sema_goruntusu_id = s.id) as constraints
                          from akis.sema_goruntusu s
                          join akis.fiziksel_sema fs on fs.id = s.fiziksel_sema_id
                          join akis.baglanti b on b.id = s.baglanti_id
                         where s.veri_nesnesi_id = v.id
                         order by s.kesif_zamani desc, s.id desc
                         limit 1
                       ) snap on true
                 left join lateral (
                        select jsonb_agg(jsonb_build_object(
                                   'reference', c.kolon_referansi,
                                   'name', c.ad,
                                   'producerType', c.uretici_tipi,
                                   'canonicalType', c.kanonik_tip,
                                   'ordinal', c.sira_no,
                                   'precision', c.hassasiyet,
                                   'scale', c.olcek,
                                   'length', c.uzunluk,
                                   'timePrecision', c.zaman_hassasiyeti,
                                   'nullable', c.null_olabilir,
                                   'defaultExpression', c.varsayilan_ifade,
                                   'comment', null,
                                   'sensitivityClass', p.sinif,
                                   'protection', p.koruma)
                               order by c.sira_no) as items
                          from akis.kolon_goruntusu c
                          left join akis.veri_nesnesi_kolon_politikasi p
                            on p.veri_nesnesi_id = v.id
                           and p.kolon_adi = upper(c.ad)
                          where c.sema_goruntusu_id = (
                                select s.id
                                  from akis.sema_goruntusu s
                                 where s.veri_nesnesi_id = v.id
                                 order by s.kesif_zamani desc, s.id desc
                                 limit 1)
                        ) columns on true
                  left join akis.ithal_katalog_metadata imported
                    on imported.veri_nesnesi_id = v.id
                  where v.proje_id = :p
                """, projectId));
        return topology;
    }

    private record PortableReferenceUuids(
            List<UUID> logicalSchemas, List<UUID> environments) {
    }

    private record DefinitionContent(DefinitionType type, JsonNode content) {
    }

    private PortableReferenceUuids portableReferenceUuids(long projectId) {
        List<DefinitionContent> contents = jdbc.sql("""
                        select t.tur, tt.icerik::text as icerik
                          from akis.tanim_taslagi tt
                          join akis.tanim t on t.id = tt.tanim_id
                         where t.proje_id = :p
                           and t.tur in ('PROSEDUR', 'DEGISKEN')
                        union all
                        select t.tur, ts.icerik::text as icerik
                          from akis.tanim_surumu ts
                          join akis.tanim t on t.id = ts.tanim_id
                         where t.proje_id = :p
                           and t.tur in ('PROSEDUR', 'DEGISKEN')
                        """)
                .param("p", projectId)
                .query((rs, row) -> new DefinitionContent(
                        apiDefinitionType(rs.getString("tur")), json(rs.getString("icerik"))))
                .list();
        Set<UUID> logical = new HashSet<>();
        Set<UUID> environments = new HashSet<>();
        for (DefinitionContent definition : contents) {
            if (definition.type() == DefinitionType.VARIABLE) {
                if ("REFRESH_QUERY".equals(definition.content().path("valueSource").asString())) {
                    addReference(logical, definition.content().path("logicalSchemaUuid"));
                }
                continue;
            }
            if (definition.type() != DefinitionType.PROCEDURE) continue;
            JsonNode tasks = definition.content().path("tasks");
            if (!tasks.isArray()) continue;
            for (JsonNode task : tasks) {
                addReference(logical, task.path("logicalSchemaUuid"));
                addReference(environments, task.path("environmentUuid"));
                JsonNode parameters = task.path("parameters");
                if (parameters.isObject()) {
                    parameters.properties().forEach(entry ->
                            addReference(logical, entry.getValue().path("logicalSchemaUuid")));
                }
            }
        }
        UUID emptySentinel = new UUID(0L, 0L);
        return new PortableReferenceUuids(
                logical.isEmpty() ? List.of(emptySentinel) : new ArrayList<>(logical),
                environments.isEmpty() ? List.of(emptySentinel) : new ArrayList<>(environments));
    }

    private void addReference(Set<UUID> target, JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return;
        if (!value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Export içeriğinde geçersiz topoloji referansı var.");
        }
        try {
            target.add(UUID.fromString(value.asString()));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Export içeriğinde geçersiz UUID referansı var.", invalid);
        }
    }

    private JsonNode jsonArray(String sql, long projectId) {
        String value = jdbc.sql("select coalesce((" + sql + "), '[]'::jsonb)::text")
                .param("p", projectId)
                .query(String.class)
                .single();
        return json(value);
    }

    private JsonNode jsonArray(
            String sql, long projectId, PortableReferenceUuids references) {
        String value = jdbc.sql("select coalesce((" + sql + "), '[]'::jsonb)::text")
                .param("p", projectId)
                .param("logicalRefs", references.logicalSchemas())
                .param("environmentRefs", references.environments())
                .query(String.class)
                .single();
        return json(value);
    }

    Map<UUID, UUID> importPortableTopology(long projectId, JsonNode topology) {
        return importPortableTopologyWithSnapshots(projectId, topology, null)
                .dataObjectUuidBySource();
    }

    record PortableTopologyImportResult(
            Map<UUID, UUID> dataObjectUuidBySource,
            Map<UUID, UUID> schemaSnapshotUuidBySource) {
    }

    Map<UUID, UUID> importPortableTopology(
            long projectId, JsonNode topology, String bundleChecksum) {
        return importPortableTopologyWithSnapshots(projectId, topology, bundleChecksum)
                .dataObjectUuidBySource();
    }

    PortableTopologyImportResult importPortableTopologyWithSnapshots(
            long projectId, JsonNode topology, String bundleChecksum) {
        if (topology == null || !topology.isObject()) {
            return new PortableTopologyImportResult(Map.of(), Map.of());
        }
        // Topology (connections, schemas, environments) is global and is referenced by code, never imported.
        Map<String,Long> logical=new HashMap<>(), environments=new HashMap<>(), models=new HashMap<>(), submodels=new HashMap<>();
        for(var row: jdbc.sql("select id, kod from akis.mantiksal_sema").query((r,n)->Map.entry(r.getString("kod"), r.getLong("id"))).list()) logical.put(row.getKey(), row.getValue());
        for(var row: jdbc.sql("select id, kod from akis.ortam").query((r,n)->Map.entry(r.getString("kod"), r.getLong("id"))).list()) environments.put(row.getKey(), row.getValue());
        for(JsonNode item:array(topology,"models")) if(!logical.containsKey(text(item,"logicalSchemaCode"))) throw new IllegalStateException("Mantıksal şema tanımlı değil: "+text(item,"logicalSchemaCode"));
        for (JsonNode item : array(topology, "models")) {
            String mode = nullable(item, "reverseMode");
            if (mode == null) mode = "STANDARD";
            String options = item.hasNonNull("reverseOptions")
                    ? item.get("reverseOptions").toString() : "{}";
            Long rkmId = resolveRkmDefinition(projectId, item);
            long id = jdbc.sql("""
                            insert into akis.model(
                                proje_id, mantiksal_sema_id, teknoloji_kodu,
                                tersine_muhendislik_ortam_id, tersine_muhendislik_modu,
                                rkm_tanim_id, tersine_muhendislik_secenekleri,
                                kod, ad, aciklama)
                            values (:p, :m, :technology, :environment, :mode,
                                    :rkm, cast(:options as jsonb), :c, :n, :d)
                            returning id
                            """)
                    .param("p", projectId)
                    .param("m", logical.get(text(item, "logicalSchemaCode")))
                    .param("technology", item.hasNonNull("technology")
                            ? text(item, "technology") : "ORACLE")
                    .param("environment", environments.get(nullable(item, "reverseEnvironmentCode")), Types.BIGINT)
                    .param("mode", mode)
                    .param("rkm", rkmId, Types.BIGINT)
                    .param("options", options)
                    .param("c", text(item, "code"))
                    .param("n", text(item, "name"))
                    .param("d", nullable(item, "description"), Types.VARCHAR)
                    .query(Long.class)
                    .single();
            models.put(text(item, "code"), id);
        }
        List<JsonNode> pendingSubmodels = new java.util.ArrayList<>();
        array(topology, "submodels").forEach(pendingSubmodels::add);
        while (!pendingSubmodels.isEmpty()) {
            int before = pendingSubmodels.size();
            var iterator = pendingSubmodels.iterator();
            while (iterator.hasNext()) {
                JsonNode item = iterator.next();
                String modelCode = text(item, "modelCode");
                String parentCode = nullable(item, "parentCode");
                Long parent = parentCode == null
                        ? null : submodels.get(modelCode + ":" + parentCode);
                if (parentCode != null && parent == null) continue;
                Long modelId = models.get(modelCode);
                if (modelId == null) {
                    throw new IllegalStateException("Model tanımlı değil: " + modelCode);
                }
                long id = jdbc.sql("""
                                insert into akis.alt_model(
                                    proje_id, model_id, ust_alt_model_id, kod, ad, aciklama)
                                values (:p, :m, :u, :c, :n, :d)
                                returning id
                                """)
                        .param("p", projectId)
                        .param("m", modelId)
                        .param("u", parent, Types.BIGINT)
                        .param("c", text(item, "code"))
                        .param("n", text(item, "name"))
                        .param("d", nullable(item, "description"), Types.VARCHAR)
                        .query(Long.class)
                        .single();
                submodels.put(modelCode + ":" + text(item, "code"), id);
                iterator.remove();
            }
            if (pendingSubmodels.size() == before) {
                throw new IllegalStateException(
                        "Alt model hiyerarşisi eksik ebeveyn veya döngü içeriyor.");
            }
        }
        Map<UUID, UUID> sourceDataObjectUuidToTargetUuid = new HashMap<>();
        Map<UUID, UUID> sourceSchemaSnapshotUuidToTargetUuid = new HashMap<>();
        for (JsonNode item : array(topology, "dataObjects")) {
            UUID sourceUuid = item.hasNonNull("sourceUuid")
                    ? UUID.fromString(item.get("sourceUuid").asString()) : null;
            UUID targetUuid = UUID.randomUUID();
            Long targetDataObjectId = jdbc.sql("""
                            insert into akis.veri_nesnesi(
                                uuid, proje_id, model_id, alt_model_id, kod, ad,
                                nesne_referansi, tur, sorgu_sema_surumu, sorgu_tanimi)
                            values(:uuid, :p,:m,:a,:c,:n,:r,:t,:qv,cast(:q as jsonb))
                            returning id
                            """)
                    .param("uuid", targetUuid)
                    .param("p", projectId)
                    .param("m", models.get(text(item, "modelCode")))
                    .param("a", item.get("submodelCode") == null || item.get("submodelCode").isNull()
                            ? null : submodels.get(text(item, "modelCode") + ":" + text(item, "submodelCode")), Types.BIGINT)
                    .param("c", text(item, "code"))
                    .param("n", text(item, "name"))
                    .param("r", text(item, "reference"))
                    .param("t", text(item, "type"))
                    .param("qv", integer(item, "querySchemaVersion"), Types.INTEGER)
                    .param("q", item.get("queryDefinition") == null || item.get("queryDefinition").isNull()
                            ? null : item.get("queryDefinition").toString(), Types.VARCHAR)
                    .query(Long.class)
                    .single();
            if (sourceUuid != null) {
                sourceDataObjectUuidToTargetUuid.put(sourceUuid, targetUuid);
            }
            UUID targetSnapshotUuid = importPortableSnapshot(
                    projectId, targetDataObjectId, item, bundleChecksum);
            UUID sourceSnapshotUuid = item.hasNonNull("sourceSchemaSnapshotUuid")
                    ? UUID.fromString(item.get("sourceSchemaSnapshotUuid").asString()) : null;
            if (sourceSnapshotUuid != null && targetSnapshotUuid != null) {
                sourceSchemaSnapshotUuidToTargetUuid.put(sourceSnapshotUuid, targetSnapshotUuid);
            }
            JsonNode columns = item.get("columns");
            if (bundleChecksum != null && columns != null && columns.isArray()) {
                jdbc.sql("""
                                insert into akis.ithal_katalog_metadata(
                                    proje_id, veri_nesnesi_id, kolonlar, kaynak_bundle_checksum)
                                values (:projectId, :dataObjectId, cast(:columns as jsonb), :checksum)
                                """)
                        .param("projectId", projectId)
                        .param("dataObjectId", targetDataObjectId, Types.BIGINT)
                        .param("columns", columns.toString())
                        .param("checksum", bundleChecksum)
                        .update();
            }
        }
        return new PortableTopologyImportResult(
                sourceDataObjectUuidToTargetUuid,
                sourceSchemaSnapshotUuidToTargetUuid);
    }

    private UUID importPortableSnapshot(
            long projectId, long dataObjectId, JsonNode item, String bundleChecksum) {
        UUID sourceSnapshotUuid = item.hasNonNull("sourceSchemaSnapshotUuid")
                ? UUID.fromString(item.get("sourceSchemaSnapshotUuid").asString()) : null;
        String physicalSchemaCode = nullable(item, "sourcePhysicalSchemaCode");
        String connectionCode = nullable(item, "sourceConnectionCode");
        String fingerprint = nullable(item, "sourceSnapshotFingerprint");
        if (sourceSnapshotUuid == null || physicalSchemaCode == null
                || connectionCode == null || fingerprint == null) return null;

        Optional<SchemaTarget> target = jdbc.sql("""
                        select fs.id as physical_schema_id, b.id as connection_id
                          from akis.fiziksel_sema fs
                          join akis.baglanti b on b.id = fs.baglanti_id
                         where fs.kod = :physicalSchemaCode and b.kod = :connectionCode
                         order by fs.id
                         limit 1
                        """)
                .param("physicalSchemaCode", physicalSchemaCode)
                .param("connectionCode", connectionCode)
                .query((rs, row) -> new SchemaTarget(
                        rs.getLong("physical_schema_id"), rs.getLong("connection_id")))
                .optional();
        if (target.isEmpty()) {
            throw new IllegalStateException(
                    "İçe aktarılan şema görüntüsü seçilen fiziksel şema ve bağlantıya bağlanamadı.");
        }

        UUID targetUuid = UUID.randomUUID();
        String properties = item.hasNonNull("sourceSnapshotProperties")
                ? item.get("sourceSnapshotProperties").toString() : "{}";
        String evidence = objectMapper.createObjectNode()
                .put("imported", true)
                .put("sourceSnapshotUuid", sourceSnapshotUuid.toString())
                .put("bundleChecksum", bundleChecksum == null ? "" : bundleChecksum)
                .toString();
        OffsetDateTime discoveredAt = item.hasNonNull("sourceSnapshotDiscoveredAt")
                ? OffsetDateTime.parse(item.get("sourceSnapshotDiscoveredAt").asText())
                : OffsetDateTime.now();
        String engineVersion = nullable(item, "sourceSnapshotEngineVersion");
        if (engineVersion == null) engineVersion = "IMPORTED";
        int propertyVersion = item.hasNonNull("sourceSnapshotPropertyVersion")
                ? item.get("sourceSnapshotPropertyVersion").asInt() : 1;
        String technology = nullable(item, "sourceSnapshotTechnology");
        if (technology == null) technology = "UNKNOWN";

        long snapshotId = jdbc.sql("""
                        insert into akis.sema_goruntusu(
                            proje_id, veri_nesnesi_id, fiziksel_sema_id,
                            baglanti_id, uuid, parmak_izi, motor_surumu,
                            kesif_zamani, ozellik_sema_surumu, ozellik,
                            teknoloji_kodu, parmak_izi_surumu, kesif_kaniti)
                        values (:projectId, :dataObjectId, :physicalSchemaId,
                                :connectionId, :uuid, :fingerprint, :engineVersion,
                                :discoveredAt, :propertyVersion, cast(:properties as jsonb),
                                :technology, 1, cast(:evidence as jsonb))
                        returning id
                        """)
                .param("projectId", projectId)
                .param("dataObjectId", dataObjectId)
                .param("physicalSchemaId", target.get().physicalSchemaId())
                .param("connectionId", target.get().connectionId())
                .param("uuid", targetUuid)
                .param("fingerprint", fingerprint)
                .param("engineVersion", engineVersion)
                .param("discoveredAt", discoveredAt)
                .param("propertyVersion", propertyVersion)
                .param("properties", properties)
                .param("technology", technology)
                .param("evidence", evidence)
                .query(Long.class)
                .single();

        Map<String, Long> columnIds = new HashMap<>();
        JsonNode columns = item.path("sourceSnapshotColumns");
        if (columns.isArray()) {
            for (JsonNode column : columns) {
                long columnId = jdbc.sql("""
                                insert into akis.kolon_goruntusu(
                                    proje_id, sema_goruntusu_id, uuid, kolon_referansi,
                                    uretici_tipi, kanonik_tip, sira_no, hassasiyet,
                                    olcek, uzunluk, zaman_hassasiyeti, null_olabilir,
                                    varsayilan_ifade, ad)
                                values (:projectId, :snapshotId, :uuid, :reference,
                                        :producerType, :canonicalType, :ordinal, :precision,
                                        :scale, :length, :timePrecision, :nullable,
                                        :defaultExpression, :name)
                                returning id
                                """)
                        .param("projectId", projectId)
                        .param("snapshotId", snapshotId)
                        .param("uuid", UUID.randomUUID())
                        .param("reference", text(column, "reference"))
                        .param("producerType", nullable(column, "producerType"), Types.VARCHAR)
                        .param("canonicalType", nullable(column, "canonicalType"), Types.VARCHAR)
                        .param("ordinal", integer(column, "ordinal"), Types.INTEGER)
                        .param("precision", integer(column, "precision"), Types.INTEGER)
                        .param("scale", integer(column, "scale"), Types.INTEGER)
                        .param("length", column.hasNonNull("length") ? column.get("length").asLong() : null, Types.BIGINT)
                        .param("timePrecision", integer(column, "timePrecision"), Types.INTEGER)
                        .param("nullable", column.hasNonNull("nullable") && column.get("nullable").asBoolean())
                        .param("defaultExpression", nullable(column, "defaultExpression"), Types.VARCHAR)
                        .param("name", text(column, "name"))
                        .query(Long.class)
                        .single();
                columnIds.put(text(column, "reference"), columnId);
            }
        }

        JsonNode constraints = item.path("sourceSnapshotConstraints");
        if (constraints.isArray()) {
            for (JsonNode constraint : constraints) {
                long constraintId = jdbc.sql("""
                                insert into akis.kisit_goruntusu(
                                    proje_id, sema_goruntusu_id, uuid, dis_referans,
                                    tur, etkin_mi, ayrinti_sema_surumu, ayrinti, ad)
                                values (:projectId, :snapshotId, :uuid, :externalReference,
                                        :type, :enabled, :detailVersion, cast(:details as jsonb), :name)
                                returning id
                                """)
                        .param("projectId", projectId)
                        .param("snapshotId", snapshotId)
                        .param("uuid", UUID.randomUUID())
                        .param("externalReference", nullable(constraint, "externalReference"), Types.VARCHAR)
                        .param("type", text(constraint, "databaseType"))
                        .param("enabled", !constraint.hasNonNull("enabled") || constraint.get("enabled").asBoolean())
                        .param("detailVersion", integer(constraint, "detailVersion"), Types.INTEGER)
                        .param("details", constraint.hasNonNull("details") ? constraint.get("details").toString() : "{}")
                        .param("name", text(constraint, "name"))
                        .query(Long.class)
                        .single();
                JsonNode refs = constraint.path("columnReferences");
                if (refs.isArray()) {
                    for (int index = 0; index < refs.size(); index++) {
                        Long columnId = columnIds.get(refs.get(index).asText());
                        if (columnId == null) continue;
                        jdbc.sql("""
                                        insert into akis.kisit_kolonu(
                                            proje_id, kisit_goruntusu_id, kolon_goruntusu_id,
                                            sira_no, uuid)
                                        values (:projectId, :constraintId, :columnId,
                                                :ordinal, :uuid)
                                        """)
                                .param("projectId", projectId)
                                .param("constraintId", constraintId)
                                .param("columnId", columnId)
                                .param("ordinal", index + 1)
                                .param("uuid", UUID.randomUUID())
                                .update();
                    }
                }
            }
        }
        return targetUuid;
    }

    private record SchemaTarget(long physicalSchemaId, long connectionId) {
    }

    private Iterable<JsonNode> array(JsonNode parent,String name){JsonNode value=parent.get(name);return value!=null&&value.isArray()?value:List.of();}
    private String text(JsonNode node,String name){return node.get(name).asString();}
    private String nullable(JsonNode node,String name){JsonNode value=node.get(name);return value==null||value.isNull()?null:value.asString();}
    private Integer integer(JsonNode node,String name){JsonNode value=node.get(name);return value==null||value.isNull()?null:value.asInt();}

    private Long resolveRkmDefinition(long projectId, JsonNode model) {
        String rkmCode = nullable(model, "rkmCode");
        if (rkmCode == null) return null;
        String scope = nullable(model, "rkmScope");
        if (scope == null) scope = "PROJE";
        if (!scope.equals("PROJE") && !scope.equals("SISTEM")) {
            throw new IllegalStateException("Geçersiz RKM kapsamı: " + scope);
        }
        String resolvedScope = scope;
        Optional<Long> id = resolvedScope.equals("PROJE")
                ? jdbc.sql("""
                                select id from akis.tanim
                                 where proje_id=:projectId and kapsam='PROJE'
                                   and tur='KNOWLEDGE_MODULE' and kod=:code
                                """)
                        .param("projectId", projectId).param("code", rkmCode)
                        .query(Long.class).optional()
                : jdbc.sql("""
                                select id from akis.tanim
                                 where proje_id is null and kapsam='SISTEM'
                                   and tur='KNOWLEDGE_MODULE' and kod=:code
                                """)
                        .param("code", rkmCode).query(Long.class).optional();
        if (id.isEmpty()) {
            throw new IllegalStateException(
                    "RKM tanımlı değil: " + resolvedScope + ":" + rkmCode);
        }
        return id.orElseThrow();
    }

    ProjectRow insertProject(
            UUID uuid, String code, String status, String name, String description) {
        return jdbc.sql("""
                        insert into akis.proje(uuid, kod, ad, aciklama, arsivlenme_zamani)
                        values (:uuid, :code, :name, :description,
                                case when :status='AKTIF' then null else current_timestamp end)
                        returning id, uuid, kod, versiyon_no,
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
                        rs.getString("kod"), rs.getLong("versiyon_no"),
                        rs.getString("durum_kodu"),
                        rs.getString("ad"), rs.getString("aciklama")))
                .single();
    }

    ProjectRow lockProject(UUID projectUuid) {
        return jdbc.sql("""
                        select id, uuid, kod, versiyon_no,
                               case when arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as durum_kodu,
                               ad, aciklama
                          from akis.proje
                         where uuid=:uuid
                         for update
                        """)
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRow(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getString("kod"), rs.getLong("versiyon_no"),
                        rs.getString("durum_kodu"), rs.getString("ad"),
                        rs.getString("aciklama")))
                .optional()
                .orElseThrow();
    }

    boolean projectContentIsEmpty(long projectId) {
        return jdbc.sql("""
                        select not exists (
                            select 1 from akis.klasor where proje_id=:p
                            union all select 1 from akis.tanim where proje_id=:p
                            union all select 1 from akis.model where proje_id=:p
                            union all select 1 from akis.alt_model where proje_id=:p
                            union all select 1 from akis.veri_nesnesi where proje_id=:p
                            union all select 1 from akis.senaryo where proje_id=:p
                            union all select 1 from akis.yayin where proje_id=:p
                            union all select 1 from akis.zamanlama where proje_id=:p)
                        """)
                .param("p", projectId)
                .query(Boolean.class)
                .single();
    }

    long advanceProjectVersion(long projectId, long expectedVersion) {
        return jdbc.sql("""
                        update akis.proje
                           set versiyon_no=versiyon_no+1,
                               guncellenme_zamani=clock_timestamp()
                         where id=:id and versiyon_no=:expectedVersion
                        returning versiyon_no
                        """)
                .param("id", projectId)
                .param("expectedVersion", expectedVersion)
                .query(Long.class)
                .optional()
                .orElseThrow();
    }

    Optional<GlobalResourceRow> findGlobalResource(
            ProjectBundleModels.GlobalResourceType type, UUID uuid) {
        String sql = switch (type) {
            case CONNECTION -> "select uuid,kod,saglayici_turu,null::varchar as parent_code from akis.baglanti where uuid=:uuid";
            case PHYSICAL_SCHEMA -> """
                    select f.uuid,f.kod,f.saglayici_turu,b.kod as parent_code
                      from akis.fiziksel_sema f join akis.baglanti b on b.id=f.baglanti_id
                     where f.uuid=:uuid
                    """;
            case LOGICAL_SCHEMA -> "select uuid,kod,saglayici_turu,null::varchar as parent_code from akis.mantiksal_sema where uuid=:uuid";
            case ENVIRONMENT -> "select uuid,kod,null::varchar as saglayici_turu,null::varchar as parent_code from akis.ortam where uuid=:uuid";
        };
        return jdbc.sql(sql).param("uuid", uuid)
                .query((rs, rowNum) -> new GlobalResourceRow(
                        type, rs.getObject("uuid", UUID.class), rs.getString("kod"),
                        rs.getString("saglayici_turu"), rs.getString("parent_code")))
                .optional();
    }

    Optional<ImportReceiptRow> findImportReceipt(long projectId, String idempotencyKey) {
        return jdbc.sql("""
                        select bundle_checksum,plan_digest,hedef_proje_surumu,sonuc
                          from akis.proje_paket_ithalati
                         where proje_id=:projectId and idempotency_key=:idempotencyKey
                        """)
                .param("projectId", projectId)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> new ImportReceiptRow(
                        rs.getString("bundle_checksum"), rs.getString("plan_digest"),
                        rs.getLong("hedef_proje_surumu"), json(rs.getString("sonuc"))))
                .optional();
    }

    void saveImportReceipt(
            long projectId, String idempotencyKey, String bundleChecksum,
            String planDigest, long targetVersion, JsonNode result, Long actorId) {
        jdbc.sql("""
                        insert into akis.proje_paket_ithalati(
                            proje_id,idempotency_key,bundle_checksum,plan_digest,
                            hedef_proje_surumu,sonuc,olusturan_kullanici_id)
                        values (:projectId,:idempotencyKey,:bundleChecksum,:planDigest,
                                :targetVersion,cast(:result as jsonb),:actorId)
                        """)
                .param("projectId", projectId)
                .param("idempotencyKey", idempotencyKey)
                .param("bundleChecksum", bundleChecksum)
                .param("planDigest", planDigest)
                .param("targetVersion", targetVersion)
                .param("result", result.toString())
                .param("actorId", actorId, Types.BIGINT)
                .update();
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
        UUID versionUuid = version.uuid() != null ? version.uuid() : UUID.randomUUID();
        jdbc.sql("""
                        insert into akis.tanim_surumu(
                            uuid, proje_id, tanim_id, surum_no, sema_surumu, icerik_ozeti,
                            icerik, aciklama, olusturulma_zamani)
                        select :versionUuid, proje_id, id, :versionNumber, :schemaVersion, :contentHash,
                               cast(:content as jsonb), :description, :createdAt
                          from akis.tanim where id=:definitionId
                        """)
                .param("versionUuid", versionUuid)
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

    record ProjectRow(
            long id, UUID uuid, String code, long version,
            String status, String name, String description) {
    }

    record GlobalResourceRow(
            ProjectBundleModels.GlobalResourceType type,
            UUID uuid, String code, String provider, String parentCode) {
    }

    record ImportReceiptRow(
            String bundleChecksum, String planDigest,
            long targetVersion, JsonNode result) {
    }

    record FolderRow(
            long id, Long parentId, String code,
            String status, String name, String description) {
    }

    record DefinitionRow(
            long id, UUID uuid, Long folderId, DefinitionType type, String code,
            String status, String name, String description) {

        DefinitionRow(long id, Long folderId, DefinitionType type, String code,
                      String status, String name, String description) {
            this(id, null, folderId, type, code, status, name, description);
        }
    }

    record DraftRow(long definitionId, int schemaVersion, JsonNode content) {
    }

    record VersionRow(
            long definitionId, UUID uuid, int versionNumber, int schemaVersion, String contentHash,
            JsonNode content, String description, OffsetDateTime createdAt) {

        VersionRow(long definitionId, int versionNumber, int schemaVersion, String contentHash,
                   JsonNode content, String description, OffsetDateTime createdAt) {
            this(definitionId, null, versionNumber, schemaVersion, contentHash,
                    content, description, createdAt);
        }
    }

    /** A publication recipe row: definition type/code/version + environment code, no source ids. */
    record PublicationRow(
            DefinitionType definitionType,
            String definitionCode,
            int definitionVersionNumber,
            String environmentCode) {
    }

    /** A schedule recipe row: trigger definition plus its publication target, no runtime state. */
    record ScheduleRow(
            String code,
            String name,
            String cronExpression,
            String timeZone,
            String conflictPolicy,
            String misfirePolicy,
            String publicationPolicy,
            java.time.OffsetDateTime startsAt,
            java.time.OffsetDateTime endsAt,
            DefinitionType selectedDefinitionType,
            String selectedDefinitionCode,
            int selectedDefinitionVersionNumber,
            String selectedEnvironmentCode) {
        ScheduleRow(String code, String name, String cronExpression, String timeZone,
                String conflictPolicy, String misfirePolicy, String publicationPolicy,
                DefinitionType selectedDefinitionType, String selectedDefinitionCode,
                int selectedDefinitionVersionNumber, String selectedEnvironmentCode) {
            this(code, name, cronExpression, timeZone, conflictPolicy, misfirePolicy,
                    publicationPolicy, null, null, selectedDefinitionType, selectedDefinitionCode,
                    selectedDefinitionVersionNumber, selectedEnvironmentCode);
        }
    }

    record ExportSnapshot(
            ProjectRow project,
            List<FolderRow> folders,
            List<DefinitionRow> definitions,
            List<DraftRow> drafts,
            List<VersionRow> versions,
            List<PublicationRow> publications,
            List<ScheduleRow> schedules) {
    }

    UUID findDefinitionUuid(long projectId, DefinitionType type, String code) {
        return jdbc.sql("""
                        select uuid from akis.tanim
                         where proje_id = :projectId and tur = :type and kod = :code
                        """)
                .param("projectId", projectId)
                .param("type", storedDefinitionType(type))
                .param("code", code)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    UUID findVersionUuid(long definitionId, int versionNumber) {
        return jdbc.sql("""
                        select uuid from akis.tanim_surumu
                         where tanim_id = :definitionId and surum_no = :versionNumber
                        """)
                .param("definitionId", definitionId)
                .param("versionNumber", versionNumber)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }
}

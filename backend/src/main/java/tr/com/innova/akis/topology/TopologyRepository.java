package tr.com.innova.akis.topology;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionCatalogRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionDependencyRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionVersionRow;
import tr.com.innova.akis.topology.TopologyModels.EnvironmentRow;
import tr.com.innova.akis.topology.TopologyModels.LogicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.PhysicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.ProjectRef;
import tr.com.innova.akis.topology.TopologyModels.SchemaBindingRow;

@Repository
public class TopologyRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public TopologyRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ProjectRef> findProject(UUID projectUuid) {
        return jdbc.sql("select id, uuid from akis.proje where uuid = :uuid and arsivlenme_zamani is null")
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class)))
                .optional();
    }

    ConnectionRow createConnection(
            long projectId,
            UUID uuid,
            String code,
            String databaseType,
            String name,
            String description) {
        return jdbc.sql("""
                        insert into akis.baglanti(
                            proje_id, uuid, kod, saglayici_turu, ad, aciklama)
                        values (:projectId, :uuid, :code, :databaseType, :name, :description)
                        returning id, proje_id, uuid, kod, saglayici_turu,
                                  'AKTIF'::text as durum, ad, aciklama, versiyon_no,
                                  (select k.gorunen_ad from akis.kullanici k where k.id = baglanti.olusturan_kullanici_id) as created_by,
                                  olusturulma_zamani as connection_created_at
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("databaseType", databaseType)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .query(this::mapConnection)
                .single();
    }

    List<ConnectionRow> listConnections(long projectId) {
        return jdbc.sql(connectionSelect() + " where proje_id = :projectId and arsivlenme_zamani is null order by kod")
                .param("projectId", projectId)
                .query(this::mapConnection)
                .list();
    }

    List<ConnectionCatalogRow> listConnectionCatalog(long projectId) {
        return jdbc.sql("""
                with ranked_versions as (
                    select bs.*,
                           row_number() over (
                               partition by bs.baglanti_id
                               order by case when bs.durum = 'ETKIN' then 0 else 1 end,
                                        bs.surum_no desc) as display_rank,
                           max(bs.surum_no) over (partition by bs.baglanti_id) as latest_version_number
                      from akis.baglanti_surumu bs
                     where bs.proje_id = :projectId
                )
                select c.id as connection_id, c.proje_id, c.uuid as connection_uuid,
                       c.kod as connection_code, c.saglayici_turu, c.ad as connection_name,
                       c.aciklama as connection_description, c.versiyon_no as connection_version,
                       (select k.gorunen_ad from akis.kullanici k where k.id = c.olusturan_kullanici_id) as created_by,
                       c.olusturulma_zamani as connection_created_at,
                       rv.id, rv.uuid, rv.baglanti_id, rv.surum_no, rv.baglanti_modu,
                       rv.surucu_sinifi, bk.kullanici_adi, rv.sunucu_adi, rv.servis_adi, rv.sid,
                       rv.veritabani_adi, rv.jndi_adi, rv.tls_modu, rv.port,
                       rv.baglanti_zaman_asimi_ms, rv.okuma_zaman_asimi_ms,
                       rv.ag_zaman_asimi_ms, rv.sorgu_zaman_asimi_saniye,
                       rv.kullanim_amaci, rv.olusturulma_zamani,
                       case rv.durum when 'TASLAK' then 'DRAFT'
                            when 'TEST_EDILDI' then 'TESTED'
                            when 'ETKIN' then 'ACTIVE' else 'DISABLED' end as lifecycle_status,
                       rv.versiyon_no as lifecycle_version,
                       rv.hedef_kimlik_surumu as target_identity_version,
                       rv.hedef_parmak_izi as target_fingerprint,
                       rv.son_basarili_test_uuid as latest_successful_test_uuid,
                       rv.test_edilme_zamani as tested_at,
                       rv.etkinlestirilme_zamani as activated_at,
                       rv.latest_version_number,
                       (select count(*) from akis.fiziksel_sema f
                         where f.proje_id = :projectId and f.baglanti_id = c.id
                           and f.arsivlenme_zamani is null) as physical_schema_count,
                       (select count(distinct se.mantiksal_sema_id)
                          from akis.sema_eslemesi se
                          join akis.fiziksel_sema f on f.id = se.fiziksel_sema_id
                          join akis.mantiksal_sema ms on ms.id = se.mantiksal_sema_id
                         where se.proje_id = :projectId and f.baglanti_id = c.id
                           and f.arsivlenme_zamani is null and ms.arsivlenme_zamani is null)
                           as logical_schema_count
                  from akis.baglanti c
                  left join ranked_versions rv
                    on rv.baglanti_id = c.id and rv.display_rank = 1
                  left join akis.baglanti_kimligi bk
                    on bk.baglanti_surumu_id = rv.id and bk.kullanim_amaci = 'VERITABANI'
                 where c.proje_id = :projectId and c.arsivlenme_zamani is null
                 order by c.ad, c.kod
                """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> {
                    ConnectionRow connection = new ConnectionRow(
                            rs.getLong("connection_id"), rs.getLong("proje_id"),
                            rs.getObject("connection_uuid", UUID.class), rs.getString("connection_code"),
                            rs.getString("saglayici_turu"), "AKTIF", rs.getString("connection_name"),
                            rs.getString("connection_description"), rs.getLong("connection_version"),
                            rs.getString("created_by"), rs.getObject("connection_created_at", OffsetDateTime.class));
                    ConnectionVersionRow displayed = rs.getObject("uuid", UUID.class) == null
                            ? null : mapConnectionVersion(rs, rowNum);
                    return new ConnectionCatalogRow(
                            connection, displayed, rs.getObject("latest_version_number", Integer.class),
                            rs.getInt("physical_schema_count"), rs.getInt("logical_schema_count"));
                })
                .list();
    }

    Optional<ConnectionRow> findConnection(long projectId, UUID uuid) {
        return jdbc.sql(connectionSelect() + " where proje_id = :projectId and uuid = :uuid and arsivlenme_zamani is null")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapConnection)
                .optional();
    }

    List<ConnectionDependencyRow> listConnectionDependencies(long projectId, long connectionId) {
        return jdbc.sql("""
                select distinct ms.uuid, 'LOGICAL_SCHEMA'::text as type, ms.ad as name
                  from akis.sema_eslemesi se
                  join akis.fiziksel_sema fs on fs.id = se.fiziksel_sema_id
                  join akis.mantiksal_sema ms on ms.id = se.mantiksal_sema_id
                 where se.proje_id = :projectId and fs.baglanti_id = :connectionId
                   and fs.arsivlenme_zamani is null and ms.arsivlenme_zamani is null
                 order by ms.ad
                """)
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .query((rs, rowNum) -> new ConnectionDependencyRow(
                        rs.getObject("uuid", UUID.class), rs.getString("type"), rs.getString("name")))
                .list();
    }

    Optional<ConnectionRow> updateConnection(
            long projectId, UUID uuid, String code, String name, String description,
            long expectedVersion) {
        return jdbc.sql("""
                        update akis.baglanti
                           set kod = :code,
                               ad = :name,
                               aciklama = :description,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId
                           and uuid = :uuid
                           and arsivlenme_zamani is null
                           and versiyon_no = :expectedVersion
                        returning id, proje_id, uuid, kod, saglayici_turu,
                                  'AKTIF'::text as durum, ad, aciklama, versiyon_no,
                                  (select k.gorunen_ad from akis.kullanici k where k.id = baglanti.olusturan_kullanici_id) as created_by,
                                  olusturulma_zamani as connection_created_at
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .param("expectedVersion", expectedVersion)
                .query(this::mapConnection)
                .optional();
    }

    void archiveConnection(long projectId, long connectionId) {
        jdbc.sql("""
                        update akis.fiziksel_sema
                           set arsivlenme_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId
                           and baglanti_id = :connectionId
                           and arsivlenme_zamani is null
                        """)
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .update();
        jdbc.sql("""
                        update akis.baglanti
                           set arsivlenme_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId
                           and id = :connectionId
                           and arsivlenme_zamani is null
                        """)
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .update();
    }

    void lockConnection(long connectionId) {
        jdbc.sql("select id from akis.baglanti where id = :id for update")
                .param("id", connectionId)
                .query(Long.class)
                .single();
    }

    void activateDraftConnection(long connectionId) {
        jdbc.sql("""
                        update akis.baglanti
                           set guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where id = :id and arsivlenme_zamani is null
                        """)
                .param("id", connectionId)
                .update();
    }

    int nextConnectionVersion(long connectionId) {
        return jdbc.sql("""
                        select coalesce(max(surum_no), 0) + 1
                          from akis.baglanti_surumu
                         where baglanti_id = :connectionId
                        """)
                .param("connectionId", connectionId)
                .query(Integer.class)
                .single();
    }

    ConnectionVersionRow createConnectionVersion(
            long projectId,
            long connectionId,
            UUID uuid,
            int versionNumber,
            String mode,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String jndiName,
            String tlsMode,
            Integer port,
            int policyVersion,
            JsonNode policy) {
        return jdbc.sql("""
                        insert into akis.baglanti_surumu(
                            proje_id, baglanti_id, uuid, surum_no, baglanti_modu, surucu_sinifi,
                            sunucu_adi, servis_adi, sid, veritabani_adi, jndi_adi, tls_modu,
                            port, baglanti_zaman_asimi_ms, okuma_zaman_asimi_ms,
                            ag_zaman_asimi_ms, sorgu_zaman_asimi_saniye, kullanim_amaci)
                        values (:projectId, :connectionId, :uuid, :versionNumber, :mode, :driverReference,
                                :host, :serviceName, :sid, :databaseName, :jndiName, :tlsMode,
                                :port, :connectTimeoutMs, :readTimeoutMs,
                                :networkTimeoutMs, :queryTimeoutSeconds, :purpose)
                        returning id, uuid, baglanti_id, surum_no, baglanti_modu, surucu_sinifi,
                                  null::varchar as kullanici_adi,
                                  sunucu_adi, servis_adi, sid, veritabani_adi, jndi_adi, tls_modu,
                                  port, baglanti_zaman_asimi_ms, okuma_zaman_asimi_ms,
                                  ag_zaman_asimi_ms, sorgu_zaman_asimi_saniye, kullanim_amaci,
                                  olusturulma_zamani,
                                  'DRAFT'::text as lifecycle_status,
                                  versiyon_no as lifecycle_version,
                                  hedef_kimlik_surumu as target_identity_version,
                                  hedef_parmak_izi as target_fingerprint,
                                  son_basarili_test_uuid as latest_successful_test_uuid,
                                  test_edilme_zamani as tested_at,
                                  etkinlestirilme_zamani as activated_at
                        """)
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .param("uuid", uuid)
                .param("versionNumber", versionNumber)
                .param("mode", mode)
                .param("driverReference", driverReference, Types.VARCHAR)
                .param("host", host, Types.VARCHAR)
                .param("serviceName", serviceName, Types.VARCHAR)
                .param("sid", sid, Types.VARCHAR)
                .param("databaseName", databaseName, Types.VARCHAR)
                .param("jndiName", jndiName, Types.VARCHAR)
                .param("tlsMode", storedTlsMode(tlsMode))
                .param("port", port, Types.INTEGER)
                .param("connectTimeoutMs", policyInteger(policy, "connectTimeoutMs", 10000))
                .param("readTimeoutMs", policyInteger(policy, "readTimeoutMs", 60000))
                .param("networkTimeoutMs", policyInteger(policy, "networkTimeoutMs", 60000))
                .param("queryTimeoutSeconds", policyInteger(policy, "queryTimeoutSeconds", 60))
                .param("purpose", policyText(policy, "purpose"), Types.VARCHAR)
                .query(this::mapConnectionVersion)
                .single();
    }

    void bindCredential(
            long projectId,
            long connectionVersionId,
            String provider,
            String referencePath,
            String role,
            String username) {
        jdbc.sql("""
                        insert into akis.baglanti_kimligi(
                            proje_id, baglanti_surumu_id, kullanim_amaci,
                            gizli_deger_saglayicisi, gizli_deger_konumu, kullanici_adi)
                        values (:projectId, :connectionVersionId, :role, :provider, :referencePath, :username)
                        """)
                .param("projectId", projectId)
                .param("connectionVersionId", connectionVersionId)
                .param("provider", provider)
                .param("referencePath", referencePath)
                .param("role", storedCredentialPurpose(role))
                .param("username", username)
                .update();
    }

    List<ConnectionVersionRow> listConnectionVersions(long projectId, long connectionId) {
        return jdbc.sql(connectionVersionSelect()
                        + " where bs.proje_id = :projectId and bs.baglanti_id = :connectionId"
                        + " order by bs.surum_no desc")
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .query(this::mapConnectionVersion)
                .list();
    }

    Optional<ConnectionVersionRow> findConnectionVersion(long projectId, UUID uuid) {
        return jdbc.sql(connectionVersionSelect()
                        + " where bs.proje_id = :projectId and bs.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapConnectionVersion)
                .optional();
    }

    PhysicalSchemaRow createPhysicalSchema(
            long projectId,
            long connectionId,
            UUID uuid,
            String code,
            String schemaReference,
            String name) {
        jdbc.sql("""
                        insert into akis.fiziksel_sema(
                            proje_id, baglanti_id, uuid, kod, sema_adi, ad)
                        values (:projectId, :connectionId, :uuid, :code, :schemaReference, :name)
                        """)
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .param("uuid", uuid)
                .param("code", code)
                .param("schemaReference", schemaReference)
                .param("name", name)
                .update();
        return findPhysicalSchema(projectId, uuid).orElseThrow();
    }

    List<PhysicalSchemaRow> listPhysicalSchemas(long projectId) {
        return jdbc.sql(physicalSchemaSelect() + " where f.proje_id = :projectId and f.arsivlenme_zamani is null and c.arsivlenme_zamani is null order by f.kod")
                .param("projectId", projectId)
                .query(this::mapPhysicalSchema)
                .list();
    }

    Optional<PhysicalSchemaRow> findPhysicalSchema(long projectId, UUID uuid) {
        return jdbc.sql(physicalSchemaSelect()
                        + " where f.proje_id = :projectId and f.uuid = :uuid and f.arsivlenme_zamani is null and c.arsivlenme_zamani is null")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapPhysicalSchema)
                .optional();
    }

    LogicalSchemaRow createLogicalSchema(
            long projectId,
            UUID uuid,
            String code,
            String name,
            String description) {
        return jdbc.sql("""
                        insert into akis.mantiksal_sema(
                            proje_id, uuid, kod, ad, aciklama)
                        values (:projectId, :uuid, :code, :name, :description)
                        returning id, uuid, kod, 'AKTIF'::text as durum,
                                  ad, aciklama, versiyon_no
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .query(this::mapLogicalSchema)
                .single();
    }

    boolean contextInUse(long projectId, UUID uuid, boolean logical) {
        String table = logical ? "mantiksal_sema" : "ortam";
        String column = logical ? "mantiksal_sema_id" : "ortam_id";
        String additional = logical ? "exists(select 1 from akis.model m where m.proje_id = :p and m.mantiksal_sema_id = c.id)"
                : "exists(select 1 from akis.yayin y where y.proje_id = :p and y.ortam_id = c.id)";
        return jdbc.sql("select exists(select 1 from akis." + table + " c where c.proje_id = :p and c.uuid = :u and ("
                + "exists(select 1 from akis.sema_eslemesi b where b.proje_id = :p and b." + column + " = c.id) or " + additional
                + " or exists(select 1 from akis.tanim_taslagi d where d.proje_id = :p and (strpos(d.icerik::text, c.uuid::text) > 0 or strpos(d.icerik::text, '\"' || c.kod || '\"') > 0))"
                + " or exists(select 1 from akis.tanim_surumu d where d.proje_id = :p and (strpos(d.icerik::text, c.uuid::text) > 0 or strpos(d.icerik::text, '\"' || c.kod || '\"') > 0))))")
                .param("p", projectId).param("u", uuid).query(Boolean.class).single();
    }

    boolean changeContext(long projectId, UUID uuid, boolean logical, String name, String description, long expectedVersion, boolean archive) {
        String table = logical ? "mantiksal_sema" : "ortam";
        String assignment = archive ? "arsivlenme_zamani = current_timestamp" : "ad = :name" + (logical ? ", aciklama = :description" : "");
        var query = jdbc.sql("update akis." + table + " set " + assignment + ", versiyon_no = versiyon_no + 1 where proje_id = :p and uuid = :u and versiyon_no = :v and arsivlenme_zamani is null")
                .param("p", projectId).param("u", uuid).param("v", expectedVersion);
        if (!archive) { query.param("name", name); if (logical) query.param("description", description, Types.VARCHAR); }
        return query.update() == 1;
    }

    List<LogicalSchemaRow> listLogicalSchemas(long projectId) {
        return jdbc.sql(logicalSchemaSelect() + " where proje_id = :projectId and arsivlenme_zamani is null order by kod")
                .param("projectId", projectId)
                .query(this::mapLogicalSchema)
                .list();
    }

    Optional<LogicalSchemaRow> findLogicalSchema(long projectId, UUID uuid) {
        return jdbc.sql(logicalSchemaSelect()
                        + " where proje_id = :projectId and uuid = :uuid and arsivlenme_zamani is null")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapLogicalSchema)
                .optional();
    }

    EnvironmentRow createEnvironment(
            long projectId,
            UUID uuid,
            String code,
            String risk,
            int policyVersion,
            JsonNode policy,
            String name) {
        return jdbc.sql("""
                        insert into akis.ortam(
                            proje_id, uuid, kod, uretim_mi, risk,
                            politika_sema_surumu, politika, ad)
                        values (:projectId, :uuid, :code, :production, :risk,
                                :policyVersion, cast(:policy as jsonb), :name)
                        returning id, uuid, kod, risk,
                                  'AKTIF'::text as durum, politika_sema_surumu,
                                  politika, ad, versiyon_no
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("production", "URETIM".equals(risk))
                .param("risk", risk)
                .param("policyVersion", policyVersion)
                .param("policy", policy.toString())
                .param("name", name)
                .query(this::mapEnvironment)
                .single();
    }

    List<EnvironmentRow> listEnvironments(long projectId) {
        return jdbc.sql(environmentSelect() + " where proje_id = :projectId and arsivlenme_zamani is null order by kod")
                .param("projectId", projectId)
                .query(this::mapEnvironment)
                .list();
    }

    Optional<EnvironmentRow> findEnvironment(long projectId, UUID uuid) {
        return jdbc.sql(environmentSelect() + " where proje_id = :projectId and uuid = :uuid and arsivlenme_zamani is null")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapEnvironment)
                .optional();
    }

    SchemaBindingRow createSchemaBinding(
            long projectId,
            UUID uuid,
            long logicalSchemaId,
            long environmentId,
            long physicalSchemaId,
            long connectionVersionId) {
        jdbc.sql("""
                        insert into akis.sema_eslemesi(
                            proje_id, uuid, mantiksal_sema_id, ortam_id, baglanti_id,
                            fiziksel_sema_id, baglanti_surumu_id)
                        select :projectId, :uuid, :logicalSchemaId, :environmentId,
                               f.baglanti_id, :physicalSchemaId, :connectionVersionId
                          from akis.fiziksel_sema f
                         where f.proje_id = :projectId and f.id = :physicalSchemaId
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("logicalSchemaId", logicalSchemaId)
                .param("environmentId", environmentId)
                .param("physicalSchemaId", physicalSchemaId)
                .param("connectionVersionId", connectionVersionId)
                .update();
        return findSchemaBinding(projectId, uuid).orElseThrow();
    }

    Optional<SchemaBindingRow> updateSchemaBinding(
            long projectId, UUID uuid, long logicalSchemaId, long environmentId,
            long physicalSchemaId, long connectionVersionId, long expectedVersion) {
        int updated = jdbc.sql("""
                update akis.sema_eslemesi se
                   set mantiksal_sema_id = :logicalSchemaId,
                       ortam_id = :environmentId,
                       baglanti_id = f.baglanti_id,
                       fiziksel_sema_id = :physicalSchemaId,
                       baglanti_surumu_id = :connectionVersionId,
                       guncellenme_zamani = current_timestamp,
                       versiyon_no = se.versiyon_no + 1
                  from akis.fiziksel_sema f
                 where se.proje_id = :projectId and se.uuid = :uuid
                   and se.versiyon_no = :expectedVersion
                   and f.proje_id = :projectId and f.id = :physicalSchemaId
                """)
                .param("projectId", projectId).param("uuid", uuid)
                .param("logicalSchemaId", logicalSchemaId).param("environmentId", environmentId)
                .param("physicalSchemaId", physicalSchemaId).param("connectionVersionId", connectionVersionId)
                .param("expectedVersion", expectedVersion).update();
        return updated == 0 ? Optional.empty() : findSchemaBinding(projectId, uuid);
    }

    List<SchemaBindingRow> listSchemaBindings(long projectId) {
        return jdbc.sql(schemaBindingSelect()
                        + " where b.proje_id = :projectId and l.arsivlenme_zamani is null and o.arsivlenme_zamani is null and f.arsivlenme_zamani is null order by o.kod, l.kod")
                .param("projectId", projectId)
                .query(this::mapSchemaBinding)
                .list();
    }

    Optional<SchemaBindingRow> findSchemaBinding(long projectId, UUID uuid) {
        return jdbc.sql(schemaBindingSelect()
                        + " where b.proje_id = :projectId and b.uuid = :uuid and l.arsivlenme_zamani is null and o.arsivlenme_zamani is null and f.arsivlenme_zamani is null")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapSchemaBinding)
                .optional();
    }

    private String connectionSelect() {
        return """
                select id, proje_id, uuid, kod, saglayici_turu,
                       case when arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum,
                       ad, aciklama, versiyon_no,
                       (select k.gorunen_ad from akis.kullanici k where k.id = baglanti.olusturan_kullanici_id) as created_by,
                       olusturulma_zamani as connection_created_at
                  from akis.baglanti
                """;
    }

    private ConnectionRow mapConnection(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ConnectionRow(
                rs.getLong("id"), rs.getLong("proje_id"),
                rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("saglayici_turu"), rs.getString("durum"),
                rs.getString("ad"), rs.getString("aciklama"), rs.getLong("versiyon_no"),
                rs.getString("created_by"), rs.getObject("connection_created_at", OffsetDateTime.class));
    }

    private String connectionVersionSelect() {
        return """
                select bs.id, bs.uuid, bs.baglanti_id, bs.surum_no, bs.baglanti_modu,
                       bs.surucu_sinifi, bk.kullanici_adi, bs.sunucu_adi, bs.servis_adi, bs.sid,
                       bs.veritabani_adi, bs.jndi_adi, bs.tls_modu, bs.port,
                       bs.baglanti_zaman_asimi_ms, bs.okuma_zaman_asimi_ms,
                       bs.ag_zaman_asimi_ms, bs.sorgu_zaman_asimi_saniye,
                       bs.kullanim_amaci, bs.olusturulma_zamani,
                       case bs.durum when 'TASLAK' then 'DRAFT'
                            when 'TEST_EDILDI' then 'TESTED'
                            when 'ETKIN' then 'ACTIVE' else 'DISABLED' end as lifecycle_status,
                       bs.versiyon_no as lifecycle_version,
                       bs.hedef_kimlik_surumu as target_identity_version,
                       bs.hedef_parmak_izi as target_fingerprint,
                       bs.son_basarili_test_uuid as latest_successful_test_uuid,
                       bs.test_edilme_zamani as tested_at,
                       bs.etkinlestirilme_zamani as activated_at
                  from akis.baglanti_surumu bs
                  left join akis.baglanti_kimligi bk
                    on bk.baglanti_surumu_id = bs.id and bk.kullanim_amaci = 'VERITABANI'
                """;
    }

    private ConnectionVersionRow mapConnectionVersion(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ConnectionVersionRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("baglanti_id"), rs.getInt("surum_no"),
                rs.getString("baglanti_modu"), rs.getString("surucu_sinifi"), rs.getString("kullanici_adi"), rs.getString("sunucu_adi"),
                rs.getString("servis_adi"), rs.getString("sid"),
                rs.getString("veritabani_adi"), rs.getString("jndi_adi"), apiTlsMode(rs.getString("tls_modu")),
                rs.getObject("port", Integer.class), 2,
                connectionPolicy(rs),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                rs.getString("lifecycle_status"), rs.getLong("lifecycle_version"),
                rs.getObject("target_identity_version", Integer.class),
                rs.getString("target_fingerprint"),
                rs.getObject("latest_successful_test_uuid", UUID.class),
                rs.getObject("tested_at", OffsetDateTime.class),
                rs.getObject("activated_at", OffsetDateTime.class));
    }

    private String physicalSchemaSelect() {
        return """
                select f.id, f.uuid, f.baglanti_id, c.uuid as connection_uuid,
                       f.kod, f.sema_adi, case when f.arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum,
                       f.ad, f.versiyon_no
                  from akis.fiziksel_sema f
                  join akis.baglanti c on c.id = f.baglanti_id
                """;
    }

    private PhysicalSchemaRow mapPhysicalSchema(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PhysicalSchemaRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("baglanti_id"), rs.getObject("connection_uuid", UUID.class),
                rs.getString("kod"), rs.getString("sema_adi"),
                rs.getString("durum"), rs.getString("ad"), rs.getLong("versiyon_no"));
    }

    private String logicalSchemaSelect() {
        return """
                select id, uuid, kod,
                       case when arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum,
                       ad, aciklama, versiyon_no
                  from akis.mantiksal_sema
                """;
    }

    private LogicalSchemaRow mapLogicalSchema(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new LogicalSchemaRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("durum"), rs.getString("ad"), rs.getString("aciklama"),
                rs.getLong("versiyon_no"));
    }

    private String environmentSelect() {
        return """
                select id, uuid, kod,
                       risk,
                       case when arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum,
                       politika_sema_surumu, politika, ad, versiyon_no
                  from akis.ortam
                """;
    }

    private EnvironmentRow mapEnvironment(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new EnvironmentRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("risk"), rs.getString("durum"),
                rs.getInt("politika_sema_surumu"), json(rs.getString("politika")),
                rs.getString("ad"), rs.getLong("versiyon_no"));
    }

    private String schemaBindingSelect() {
        return """
                select b.uuid, l.uuid as logical_schema_uuid, o.uuid as environment_uuid,
                       f.uuid as physical_schema_uuid, v.uuid as connection_version_uuid,
                       'AKTIF'::text as durum, b.versiyon_no
                  from akis.sema_eslemesi b
                  join akis.mantiksal_sema l on l.id = b.mantiksal_sema_id
                  join akis.ortam o on o.id = b.ortam_id
                  join akis.fiziksel_sema f on f.id = b.fiziksel_sema_id
                  join akis.baglanti_surumu v on v.id = b.baglanti_surumu_id
                """;
    }

    private SchemaBindingRow mapSchemaBinding(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SchemaBindingRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("logical_schema_uuid", UUID.class),
                rs.getObject("environment_uuid", UUID.class),
                rs.getObject("physical_schema_uuid", UUID.class),
                rs.getObject("connection_version_uuid", UUID.class),
                rs.getString("durum"), rs.getLong("versiyon_no"));
    }

    private JsonNode connectionPolicy(java.sql.ResultSet rs) throws java.sql.SQLException {
        var policy = objectMapper.createObjectNode();
        policy.put("connectTimeoutMs", rs.getInt("baglanti_zaman_asimi_ms"));
        policy.put("readTimeoutMs", rs.getInt("okuma_zaman_asimi_ms"));
        policy.put("networkTimeoutMs", rs.getInt("ag_zaman_asimi_ms"));
        policy.put("queryTimeoutSeconds", rs.getInt("sorgu_zaman_asimi_saniye"));
        String purpose = rs.getString("kullanim_amaci");
        if (purpose != null) {
            policy.put("purpose", purpose);
        }
        return policy;
    }

    private int policyInteger(JsonNode policy, String field, int fallback) {
        JsonNode value = policy.get(field);
        return value == null ? fallback : value.intValue();
    }

    private String policyText(JsonNode policy, String field) {
        JsonNode value = policy.get(field);
        return value == null || !value.isString() ? null : value.stringValue();
    }

    private String storedTlsMode(String value) {
        return switch (value) {
            case "DISABLED" -> "DEVRE_DISI";
            case "REQUIRED" -> "ZORUNLU";
            case "VERIFY_CA" -> "SERTIFIKA_DOGRULA";
            case "VERIFY_FULL" -> "TAM_DOGRULA";
            default -> value;
        };
    }

    private String apiTlsMode(String value) {
        return switch (value) {
            case "DEVRE_DISI" -> "DISABLED";
            case "ZORUNLU" -> "REQUIRED";
            case "SERTIFIKA_DOGRULA" -> "VERIFY_CA";
            case "TAM_DOGRULA" -> "VERIFY_FULL";
            default -> value;
        };
    }

    private String storedCredentialPurpose(String value) {
        return switch (value) {
            case "KIMLIK" -> "VERITABANI";
            case "CLIENT_SERTIFIKA" -> "ISTEMCI_SERTIFIKASI";
            default -> value;
        };
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored environment policy could not be read.", exception);
        }
    }
}

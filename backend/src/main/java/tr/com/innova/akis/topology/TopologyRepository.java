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
                                  'AKTIF'::text as durum, ad, aciklama, versiyon_no
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
        return jdbc.sql(connectionSelect() + " where proje_id = :projectId order by kod")
                .param("projectId", projectId)
                .query(this::mapConnection)
                .list();
    }

    Optional<ConnectionRow> findConnection(long projectId, UUID uuid) {
        return jdbc.sql(connectionSelect() + " where proje_id = :projectId and uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapConnection)
                .optional();
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
            String role) {
        jdbc.sql("""
                        insert into akis.baglanti_kimligi(
                            proje_id, baglanti_surumu_id, kullanim_amaci,
                            gizli_deger_saglayicisi, gizli_deger_konumu)
                        values (:projectId, :connectionVersionId, :role, :provider, :referencePath)
                        """)
                .param("projectId", projectId)
                .param("connectionVersionId", connectionVersionId)
                .param("provider", provider)
                .param("referencePath", referencePath)
                .param("role", storedCredentialPurpose(role))
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
        return jdbc.sql(physicalSchemaSelect() + " where f.proje_id = :projectId order by f.kod")
                .param("projectId", projectId)
                .query(this::mapPhysicalSchema)
                .list();
    }

    Optional<PhysicalSchemaRow> findPhysicalSchema(long projectId, UUID uuid) {
        return jdbc.sql(physicalSchemaSelect()
                        + " where f.proje_id = :projectId and f.uuid = :uuid")
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

    List<LogicalSchemaRow> listLogicalSchemas(long projectId) {
        return jdbc.sql(logicalSchemaSelect() + " where proje_id = :projectId order by kod")
                .param("projectId", projectId)
                .query(this::mapLogicalSchema)
                .list();
    }

    Optional<LogicalSchemaRow> findLogicalSchema(long projectId, UUID uuid) {
        return jdbc.sql(logicalSchemaSelect()
                        + " where proje_id = :projectId and uuid = :uuid")
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
                            proje_id, uuid, kod, uretim_mi, ad)
                        values (:projectId, :uuid, :code, :production, :name)
                        returning id, uuid, kod,
                                  case when uretim_mi then 'URETIM' else 'DUSUK' end as risk,
                                  'AKTIF'::text as durum, ad, versiyon_no
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("production", "URETIM".equals(risk))
                .param("name", name)
                .query(this::mapEnvironment)
                .single();
    }

    List<EnvironmentRow> listEnvironments(long projectId) {
        return jdbc.sql(environmentSelect() + " where proje_id = :projectId order by kod")
                .param("projectId", projectId)
                .query(this::mapEnvironment)
                .list();
    }

    Optional<EnvironmentRow> findEnvironment(long projectId, UUID uuid) {
        return jdbc.sql(environmentSelect() + " where proje_id = :projectId and uuid = :uuid")
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

    List<SchemaBindingRow> listSchemaBindings(long projectId) {
        return jdbc.sql(schemaBindingSelect()
                        + " where b.proje_id = :projectId order by o.kod, l.kod")
                .param("projectId", projectId)
                .query(this::mapSchemaBinding)
                .list();
    }

    Optional<SchemaBindingRow> findSchemaBinding(long projectId, UUID uuid) {
        return jdbc.sql(schemaBindingSelect()
                        + " where b.proje_id = :projectId and b.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapSchemaBinding)
                .optional();
    }

    private String connectionSelect() {
        return """
                select id, proje_id, uuid, kod, saglayici_turu,
                       case when arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum,
                       ad, aciklama, versiyon_no
                  from akis.baglanti
                """;
    }

    private ConnectionRow mapConnection(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ConnectionRow(
                rs.getLong("id"), rs.getLong("proje_id"),
                rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("saglayici_turu"), rs.getString("durum"),
                rs.getString("ad"), rs.getString("aciklama"), rs.getLong("versiyon_no"));
    }

    private String connectionVersionSelect() {
        return """
                select bs.id, bs.uuid, bs.baglanti_id, bs.surum_no, bs.baglanti_modu,
                       bs.surucu_sinifi, bs.sunucu_adi, bs.servis_adi, bs.sid,
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
                """;
    }

    private ConnectionVersionRow mapConnectionVersion(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ConnectionVersionRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("baglanti_id"), rs.getInt("surum_no"),
                rs.getString("baglanti_modu"), rs.getString("surucu_sinifi"), rs.getString("sunucu_adi"),
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
                       case when uretim_mi then 'URETIM' else 'DUSUK' end as risk,
                       case when arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum,
                       ad, versiyon_no
                  from akis.ortam
                """;
    }

    private EnvironmentRow mapEnvironment(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new EnvironmentRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("risk"), rs.getString("durum"),
                1, objectMapper.createObjectNode(),
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
}

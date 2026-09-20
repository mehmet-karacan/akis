package tr.com.innova.akis.oracle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DataObjectCaptureProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.PhysicalSchemaProfile;

/** Topology is global: connection and physical schema lookups are not project scoped. */
@Repository
public class OracleDiscoveryRepository {

    static final String PROFILE_SELECT = """
            select b.id as connection_id,
                   b.uuid as connection_uuid,
                   b.saglayici_turu,
                   b.baglanti_modu,
                   b.jndi_adi,
                   b.surucu_sinifi,
                   b.sunucu_adi,
                   case when b.saglayici_turu = 'ORACLE' then b.servis_adi else b.veritabani_adi end as servis_adi,
                   b.sid,
                   b.port,
                   b.baglanti_zaman_asimi_ms,
                   b.okuma_zaman_asimi_ms,
                   b.sorgu_zaman_asimi_saniye,
                   b.sifre,
                   b.durum
              from akis.baglanti b
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OracleDiscoveryRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ConnectionProfile> findConnectionProfile(UUID connectionUuid) {
        return jdbc.sql(PROFILE_SELECT + " where b.uuid = :connectionUuid")
                .param("connectionUuid", connectionUuid)
                .query((rs, rowNum) -> mapProfile(rs, objectMapper))
                .optional();
    }

    static ConnectionProfile mapProfile(java.sql.ResultSet rs, ObjectMapper objectMapper) throws java.sql.SQLException {
        String secret = rs.getString("sifre");
        boolean jndi = "JNDI".equals(rs.getString("baglanti_modu"));
        UUID uuid = rs.getObject("connection_uuid", UUID.class);
        return new ConnectionProfile(
                0L,
                rs.getLong("connection_id"),
                uuid,
                uuid,
                rs.getString("saglayici_turu"),
                rs.getString("baglanti_modu"),
                rs.getString("jndi_adi"),
                rs.getString("surucu_sinifi"),
                rs.getString("sunucu_adi"),
                rs.getString("servis_adi"),
                rs.getString("sid"),
                "DISABLED",
                rs.getInt("port"),
                policy(rs, objectMapper),
                jndi ? null : "TABLO",
                jndi ? null : secret,
                jndi || secret == null ? null : "AKTIF",
                "ETKIN".equals(rs.getString("durum")) ? "ACTIVE" : "DISABLED",
                1L,
                null,
                null,
                null);
    }

    Optional<DataObjectCaptureProfile> findDataObjectCaptureProfile(
            long projectId,
            UUID dataObjectUuid,
            UUID physicalSchemaUuid) {
        return jdbc.sql("""
                        select vn.uuid, vn.nesne_referansi,
                               case vn.tur when 'GORUNUM' then 'VIEW' else vn.tur end as tur_kodu,
                               case when vn.arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum_kodu
                          from akis.veri_nesnesi vn
                          join akis.model m
                            on m.proje_id = vn.proje_id
                           and m.id = vn.model_id
                         where vn.proje_id = :projectId
                           and vn.uuid = :dataObjectUuid
                           and m.arsivlenme_zamani is null
                           and exists (
                               select 1
                                 from akis.sema_eslemesi ose
                                 join akis.fiziksel_sema fs on fs.id = ose.fiziksel_sema_id
                                where ose.mantiksal_sema_id = m.mantiksal_sema_id
                                  and fs.uuid = :physicalSchemaUuid)
                        """)
                .param("projectId", projectId)
                .param("dataObjectUuid", dataObjectUuid)
                .param("physicalSchemaUuid", physicalSchemaUuid)
                .query((rs, rowNum) -> new DataObjectCaptureProfile(
                        rs.getObject("uuid", UUID.class),
                        rs.getString("nesne_referansi"),
                        rs.getString("tur_kodu"),
                        rs.getString("durum_kodu")))
                .optional();
    }

    Optional<Long> findProjectId(UUID projectUuid) {
        return jdbc.sql("select id from akis.proje where uuid = :uuid")
                .param("uuid", projectUuid).query(Long.class).optional();
    }

    Optional<PhysicalSchemaProfile> findPhysicalSchema(UUID physicalSchemaUuid) {
        return jdbc.sql("""
                        select uuid, baglanti_id, sema_adi,
                               case durum when 'ETKIN' then 'AKTIF' else 'PASIF' end as durum
                          from akis.fiziksel_sema
                         where uuid = :physicalSchemaUuid
                        """)
                .param("physicalSchemaUuid", physicalSchemaUuid)
                .query((rs, rowNum) -> new PhysicalSchemaProfile(
                        rs.getObject("uuid", UUID.class),
                        rs.getLong("baglanti_id"),
                        rs.getString("sema_adi"),
                        rs.getString("durum")))
                .optional();
    }

    static JsonNode policy(java.sql.ResultSet rs, ObjectMapper objectMapper) throws java.sql.SQLException {
        var policy = objectMapper.createObjectNode();
        policy.put("connectTimeoutMs", rs.getInt("baglanti_zaman_asimi_ms"));
        policy.put("readTimeoutMs", rs.getInt("okuma_zaman_asimi_ms"));
        policy.put("networkTimeoutMs", rs.getInt("okuma_zaman_asimi_ms"));
        policy.put("queryTimeoutSeconds", rs.getInt("sorgu_zaman_asimi_saniye"));
        return policy;
    }
}

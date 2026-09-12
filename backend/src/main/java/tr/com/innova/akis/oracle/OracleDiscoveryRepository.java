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

@Repository
public class OracleDiscoveryRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OracleDiscoveryRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ConnectionProfile> findConnectionProfile(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid) {
        return jdbc.sql("""
                        select p.id as project_id,
                               b.id as connection_id,
                               b.uuid as connection_uuid,
                               bs.uuid as connection_version_uuid,
                               b.saglayici_turu,
                               bs.baglanti_modu,
                               bs.jndi_adi,
                               bs.surucu_sinifi,
                               bs.sunucu_adi,
                               bs.servis_adi,
                               bs.sid,
                               bs.tls_modu,
                               bs.port,
                               bs.baglanti_zaman_asimi_ms,
                               bs.okuma_zaman_asimi_ms,
                               bs.ag_zaman_asimi_ms,
                               bs.sorgu_zaman_asimi_saniye,
                               bk.gizli_deger_saglayicisi,
                               bk.gizli_deger_konumu,
                               case when bk.id is null then null else 'AKTIF' end as secret_status,
                               case bs.durum when 'TASLAK' then 'DRAFT'
                                    when 'TEST_EDILDI' then 'TESTED'
                                    when 'ETKIN' then 'ACTIVE' else 'DISABLED' end as lifecycle_status,
                               bs.versiyon_no as lifecycle_state_version,
                               bs.son_basarili_test_uuid,
                               bs.hedef_kimlik_surumu,
                               bs.hedef_parmak_izi
                          from akis.proje p
                          join akis.baglanti b
                            on b.proje_id = p.id
                          join akis.baglanti_surumu bs
                            on bs.proje_id = p.id
                           and bs.baglanti_id = b.id
                          left join akis.baglanti_kimligi bk
                            on bk.proje_id = p.id
                           and bk.baglanti_surumu_id = bs.id
                           and bk.kullanim_amaci = 'VERITABANI'
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .query((rs, rowNum) -> new ConnectionProfile(
                        rs.getLong("project_id"),
                        rs.getLong("connection_id"),
                        rs.getObject("connection_uuid", UUID.class),
                        rs.getObject("connection_version_uuid", UUID.class),
                        rs.getString("saglayici_turu"),
                        rs.getString("baglanti_modu"),
                        rs.getString("jndi_adi"),
                        rs.getString("surucu_sinifi"),
                        rs.getString("sunucu_adi"),
                        rs.getString("servis_adi"),
                        rs.getString("sid"),
                        apiTlsMode(rs.getString("tls_modu")),
                        rs.getInt("port"),
                        policy(rs),
                        rs.getString("gizli_deger_saglayicisi"),
                        rs.getString("gizli_deger_konumu"),
                        rs.getString("secret_status"),
                        rs.getString("lifecycle_status"),
                        rs.getLong("lifecycle_state_version"),
                        rs.getObject("son_basarili_test_uuid", UUID.class),
                        rs.getObject("hedef_kimlik_surumu", Integer.class),
                        rs.getString("hedef_parmak_izi")))
                .optional();
    }

    Optional<DataObjectCaptureProfile> findDataObjectCaptureProfile(
            long projectId,
            UUID dataObjectUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid) {
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
                                 join akis.fiziksel_sema fs
                                   on fs.proje_id = ose.proje_id
                                  and fs.id = ose.fiziksel_sema_id
                                 join akis.baglanti_surumu bs
                                   on bs.proje_id = ose.proje_id
                                  and bs.id = ose.baglanti_surumu_id
                                where ose.proje_id = vn.proje_id
                                  and ose.mantiksal_sema_id = m.mantiksal_sema_id
                                  and fs.uuid = :physicalSchemaUuid
                                  and bs.uuid = :connectionVersionUuid)
                        """)
                .param("projectId", projectId)
                .param("dataObjectUuid", dataObjectUuid)
                .param("physicalSchemaUuid", physicalSchemaUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .query((rs, rowNum) -> new DataObjectCaptureProfile(
                        rs.getObject("uuid", UUID.class),
                        rs.getString("nesne_referansi"),
                        rs.getString("tur_kodu"),
                        rs.getString("durum_kodu")))
                .optional();
    }

    Optional<PhysicalSchemaProfile> findPhysicalSchema(
            long projectId,
            UUID physicalSchemaUuid) {
        return jdbc.sql("""
                        select uuid, baglanti_id, sema_adi,
                               case when arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as durum
                          from akis.fiziksel_sema
                         where proje_id = :projectId
                           and uuid = :physicalSchemaUuid
                        """)
                .param("projectId", projectId)
                .param("physicalSchemaUuid", physicalSchemaUuid)
                .query((rs, rowNum) -> new PhysicalSchemaProfile(
                        rs.getObject("uuid", UUID.class),
                        rs.getLong("baglanti_id"),
                        rs.getString("sema_adi"),
                        rs.getString("durum")))
                .optional();
    }

    private JsonNode policy(java.sql.ResultSet rs) throws java.sql.SQLException {
        var policy = objectMapper.createObjectNode();
        policy.put("connectTimeoutMs", rs.getInt("baglanti_zaman_asimi_ms"));
        policy.put("readTimeoutMs", rs.getInt("okuma_zaman_asimi_ms"));
        policy.put("networkTimeoutMs", rs.getInt("ag_zaman_asimi_ms"));
        policy.put("queryTimeoutSeconds", rs.getInt("sorgu_zaman_asimi_saniye"));
        return policy;
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
}

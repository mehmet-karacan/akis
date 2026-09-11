package tr.com.innova.akis.oracle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
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
                               b.veritabani_turu,
                               bs.baglanti_modu,
                               bs.jndi_adi,
                               bs.surucu_referansi,
                               bs.sunucu_adi,
                               bs.servis_adi,
                               bs.sid,
                               bs.tls_modu,
                               bs.port,
                               bs.politika,
                               sr.saglayici_kodu,
                               sr.referans_yolu,
                               sr.durum_kodu as secret_status,
                               yd.durum_kodu as lifecycle_status,
                               yd.durum_surumu as lifecycle_state_version,
                               yd.son_basarili_test_uuid,
                               yd.hedef_kimlik_surumu,
                               yd.hedef_parmak_izi
                          from entegrasyon.proje p
                          join entegrasyon.baglanti b
                            on b.proje_id = p.id
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = p.id
                           and bs.baglanti_id = b.id
                          join entegrasyon.baglanti_surumu_yasam_dongusu yd
                            on yd.proje_id = p.id
                           and yd.baglanti_id = b.id
                           and yd.baglanti_surumu_id = bs.id
                          left join entegrasyon.baglanti_secret_bagi ssb
                            on ssb.proje_id = p.id
                           and ssb.baglanti_surumu_id = bs.id
                           and ssb.rol_kodu = 'KIMLIK'
                          left join entegrasyon.secret_referansi sr
                            on sr.proje_id = p.id
                           and sr.id = ssb.secret_referansi_id
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
                        rs.getString("veritabani_turu"),
                        rs.getString("baglanti_modu"),
                        rs.getString("jndi_adi"),
                        rs.getString("surucu_referansi"),
                        rs.getString("sunucu_adi"),
                        rs.getString("servis_adi"),
                        rs.getString("sid"),
                        rs.getString("tls_modu"),
                        rs.getInt("port"),
                        json(rs.getString("politika")),
                        rs.getString("saglayici_kodu"),
                        rs.getString("referans_yolu"),
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
                        select vn.uuid, vn.nesne_referansi, vn.tur_kodu, vn.durum_kodu
                          from entegrasyon.veri_nesnesi vn
                          join entegrasyon.model m
                            on m.proje_id = vn.proje_id
                           and m.id = vn.model_id
                         where vn.proje_id = :projectId
                           and vn.uuid = :dataObjectUuid
                           and m.durum_kodu = 'AKTIF'
                           and exists (
                               select 1
                                 from entegrasyon.ortam_sema_eslemesi ose
                                 join entegrasyon.fiziksel_sema fs
                                   on fs.proje_id = ose.proje_id
                                  and fs.id = ose.fiziksel_sema_id
                                 join entegrasyon.baglanti_surumu bs
                                   on bs.proje_id = ose.proje_id
                                  and bs.id = ose.baglanti_surumu_id
                                where ose.proje_id = vn.proje_id
                                  and ose.mantiksal_sema_id = m.mantiksal_sema_id
                                  and ose.durum_kodu = 'AKTIF'
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
                        select uuid, baglanti_id, sema_referansi, durum_kodu
                          from entegrasyon.fiziksel_sema
                         where proje_id = :projectId
                           and uuid = :physicalSchemaUuid
                        """)
                .param("projectId", projectId)
                .param("physicalSchemaUuid", physicalSchemaUuid)
                .query((rs, rowNum) -> new PhysicalSchemaProfile(
                        rs.getObject("uuid", UUID.class),
                        rs.getLong("baglanti_id"),
                        rs.getString("sema_referansi"),
                        rs.getString("durum_kodu")))
                .optional();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Kayıtlı bağlantı politikası okunamadı.", exception);
        }
    }
}

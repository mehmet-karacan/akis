package tr.com.innova.akis.oracle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
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
                               bs.surucu_referansi,
                               bs.sunucu_adi,
                               bs.servis_adi,
                               bs.sid,
                               bs.tls_modu,
                               bs.port,
                               bs.politika,
                               sr.saglayici_kodu,
                               sr.referans_yolu,
                               sr.durum_kodu as secret_status
                          from entegrasyon.proje p
                          join entegrasyon.baglanti b
                            on b.proje_id = p.id
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = p.id
                           and bs.baglanti_id = b.id
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
                        rs.getString("surucu_referansi"),
                        rs.getString("sunucu_adi"),
                        rs.getString("servis_adi"),
                        rs.getString("sid"),
                        rs.getString("tls_modu"),
                        rs.getInt("port"),
                        json(rs.getString("politika")),
                        rs.getString("saglayici_kodu"),
                        rs.getString("referans_yolu"),
                        rs.getString("secret_status")))
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

package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;

/** Loads only the exact, active published binding selected by a runtime plan. */
@Repository
public class JdbcRuntimeOracleConnectionMetadataStore
        implements RuntimeOracleConnectionMetadataPort {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRuntimeOracleConnectionMetadataStore(
            JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConnectionProfile> find(DatasetBinding binding) {
        if (binding == null || (binding.role() != PilotRuntimePlan.DatasetRole.SOURCE
                && binding.role() != PilotRuntimePlan.DatasetRole.TARGET)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select distinct p.uuid as project_uuid,
                               bs.uuid as connection_version_uuid,
                               bs.baglanti_modu,
                               bs.jndi_adi,
                               bs.surucu_sinifi as surucu_referansi,
                               bs.sunucu_adi,
                               bs.servis_adi,
                               bs.sid,
                               bs.port,
                               bs.tls_modu,
                               jsonb_build_object(
                                  'connectTimeoutMs',bs.baglanti_zaman_asimi_ms,
                                  'readTimeoutMs',bs.okuma_zaman_asimi_ms,
                                  'networkTimeoutMs',bs.ag_zaman_asimi_ms,
                                  'queryTimeoutSeconds',bs.sorgu_zaman_asimi_saniye) as politika,
                               bk.gizli_deger_saglayicisi as saglayici_kodu,
                               bk.gizli_deger_konumu as referans_yolu
                          from akis.tanim_veri_nesnesi tvn
                          join akis.proje p on p.id = tvn.proje_id
                          join akis.yayin_veri_bagi yb
                            on yb.proje_id = tvn.proje_id
                           and yb.tanim_veri_nesnesi_id = tvn.id
                          join akis.veri_nesnesi vn
                            on vn.proje_id = tvn.proje_id
                           and vn.id = tvn.veri_nesnesi_id
                          join akis.sema_goruntusu sg
                            on sg.proje_id = tvn.proje_id
                           and sg.id = tvn.sema_goruntusu_id
                          join akis.sema_eslemesi ose
                            on ose.proje_id = tvn.proje_id
                          join akis.fiziksel_sema fs
                            on fs.proje_id = tvn.proje_id
                           and fs.id = ose.fiziksel_sema_id
                          join akis.baglanti_surumu bs
                            on bs.proje_id = tvn.proje_id
                           and bs.id = ose.baglanti_surumu_id
                          join akis.baglanti b
                            on b.proje_id = tvn.proje_id
                           and b.id = bs.baglanti_id
                          left join akis.baglanti_kimligi bk
                            on bk.proje_id = tvn.proje_id
                           and bk.baglanti_surumu_id = bs.id
                           and bk.kullanim_amaci = 'VERITABANI'
                         where tvn.uuid = :definitionDataObjectUuid
                           and tvn.dugum_kodu = :datasetId
                           and vn.uuid = :dataObjectUuid
                           and vn.tur = 'TABLO'
                           and vn.nesne_referansi = :objectName
                           and sg.uuid = :schemaSnapshotUuid
                           and sg.veri_nesnesi_id = vn.id
                           and sg.fiziksel_sema_id = fs.id
                           and sg.baglanti_surumu_id = bs.id
                           and sg.parmak_izi = :snapshotFingerprint
                           and yb.sema_eslemesi_id = ose.id
                           and yb.fiziksel_sema_id = fs.id
                           and yb.baglanti_surumu_id = bs.id
                           and yb.sema_goruntusu_id = sg.id
                           and yb.fiziksel_kimlik = :physicalIdentity
                           and yb.bag_surumu = :bindingVersion
                           and ose.uuid = :environmentSchemaBindingUuid
                           and fs.uuid = :physicalSchemaUuid
                           and fs.sema_adi = :owner
                           and fs.baglanti_id = bs.baglanti_id
                           and bs.uuid = :connectionVersionUuid
                           and b.saglayici_turu = 'ORACLE'
                           and b.arsivlenme_zamani is null
                           and fs.arsivlenme_zamani is null
                           and bs.durum = 'ETKIN'
                           and (bs.baglanti_modu = 'JNDI' or bk.id is not null)
                           and tvn.rol = :storedRole
                        """)
                .param("definitionDataObjectUuid", binding.definitionDataObjectUuid())
                .param("datasetId", binding.datasetId())
                .param("dataObjectUuid", binding.dataObjectUuid())
                .param("objectName", binding.objectName())
                .param("schemaSnapshotUuid", binding.schemaSnapshotUuid())
                .param("snapshotFingerprint", binding.schemaSnapshotFingerprint())
                .param("physicalIdentity", binding.physicalIdentity())
                .param("bindingVersion", binding.bindingVersion())
                .param("environmentSchemaBindingUuid", binding.environmentSchemaBindingUuid())
                .param("physicalSchemaUuid", binding.physicalSchemaUuid())
                .param("owner", binding.owner())
                .param("connectionVersionUuid", binding.connectionVersionUuid())
                .param("storedRole", binding.role() == PilotRuntimePlan.DatasetRole.SOURCE
                        ? "KAYNAK" : "HEDEF")
                .query((rs, rowNum) -> new ConnectionProfile(
                        rs.getObject("project_uuid", UUID.class),
                        rs.getObject("connection_version_uuid", UUID.class),
                        rs.getString("baglanti_modu"), rs.getString("jndi_adi"),
                        rs.getString("surucu_referansi"), rs.getString("sunucu_adi"),
                        rs.getString("servis_adi"), rs.getString("sid"),
                        rs.getInt("port"), rs.getString("tls_modu"),
                        json(rs.getString("politika")), rs.getString("saglayici_kodu"),
                        rs.getString("referans_yolu")))
                .optional();
    }

    private JsonNode json(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException(
                        "Runtime Oracle connection metadata is unavailable.");
            }
            return node;
        }
        catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Runtime Oracle connection metadata is unavailable.");
        }
    }
}

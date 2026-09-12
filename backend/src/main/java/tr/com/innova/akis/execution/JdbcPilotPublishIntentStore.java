package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.PilotPublishIntentPort.PilotPublishIntent;

/** Loads the one append-only intent only through its exact active publish lease. */
@Repository
public class JdbcPilotPublishIntentStore implements PilotPublishIntentPort {

    private final JdbcClient jdbc;

    public JdbcPilotPublishIntentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PilotPublishIntent> find(UUID runUuid) {
        if (runUuid == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select p.uuid as project_uuid,
                               y.uuid as publication_uuid,
                               it.uuid as job_request_uuid,
                               c.uuid as run_uuid,
                               pyn.deneme_no,
                               pyn.calistirma_nesil_no,
                               pyn.isleyici_referansi,
                               hk.uuid as target_resource_uuid,
                               pyn.hedef_nesil_no,
                               pyn.hedef_fiziksel_ozeti,
                               pyn.hedef_kimlik_surumu,
                               pyn.yayin_ozeti,
                               pyn.plan_ozeti,
                               pyn.runtime_plan_ozeti,
                               pyn.yayin_anahtari_ozeti,
                               pyn.payload_ozeti,
                               pyn.satir_sayisi,
                               pyn.bayt_sayisi
                          from akis.pilot_yayin_niyeti pyn
                          join akis.calistirma c
                            on c.proje_id = pyn.proje_id
                           and c.id = pyn.calistirma_id
                          join akis.proje p
                            on p.id = pyn.proje_id
                          join akis.is_talebi it
                            on it.proje_id = pyn.proje_id
                           and it.id = pyn.is_talebi_id
                          join akis.yayin y
                            on y.proje_id = it.proje_id
                           and y.id = it.yayin_id
                          join akis.calistirma_durumu cd
                            on cd.proje_id = pyn.proje_id
                           and cd.calistirma_id = pyn.calistirma_id
                          join akis.hedef_kaynagi hk
                            on hk.id = pyn.hedef_kaynagi_id
                         where c.uuid = :runUuid
                           and cd.durum = 'YAYINLANIYOR'
                           and cd.nesil_no = pyn.calistirma_nesil_no
                           and cd.isleyici_referansi = pyn.isleyici_referansi
                           and cd.kiralama_bitis_zamani > clock_timestamp()
                           and cd.hedef_kaynagi_id = pyn.hedef_kaynagi_id
                           and cd.hedef_nesil_no = pyn.hedef_nesil_no
                           and hk.durum = 'SAHIPLENILDI'
                           and hk.calistirma_id = pyn.calistirma_id
                           and hk.nesil_no = pyn.hedef_nesil_no
                           and hk.kiralama_bitis_zamani > clock_timestamp()
                           and hk.fiziksel_ozet = pyn.hedef_fiziksel_ozeti
                           and hk.kimlik_surumu = pyn.hedef_kimlik_surumu
                        """)
                .param("runUuid", runUuid)
                .query((rs, rowNum) -> new PilotPublishIntent(
                        rs.getObject("project_uuid", UUID.class),
                        rs.getObject("publication_uuid", UUID.class),
                        rs.getObject("job_request_uuid", UUID.class),
                        rs.getObject("run_uuid", UUID.class),
                        rs.getInt("deneme_no"),
                        rs.getLong("calistirma_nesil_no"),
                        rs.getString("isleyici_referansi"),
                        rs.getObject("target_resource_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no"),
                        rs.getString("hedef_fiziksel_ozeti"),
                        rs.getInt("hedef_kimlik_surumu"),
                        rs.getString("yayin_ozeti"),
                        rs.getString("plan_ozeti"),
                        rs.getString("runtime_plan_ozeti"),
                        rs.getString("yayin_anahtari_ozeti"),
                        rs.getString("payload_ozeti"),
                        rs.getLong("satir_sayisi"),
                        rs.getLong("bayt_sayisi")))
                .optional();
    }
}

package tr.com.innova.akis.oracle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tr.com.innova.akis.metadata.ApiException;

@Repository
class OracleSchemaSnapshotEvidenceRepository {

    private final JdbcClient jdbc;

    OracleSchemaSnapshotEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void attest(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID snapshotUuid,
            long expectedLifecycleStateVersion,
            UUID successfulTestUuid,
            int targetIdentityVersion,
            String targetFingerprint) {
        Optional<Long> inserted = jdbc.sql("""
                        insert into entegrasyon.sema_goruntusu_oracle_kaniti(
                            sema_goruntusu_id, proje_id, baglanti_id,
                            baglanti_surumu_id, baglanti_surumu_testi_uuid,
                            hedef_kimlik_surumu, hedef_parmak_izi,
                            yakalama_sozlesmesi_surumu)
                        select sg.id, p.id, b.id, bs.id, :successfulTestUuid,
                               :targetIdentityVersion, :targetFingerprint, 1
                          from entegrasyon.proje p
                          join entegrasyon.baglanti b
                            on b.proje_id = p.id
                           and b.uuid = :connectionUuid
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = p.id
                           and bs.baglanti_id = b.id
                           and bs.uuid = :connectionVersionUuid
                          join entegrasyon.baglanti_surumu_yasam_dongusu yd
                            on yd.proje_id = p.id
                           and yd.baglanti_id = b.id
                           and yd.baglanti_surumu_id = bs.id
                          join entegrasyon.sema_goruntusu sg
                            on sg.proje_id = p.id
                           and sg.baglanti_surumu_id = bs.id
                           and sg.uuid = :snapshotUuid
                         where p.uuid = :projectUuid
                           and yd.durum_kodu = 'ACTIVE'
                           and yd.durum_surumu = :expectedLifecycleStateVersion
                           and yd.son_basarili_test_uuid = :successfulTestUuid
                           and yd.hedef_kimlik_surumu = :targetIdentityVersion
                           and yd.hedef_parmak_izi = :targetFingerprint
                        on conflict (sema_goruntusu_id) do nothing
                        returning sema_goruntusu_id
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .param("snapshotUuid", snapshotUuid)
                .param("expectedLifecycleStateVersion", expectedLifecycleStateVersion)
                .param("successfulTestUuid", successfulTestUuid)
                .param("targetIdentityVersion", targetIdentityVersion)
                .param("targetFingerprint", targetFingerprint)
                .query(Long.class)
                .optional();
        if (inserted.isPresent() || existingEvidenceMatches(
                snapshotUuid, targetIdentityVersion, targetFingerprint)) {
            return;
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "ORACLE_SNAPSHOT_EVIDENCE_REJECTED",
                "Oracle snapshot kanıtı aktif bağlantı durumu değiştiği için kaydedilemedi.");
    }

    private boolean existingEvidenceMatches(
            UUID snapshotUuid,
            int targetIdentityVersion,
            String targetFingerprint) {
        return jdbc.sql("""
                        select exists(
                            select 1
                              from entegrasyon.sema_goruntusu_oracle_kaniti ok
                              join entegrasyon.sema_goruntusu sg
                                on sg.id = ok.sema_goruntusu_id
                             where sg.uuid = :snapshotUuid
                               and ok.hedef_kimlik_surumu = :targetIdentityVersion
                               and ok.hedef_parmak_izi = :targetFingerprint)
                        """)
                .param("snapshotUuid", snapshotUuid)
                .param("targetIdentityVersion", targetIdentityVersion)
                .param("targetFingerprint", targetFingerprint)
                .query(Boolean.class)
                .single();
    }
}

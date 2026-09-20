package tr.com.innova.akis.oracle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tr.com.innova.akis.metadata.ApiException;

/** Records the live target-identity probe taken at capture time as evidence for a snapshot. */
@Repository
class OracleSchemaSnapshotEvidenceRepository {

    private final JdbcClient jdbc;

    OracleSchemaSnapshotEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void attest(
            UUID projectUuid,
            UUID connectionUuid,
            UUID snapshotUuid,
            int targetIdentityVersion,
            String targetFingerprint) {
        Optional<Long> inserted = jdbc.sql("""
                        insert into akis.sema_goruntusu_oracle_kaniti(
                            sema_goruntusu_id, proje_id, baglanti_id,
                            basarili_baglanti_testi_uuid,
                            hedef_kimlik_surumu, hedef_parmak_izi,
                            yakalama_sozlesmesi_surumu)
                        select sg.id, p.id, b.id, t.uuid,
                               :targetIdentityVersion, :targetFingerprint, 1
                          from akis.proje p
                          join akis.baglanti b on b.uuid = :connectionUuid
                          join akis.sema_goruntusu sg
                            on sg.proje_id = p.id
                           and sg.baglanti_id = b.id
                           and sg.uuid = :snapshotUuid
                          left join lateral (
                              select bt.uuid from akis.baglanti_testi bt
                               where bt.baglanti_id = b.id and bt.sonuc = 'BASARILI'
                               order by bt.deneme_no desc limit 1) t on true
                         where p.uuid = :projectUuid
                        on conflict (sema_goruntusu_id) do nothing
                        returning sema_goruntusu_id
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("snapshotUuid", snapshotUuid)
                .param("targetIdentityVersion", targetIdentityVersion)
                .param("targetFingerprint", targetFingerprint)
                .query(Long.class)
                .optional();
        if (inserted.isPresent() || existingEvidenceMatches(snapshotUuid, targetIdentityVersion, targetFingerprint)) {
            return;
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "ORACLE_SNAPSHOT_EVIDENCE_REJECTED",
                "Oracle snapshot kanıtı hedef veritabanı kimliği değiştiği için kaydedilemedi.");
    }

    private boolean existingEvidenceMatches(UUID snapshotUuid, int targetIdentityVersion, String targetFingerprint) {
        return jdbc.sql("""
                        select exists(
                            select 1
                              from akis.sema_goruntusu_oracle_kaniti ok
                              join akis.sema_goruntusu sg on sg.id = ok.sema_goruntusu_id
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

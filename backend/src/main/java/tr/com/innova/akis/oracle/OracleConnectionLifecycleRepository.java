package tr.com.innova.akis.oracle;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.LifecycleRow;
import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.TestAttemptRow;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;

@Repository
public class OracleConnectionLifecycleRepository {

    private final JdbcClient jdbc;

    public OracleConnectionLifecycleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    LifecycleRow lockLifecycle(
            UUID projectUuid, UUID connectionUuid, UUID connectionVersionUuid) {
        return jdbc.sql(lifecycleSelect() + """
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                         for update
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .query(this::mapLifecycle)
                .optional()
                .orElseThrow(() -> new LifecycleNotFoundException());
    }

    Optional<LifecycleRow> findLifecycle(
            UUID projectUuid, UUID connectionUuid, UUID connectionVersionUuid) {
        return jdbc.sql(lifecycleSelect() + """
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .query(this::mapLifecycle)
                .optional();
    }

    int nextAttemptNumber(UUID projectUuid, UUID connectionVersionUuid) {
        return jdbc.sql("""
                        select coalesce(max(t.deneme_no), 0) + 1
                          from akis.baglanti_testi t
                          join akis.proje p on p.id = t.proje_id
                          join akis.baglanti_surumu bs
                            on bs.proje_id = t.proje_id
                           and bs.id = t.baglanti_surumu_id
                         where p.uuid = :projectUuid
                           and bs.uuid = :connectionVersionUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .query(Integer.class)
                .single();
    }

    TestAttemptRow insertSuccessfulAttempt(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID testUuid,
            int attemptNumber,
            String outcome,
            ConnectionProbe probe,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            long durationMs) {
        return jdbc.sql("""
                        insert into akis.baglanti_testi(
                            proje_id, baglanti_surumu_id, uuid, deneme_no,
                            sonuc, hata_kodu, urun_adi, urun_surumu,
                            veritabani_ana_surumu, veritabani_alt_surumu,
                            surucu_adi, surucu_surumu, hedef_kimlik_surumu,
                            hedef_parmak_izi, baslama_zamani, tamamlanma_zamani, sure_milisaniye)
                        select p.id, bs.id, :testUuid, :attemptNumber,
                               case when :outcome = 'PASSED' then 'BASARILI' else 'BASARISIZ' end,
                               :errorCode, :databaseProduct, :databaseVersion,
                               :databaseMajorVersion, :databaseMinorVersion,
                               :driverName, :driverVersion, :targetIdentityVersion,
                               :targetFingerprint, :startedAt, :completedAt, :durationMs
                          from akis.proje p
                          join akis.baglanti b on b.proje_id = p.id
                          join akis.baglanti_surumu bs
                            on bs.proje_id = p.id and bs.baglanti_id = b.id
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                        returning uuid, :connectionVersionUuid::uuid as connection_version_uuid,
                                  deneme_no,
                                  case when sonuc = 'BASARILI' then 'PASSED'
                                       when hata_kodu = 'ORACLE_TARGET_MISMATCH' then 'TARGET_MISMATCH'
                                       else 'FAILED' end as sonuc_kodu,
                                  hata_kodu, urun_adi as database_product,
                                  urun_surumu as database_version,
                                  veritabani_ana_surumu as database_major,
                                  veritabani_alt_surumu as database_minor,
                                  surucu_adi as driver_name, surucu_surumu as driver_version,
                                  hedef_kimlik_surumu, hedef_parmak_izi,
                                  baslama_zamani, tamamlanma_zamani, sure_milisaniye as sure_ms
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .param("testUuid", testUuid)
                .param("attemptNumber", attemptNumber)
                .param("outcome", outcome)
                .param("errorCode", "TARGET_MISMATCH".equals(outcome)
                        ? "ORACLE_TARGET_MISMATCH" : null, Types.VARCHAR)
                .param("databaseProduct", probe.databaseProduct())
                .param("databaseVersion", probe.databaseVersion())
                .param("databaseMajorVersion", probe.databaseMajorVersion())
                .param("databaseMinorVersion", probe.databaseMinorVersion())
                .param("driverName", probe.driverName())
                .param("driverVersion", probe.driverVersion())
                .param("targetIdentityVersion", probe.targetIdentityVersion())
                .param("targetFingerprint", probe.targetFingerprint())
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .param("durationMs", durationMs)
                .query(this::mapTestAttempt)
                .single();
    }

    TestAttemptRow insertFailedAttempt(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID testUuid,
            int attemptNumber,
            String errorCode,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            long durationMs) {
        return jdbc.sql("""
                        insert into akis.baglanti_testi(
                            proje_id, baglanti_surumu_id, uuid, deneme_no,
                            sonuc, hata_kodu, baslama_zamani, tamamlanma_zamani, sure_milisaniye)
                        select p.id, bs.id, :testUuid, :attemptNumber,
                               'BASARISIZ', :errorCode, :startedAt, :completedAt, :durationMs
                          from akis.proje p
                          join akis.baglanti b on b.proje_id = p.id
                          join akis.baglanti_surumu bs
                            on bs.proje_id = p.id and bs.baglanti_id = b.id
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                        returning uuid, :connectionVersionUuid::uuid as connection_version_uuid,
                                  deneme_no, 'FAILED'::text as sonuc_kodu, hata_kodu,
                                  urun_adi as database_product, urun_surumu as database_version,
                                  veritabani_ana_surumu as database_major,
                                  veritabani_alt_surumu as database_minor,
                                  surucu_adi as driver_name, surucu_surumu as driver_version,
                                  hedef_kimlik_surumu, hedef_parmak_izi,
                                  baslama_zamani, tamamlanma_zamani, sure_milisaniye as sure_ms
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .param("testUuid", testUuid)
                .param("attemptNumber", attemptNumber)
                .param("errorCode", safeErrorCode(errorCode))
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .param("durationMs", durationMs)
                .query(this::mapTestAttempt)
                .single();
    }

    void markTested(
            UUID projectUuid,
            UUID connectionVersionUuid,
            UUID testUuid,
            ConnectionProbe probe,
            OffsetDateTime testedAt) {
        jdbc.sql("""
                        update akis.baglanti_surumu bs
                           set durum = case when bs.durum = 'TASLAK' then 'TEST_EDILDI'
                                            else bs.durum end,
                               versiyon_no = bs.versiyon_no + 1,
                               hedef_kimlik_surumu = coalesce(
                                   bs.hedef_kimlik_surumu, :targetIdentityVersion),
                               hedef_parmak_izi = coalesce(
                                   bs.hedef_parmak_izi, :targetFingerprint),
                               son_basarili_test_uuid = :testUuid,
                               test_edilme_zamani = :testedAt,
                               guncellenme_zamani = current_timestamp
                          from akis.proje p
                         where bs.proje_id = p.id
                           and p.uuid = :projectUuid
                           and bs.uuid = :connectionVersionUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .param("targetIdentityVersion", probe.targetIdentityVersion())
                .param("targetFingerprint", probe.targetFingerprint())
                .param("testUuid", testUuid)
                .param("testedAt", testedAt)
                .update();
    }

    LifecycleRow activate(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID testUuid,
            long expectedStateVersion) {
        jdbc.sql("""
                        update akis.baglanti_surumu previous
                           set durum = 'KULLANIM_DISI',
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = previous.versiyon_no + 1
                          from akis.proje p, akis.baglanti b, akis.baglanti_surumu candidate
                         where b.proje_id = p.id
                           and candidate.proje_id = p.id and candidate.baglanti_id = b.id
                           and previous.baglanti_id = b.id and previous.durum = 'ETKIN'
                           and previous.id <> candidate.id
                           and p.uuid = :projectUuid and b.uuid = :connectionUuid
                           and candidate.uuid = :connectionVersionUuid
                           and candidate.durum = 'TEST_EDILDI'
                           and candidate.versiyon_no = :expectedStateVersion
                           and candidate.son_basarili_test_uuid = :testUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .param("testUuid", testUuid)
                .param("expectedStateVersion", expectedStateVersion)
                .update();
        int updated = jdbc.sql("""
                        update akis.baglanti_surumu bs
                           set durum = 'ETKIN',
                               versiyon_no = bs.versiyon_no + 1,
                               etkinlestirilme_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp
                          from akis.proje p
                          join akis.baglanti b on b.proje_id = p.id
                         where bs.proje_id = p.id and bs.baglanti_id = b.id
                           and p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                           and bs.baglanti_modu = 'JDBC'
                           and bs.durum = 'TEST_EDILDI'
                           and bs.versiyon_no = :expectedStateVersion
                           and bs.son_basarili_test_uuid = :testUuid
                           and bs.hedef_parmak_izi is not null
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .param("testUuid", testUuid)
                .param("expectedStateVersion", expectedStateVersion)
                .update();
        if (updated != 1) {
            throw new LifecycleConflictException();
        }
        return lockLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
    }

    List<TestAttemptRow> listAttempts(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            int limit) {
        return jdbc.sql("""
                        select t.uuid, bs.uuid as connection_version_uuid, t.deneme_no,
                               case when t.sonuc = 'BASARILI' then 'PASSED'
                                    when t.hata_kodu = 'ORACLE_TARGET_MISMATCH' then 'TARGET_MISMATCH'
                                    else 'FAILED' end as sonuc_kodu,
                               t.hata_kodu, t.urun_adi as database_product,
                               t.urun_surumu as database_version,
                               t.veritabani_ana_surumu as database_major,
                               t.veritabani_alt_surumu as database_minor,
                               t.surucu_adi as driver_name, t.surucu_surumu as driver_version,
                               t.hedef_kimlik_surumu, t.hedef_parmak_izi,
                               t.baslama_zamani, t.tamamlanma_zamani,
                               t.sure_milisaniye as sure_ms
                          from akis.baglanti_testi t
                          join akis.proje p on p.id = t.proje_id
                          join akis.baglanti_surumu bs
                            on bs.proje_id = t.proje_id and bs.id = t.baglanti_surumu_id
                          join akis.baglanti b
                            on b.proje_id = bs.proje_id and b.id = bs.baglanti_id
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                         order by t.deneme_no desc
                         limit :limit
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .param("connectionVersionUuid", connectionVersionUuid)
                .param("limit", limit)
                .query(this::mapTestAttempt)
                .list();
    }

    private String lifecycleSelect() {
        return """
                select bs.uuid as connection_version_uuid,
                       case bs.durum when 'TASLAK' then 'DRAFT'
                            when 'TEST_EDILDI' then 'TESTED'
                            when 'ETKIN' then 'ACTIVE' else 'DISABLED' end as durum_kodu,
                       bs.versiyon_no as durum_surumu, bs.hedef_kimlik_surumu,
                       bs.hedef_parmak_izi, bs.son_basarili_test_uuid,
                       bs.test_edilme_zamani, bs.etkinlestirilme_zamani as aktiflestirilme_zamani
                  from akis.baglanti_surumu bs
                  join akis.proje p on p.id = bs.proje_id
                  join akis.baglanti b
                    on b.proje_id = bs.proje_id and b.id = bs.baglanti_id
                """;
    }

    private LifecycleRow mapLifecycle(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new LifecycleRow(
                rs.getObject("connection_version_uuid", UUID.class),
                rs.getString("durum_kodu"), rs.getLong("durum_surumu"),
                rs.getObject("hedef_kimlik_surumu", Integer.class),
                rs.getString("hedef_parmak_izi"),
                rs.getObject("son_basarili_test_uuid", UUID.class),
                rs.getObject("test_edilme_zamani", OffsetDateTime.class),
                rs.getObject("aktiflestirilme_zamani", OffsetDateTime.class));
    }

    private TestAttemptRow mapTestAttempt(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new TestAttemptRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("connection_version_uuid", UUID.class),
                rs.getInt("deneme_no"), rs.getString("sonuc_kodu"),
                rs.getString("hata_kodu"), rs.getString("database_product"),
                rs.getString("database_version"),
                rs.getObject("database_major", Integer.class),
                rs.getObject("database_minor", Integer.class),
                rs.getString("driver_name"), rs.getString("driver_version"),
                rs.getObject("hedef_kimlik_surumu", Integer.class),
                rs.getString("hedef_parmak_izi"),
                rs.getObject("baslama_zamani", OffsetDateTime.class),
                rs.getObject("tamamlanma_zamani", OffsetDateTime.class),
                rs.getLong("sure_ms"));
    }

    private String safeErrorCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,99}")) {
            return "ORACLE_TEST_FAILED";
        }
        return value;
    }

    static final class LifecycleNotFoundException extends RuntimeException {
    }

    static final class LifecycleConflictException extends RuntimeException {
    }
}

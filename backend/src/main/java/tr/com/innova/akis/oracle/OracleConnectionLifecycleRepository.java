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
                          from entegrasyon.baglanti_surumu_testi t
                          join entegrasyon.proje p on p.id = t.proje_id
                          join entegrasyon.baglanti_surumu bs
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
                        insert into entegrasyon.baglanti_surumu_testi(
                            proje_id, baglanti_id, baglanti_surumu_id, uuid, deneme_no,
                            sonuc_kodu, hata_kodu, database_product, database_version,
                            database_major, database_minor,
                            driver_name, driver_version, hedef_kimlik_surumu,
                            hedef_parmak_izi, baslama_zamani, tamamlanma_zamani, sure_ms)
                        select p.id, b.id, bs.id, :testUuid, :attemptNumber,
                               :outcome, :errorCode, :databaseProduct, :databaseVersion,
                               :databaseMajorVersion, :databaseMinorVersion,
                               :driverName, :driverVersion, :targetIdentityVersion,
                               :targetFingerprint, :startedAt, :completedAt, :durationMs
                          from entegrasyon.proje p
                          join entegrasyon.baglanti b on b.proje_id = p.id
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = p.id and bs.baglanti_id = b.id
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                        returning uuid, :connectionVersionUuid::uuid as connection_version_uuid,
                                  deneme_no, sonuc_kodu, hata_kodu, database_product,
                                  database_version, database_major,
                                  database_minor, driver_name, driver_version,
                                  hedef_kimlik_surumu, hedef_parmak_izi,
                                  baslama_zamani, tamamlanma_zamani, sure_ms
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
                        insert into entegrasyon.baglanti_surumu_testi(
                            proje_id, baglanti_id, baglanti_surumu_id, uuid, deneme_no,
                            sonuc_kodu, hata_kodu, baslama_zamani, tamamlanma_zamani, sure_ms)
                        select p.id, b.id, bs.id, :testUuid, :attemptNumber,
                               'FAILED', :errorCode, :startedAt, :completedAt, :durationMs
                          from entegrasyon.proje p
                          join entegrasyon.baglanti b on b.proje_id = p.id
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = p.id and bs.baglanti_id = b.id
                         where p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                        returning uuid, :connectionVersionUuid::uuid as connection_version_uuid,
                                  deneme_no, sonuc_kodu, hata_kodu, database_product,
                                  database_version, database_major,
                                  database_minor, driver_name, driver_version,
                                  hedef_kimlik_surumu, hedef_parmak_izi,
                                  baslama_zamani, tamamlanma_zamani, sure_ms
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
                        update entegrasyon.baglanti_surumu_yasam_dongusu yd
                           set durum_kodu = case when yd.durum_kodu = 'DRAFT' then 'TESTED'
                                                else yd.durum_kodu end,
                               durum_surumu = yd.durum_surumu + 1,
                               hedef_kimlik_surumu = coalesce(
                                   yd.hedef_kimlik_surumu, :targetIdentityVersion),
                               hedef_parmak_izi = coalesce(
                                   yd.hedef_parmak_izi, :targetFingerprint),
                               son_basarili_test_uuid = :testUuid,
                               test_edilme_zamani = :testedAt,
                               guncellenme_zamani = current_timestamp
                          from entegrasyon.proje p
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = p.id
                         where yd.proje_id = p.id
                           and yd.baglanti_surumu_id = bs.id
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
        int updated = jdbc.sql("""
                        update entegrasyon.baglanti_surumu_yasam_dongusu yd
                           set durum_kodu = 'ACTIVE',
                               durum_surumu = yd.durum_surumu + 1,
                               aktiflestirilme_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp
                          from entegrasyon.proje p
                          join entegrasyon.baglanti b on b.proje_id = p.id
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = p.id and bs.baglanti_id = b.id
                         where yd.proje_id = p.id
                           and yd.baglanti_id = b.id
                           and yd.baglanti_surumu_id = bs.id
                           and p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and bs.uuid = :connectionVersionUuid
                           and bs.baglanti_modu = 'JDBC'
                           and yd.durum_kodu = 'TESTED'
                           and yd.durum_surumu = :expectedStateVersion
                           and yd.son_basarili_test_uuid = :testUuid
                           and yd.hedef_parmak_izi is not null
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
        jdbc.sql("""
                        update entegrasyon.baglanti b
                           set durum_kodu = case when b.durum_kodu = 'TASLAK' then 'AKTIF'
                                                else b.durum_kodu end,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = b.versiyon_no + 1
                          from entegrasyon.proje p
                         where b.proje_id = p.id
                           and p.uuid = :projectUuid
                           and b.uuid = :connectionUuid
                           and b.durum_kodu = 'TASLAK'
                        """)
                .param("projectUuid", projectUuid)
                .param("connectionUuid", connectionUuid)
                .update();
        return lockLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
    }

    List<TestAttemptRow> listAttempts(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            int limit) {
        return jdbc.sql("""
                        select t.uuid, bs.uuid as connection_version_uuid, t.deneme_no,
                               t.sonuc_kodu, t.hata_kodu, t.database_product,
                               t.database_version, t.database_major,
                               t.database_minor, t.driver_name, t.driver_version,
                               t.hedef_kimlik_surumu, t.hedef_parmak_izi,
                               t.baslama_zamani, t.tamamlanma_zamani, t.sure_ms
                          from entegrasyon.baglanti_surumu_testi t
                          join entegrasyon.proje p on p.id = t.proje_id
                          join entegrasyon.baglanti b
                            on b.proje_id = t.proje_id and b.id = t.baglanti_id
                          join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = t.proje_id and bs.id = t.baglanti_surumu_id
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
                select bs.uuid as connection_version_uuid, yd.durum_kodu,
                       yd.durum_surumu, yd.hedef_kimlik_surumu,
                       yd.hedef_parmak_izi, yd.son_basarili_test_uuid,
                       yd.test_edilme_zamani, yd.aktiflestirilme_zamani
                  from entegrasyon.baglanti_surumu_yasam_dongusu yd
                  join entegrasyon.proje p on p.id = yd.proje_id
                  join entegrasyon.baglanti b
                    on b.proje_id = yd.proje_id and b.id = yd.baglanti_id
                  join entegrasyon.baglanti_surumu bs
                    on bs.proje_id = yd.proje_id and bs.id = yd.baglanti_surumu_id
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

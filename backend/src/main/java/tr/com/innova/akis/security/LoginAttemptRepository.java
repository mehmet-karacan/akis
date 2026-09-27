package tr.com.innova.akis.security;

import java.net.InetAddress;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LoginAttemptRepository {

    private final JdbcClient jdbc;

    public LoginAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LoginUser> lockUser(String userCode) {
        return jdbc.sql(userSelect() + " where k.kullanici_kodu = :userCode for update of k")
                .param("userCode", userCode)
                .query(this::mapUser)
                .optional();
    }

    Optional<LoginUser> lockUser(long userId) {
        return jdbc.sql(userSelect() + " where k.id = :userId for update of k")
                .param("userId", userId)
                .query(this::mapUser)
                .optional();
    }

    Optional<SessionUserState> sessionUser(long userId) {
        return jdbc.sql("""
                select id, durum, parola
                  from akis.kullanici
                 where id = :userId
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new SessionUserState(
                        rs.getLong("id"), rs.getString("durum"), rs.getString("parola")))
                .optional();
    }

    void changePassword(long userId, String passwordHash, OffsetDateTime now) {
        jdbc.sql("""
                update akis.kullanici
                   set parola = :passwordHash,
                       parola_degistirilme_zamani = :now,
                       hatali_giris_sayisi = 0,
                       guncellenme_zamani = :now,
                       guncelleyen_kullanici_id = :userId,
                       versiyon_no = versiyon_no + 1
                 where id = :userId and durum = 'AKTIF'
                """)
                .param("passwordHash", passwordHash)
                .param("now", now)
                .param("userId", userId)
                .update();
        jdbc.sql("delete from akis.kullanici_guvenlik_durumu where kullanici_id = :userId")
                .param("userId", userId)
                .update();
    }

    int registerIpAttempt(InetAddress address, OffsetDateTime now, Duration window) {
        return jdbc.sql("""
                insert into akis.giris_ip_durumu(ip_adresi, pencere_baslangici, deneme_sayisi)
                values (:address, :now, 1)
                on conflict (ip_adresi) do update
                   set pencere_baslangici = case
                           when akis.giris_ip_durumu.pencere_baslangici < :windowStart then :now
                           else akis.giris_ip_durumu.pencere_baslangici end,
                       deneme_sayisi = case
                           when akis.giris_ip_durumu.pencere_baslangici < :windowStart then 1
                           else akis.giris_ip_durumu.deneme_sayisi + 1 end,
                       versiyon_no = akis.giris_ip_durumu.versiyon_no + 1
                returning deneme_sayisi
                """)
                .param("address", address.getHostAddress(), Types.OTHER)
                .param("now", now)
                .param("windowStart", now.minus(window))
                .query(Integer.class)
                .single();
    }

    void recordFailure(long userId, OffsetDateTime now, Duration window, Duration lockDuration, int limit) {
        Integer failures = jdbc.sql("""
                insert into akis.kullanici_guvenlik_durumu(
                    kullanici_id, hata_penceresi_baslangici, versiyon_no)
                values (:userId, :now, 1)
                on conflict (kullanici_id) do update
                   set hata_penceresi_baslangici = case
                           when akis.kullanici_guvenlik_durumu.hata_penceresi_baslangici is null
                             or akis.kullanici_guvenlik_durumu.hata_penceresi_baslangici < :windowStart
                           then :now else akis.kullanici_guvenlik_durumu.hata_penceresi_baslangici end,
                       versiyon_no = akis.kullanici_guvenlik_durumu.versiyon_no + 1
                returning case
                    when hata_penceresi_baslangici = :now then 1
                    else (select hatali_giris_sayisi + 1 from akis.kullanici where id = :userId)
                end
                """)
                .param("userId", userId)
                .param("now", now)
                .param("windowStart", now.minus(window))
                .query(Integer.class)
                .single();
        jdbc.sql("""
                update akis.kullanici
                   set hatali_giris_sayisi = :failures,
                       guncellenme_zamani = :now,
                       versiyon_no = versiyon_no + 1
                 where id = :userId
                """)
                .param("failures", failures)
                .param("now", now)
                .param("userId", userId)
                .update();
        if (failures >= limit) {
            jdbc.sql("""
                    update akis.kullanici_guvenlik_durumu
                       set kilit_bitis_zamani = :lockedUntil,
                           versiyon_no = versiyon_no + 1
                     where kullanici_id = :userId
                    """)
                    .param("lockedUntil", now.plus(lockDuration))
                    .param("userId", userId)
                    .update();
        }
    }

    void recordSuccess(long userId, InetAddress address, OffsetDateTime now) {
        jdbc.sql("""
                update akis.kullanici
                   set hatali_giris_sayisi = 0,
                       son_giris_zamani = :now,
                       son_giris_ip_adresi = :address,
                       guncellenme_zamani = :now,
                       versiyon_no = versiyon_no + 1
                 where id = :userId
                """)
                .param("now", now)
                .param("address", address.getHostAddress(), Types.OTHER)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                delete from akis.kullanici_guvenlik_durumu where kullanici_id = :userId
                """)
                .param("userId", userId)
                .update();
    }

    record LoginUser(
            long id,
            UUID uuid,
            String userCode,
            String displayName,
            String passwordHash,
            String status,
            OffsetDateTime lockedUntil) {
    }

    record SessionUserState(long id, String status, String passwordHash) {
    }

    private String userSelect() {
        return """
                select k.id, k.uuid, k.kullanici_kodu, k.gorunen_ad, k.parola, k.durum,
                       g.kilit_bitis_zamani
                  from akis.kullanici k
                  left join akis.kullanici_guvenlik_durumu g on g.kullanici_id = k.id
                """;
    }

    private LoginUser mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LoginUser(
                rs.getLong("id"),
                rs.getObject("uuid", UUID.class),
                rs.getString("kullanici_kodu"),
                rs.getString("gorunen_ad"),
                rs.getString("parola"),
                rs.getString("durum"),
                rs.getObject("kilit_bitis_zamani", OffsetDateTime.class));
    }
}

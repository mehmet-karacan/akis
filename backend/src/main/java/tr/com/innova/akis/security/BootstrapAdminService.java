package tr.com.innova.akis.security;

import java.io.Console;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapAdminService {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final PasswordPolicy passwordPolicy;

    BootstrapAdminService(
            JdbcClient jdbc,
            PasswordEncoder passwords,
            PasswordPolicy passwordPolicy) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.passwordPolicy = passwordPolicy;
    }

    public long runInteractive(Console console) {
        if (console == null) {
            throw new IllegalStateException(
                    "Yönetici bootstrap komutu etkileşimli bir terminalde çalıştırılmalıdır.");
        }
        String userCode = required(console.readLine("Kullanıcı kodu: "), "Kullanıcı kodu");
        String firstName = required(console.readLine("Ad: "), "Ad");
        String lastName = optional(console.readLine("Soyad (isteğe bağlı): "));
        String employeeNumber = optional(console.readLine("Sicil numarası (isteğe bağlı): "));
        String email = optional(console.readLine("E-posta (isteğe bağlı): "));
        char[] first = console.readPassword("Parola: ");
        char[] second = console.readPassword("Parola tekrar: ");
        try {
            if (!Arrays.equals(first, second)) {
                throw new IllegalArgumentException("Parolalar eşleşmiyor.");
            }
            String password = new String(first);
            passwordPolicy.validate(password);
            return bootstrap(
                    userCode, firstName, lastName, employeeNumber, email,
                    passwords.encode(password));
        }
        finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }

    @Transactional
    public long bootstrap(
            String userCode,
            String firstName,
            String lastName,
            String employeeNumber,
            String email,
            String passwordHash) {
        String normalizedUserCode = UserCodeNormalizer.normalize(userCode);
        jdbc.sql("select pg_advisory_xact_lock(hashtext('akis.bootstrap.admin'))")
                .query((rs, rowNum) -> 1)
                .single();

        long initializedAdmins = jdbc.sql("""
                select count(*)
                  from akis.kullanici_rol kr
                  join akis.rol r on r.id = kr.rol_id
                  join akis.kullanici k on k.id = kr.kullanici_id
                 where r.kapsam = 'SISTEM'
                   and r.kod = 'SISTEM_YONETICISI'
                   and kr.rol_kapsami = 'SISTEM'
                   and kr.proje_id is null
                   and kr.iptal_zamani is null
                   and k.durum = 'AKTIF'
                   and k.parola is not null
                """).query(Long.class).single();
        if (initializedAdmins > 0) {
            throw new IllegalStateException("Etkin sistem yöneticisi zaten oluşturulmuş.");
        }

        List<Long> candidates = jdbc.sql("""
                select id
                  from akis.kullanici
                 where kullanici_kodu = :userCode
                    or (:email is not null and lower(eposta) = lower(:email))
                 order by id
                 for update
                """)
                .param("userCode", normalizedUserCode)
                .param("email", optional(email), Types.VARCHAR)
                .query(Long.class)
                .list();
        if (candidates.size() > 1) {
            throw new IllegalStateException(
                    "Kullanıcı kodu ve e-posta farklı kullanıcı kayıtlarıyla eşleşiyor.");
        }

        long userId = candidates.isEmpty()
                ? createUser(normalizedUserCode, firstName, lastName, employeeNumber, email)
                : candidates.getFirst();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                update akis.kullanici
                   set kullanici_kodu = :userCode,
                       ad = :firstName,
                       soyad = :lastName,
                       sicil_numarasi = :employeeNumber,
                       gorunen_ad = concat_ws(' ', :firstName, :lastName),
                       eposta = :email,
                       parola = :passwordHash,
                       parola_degistirilme_zamani = :now,
                       durum = 'AKTIF',
                       devre_disi_birakilma_zamani = null,
                       hatali_giris_sayisi = 0,
                       guncellenme_zamani = :now,
                       guncelleyen_kullanici_id = :userId,
                       versiyon_no = versiyon_no + 1
                 where id = :userId
                """)
                .param("userCode", normalizedUserCode)
                .param("firstName", required(firstName, "Ad"))
                .param("lastName", optional(lastName), Types.VARCHAR)
                .param("employeeNumber", optional(employeeNumber), Types.VARCHAR)
                .param("email", optional(email), Types.VARCHAR)
                .param("passwordHash", passwordHash)
                .param("now", now)
                .param("userId", userId)
                .update();
        jdbc.sql("delete from akis.kullanici_guvenlik_durumu where kullanici_id = :userId")
                .param("userId", userId)
                .update();

        long roleId = jdbc.sql("""
                select id from akis.rol
                 where kapsam = 'SISTEM' and kod = 'SISTEM_YONETICISI' and etkin_mi
                """).query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException(
                        "SISTEM_YONETICISI rolü bulunamadı veya etkin değil."));
        jdbc.sql("""
                insert into akis.kullanici_rol(
                    kullanici_id, rol_id, rol_kapsami,
                    atayan_kullanici_id, olusturan_kullanici_id)
                values (:userId, :roleId, 'SISTEM', :userId, :userId)
                on conflict do nothing
                """)
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
        return userId;
    }

    private long createUser(
            String userCode,
            String firstName,
            String lastName,
            String employeeNumber,
            String email) {
        long id = jdbc.sql("""
                insert into akis.kullanici(
                    kullanici_kodu, ad, soyad, sicil_numarasi,
                    gorunen_ad, eposta, durum)
                values (
                    :userCode, :firstName, :lastName, :employeeNumber,
                    concat_ws(' ', :firstName, :lastName), :email, 'PAROLA_BEKLIYOR')
                returning id
                """)
                .param("userCode", required(userCode, "Kullanıcı kodu"))
                .param("firstName", required(firstName, "Ad"))
                .param("lastName", optional(lastName), Types.VARCHAR)
                .param("employeeNumber", optional(employeeNumber), Types.VARCHAR)
                .param("email", optional(email), Types.VARCHAR)
                .query(Long.class)
                .single();
        jdbc.sql("update akis.kullanici set olusturan_kullanici_id = :id where id = :id")
                .param("id", id)
                .update();
        return id;
    }

    private static String required(String value, String label) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label + " zorunludur.");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

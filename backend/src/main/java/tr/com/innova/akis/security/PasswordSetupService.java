package tr.com.innova.akis.security;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.metadata.ApiException;

@Service
public class PasswordSetupService {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final Clock clock;

    @Autowired
    public PasswordSetupService(
            JdbcClient jdbc,
            PasswordEncoder passwords,
            PasswordPolicy policy) {
        this(jdbc, passwords, policy, Clock.systemUTC());
    }

    PasswordSetupService(
            JdbcClient jdbc,
            PasswordEncoder passwords,
            PasswordPolicy policy,
            Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public IssuedToken issue(UUID userUuid, long actorUserId) {
        long userId = jdbc.sql("select id from akis.kullanici where uuid = :uuid for update")
                .param("uuid", userUuid)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "IDENTITY_NOT_FOUND",
                        "Kullanıcı bulunamadı."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                update akis.parola_kurulum_tokeni
                   set kullanilma_zamani = :now
                 where kullanici_id = :userId
                   and kullanilma_zamani is null
                """)
                .param("now", now)
                .param("userId", userId)
                .update();

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        OffsetDateTime expiresAt = now.plus(TOKEN_LIFETIME);
        jdbc.sql("""
                insert into akis.parola_kurulum_tokeni(
                    kullanici_id, token_ozeti, gecerlilik_sonu,
                    olusturulma_zamani, olusturan_kullanici_id)
                values (:userId, :digest, :expiresAt, :now, :actorUserId)
                """)
                .param("userId", userId)
                .param("digest", digest(token))
                .param("expiresAt", expiresAt)
                .param("now", now)
                .param("actorUserId", actorUserId)
                .update();
        return new IssuedToken(token, expiresAt);
    }

    @Transactional
    public void consume(String token, String newPassword) {
        policy.validate(newPassword);
        if (token == null || token.length() > 128) {
            throw invalidToken();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        var row = jdbc.sql("""
                select pkt.id, pkt.kullanici_id, k.kullanici_kodu
                  from akis.parola_kurulum_tokeni pkt
                  join akis.kullanici k on k.id = pkt.kullanici_id
                 where pkt.token_ozeti = :digest
                   and pkt.kullanilma_zamani is null
                   and pkt.gecerlilik_sonu > :now
                   and k.durum <> 'PASIF'
                 for update of pkt, k
                """)
                .param("digest", digest(token))
                .param("now", now)
                .query((rs, rowNum) -> new TokenOwner(
                        rs.getLong("id"),
                        rs.getLong("kullanici_id"),
                        rs.getString("kullanici_kodu")))
                .optional()
                .orElseThrow(this::invalidToken);

        jdbc.sql("""
                update akis.kullanici
                   set parola = :passwordHash,
                       parola_degistirilme_zamani = :now,
                       durum = 'AKTIF',
                       hatali_giris_sayisi = 0,
                       guncellenme_zamani = :now,
                       guncelleyen_kullanici_id = :userId,
                       versiyon_no = versiyon_no + 1
                 where id = :userId
                """)
                .param("passwordHash", passwords.encode(newPassword))
                .param("now", now)
                .param("userId", row.userId())
                .update();
        jdbc.sql("update akis.parola_kurulum_tokeni set kullanilma_zamani = :now where id = :id")
                .param("now", now)
                .param("id", row.tokenId())
                .update();
        jdbc.sql("delete from akis.kullanici_guvenlik_durumu where kullanici_id = :userId")
                .param("userId", row.userId())
                .update();
        jdbc.sql("delete from akis.spring_session where principal_name = :principalName")
                .param("principalName", row.userCode())
                .update();
    }

    private String digest(String token) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 kullanılamıyor.", impossible);
        }
    }

    private ApiException invalidToken() {
        return new ApiException(
                UNAUTHORIZED,
                "PASSWORD_SETUP_TOKEN_INVALID",
                "Parola kurulum bağlantısı geçersiz veya süresi dolmuş.");
    }

    public record IssuedToken(String token, OffsetDateTime expiresAt) {
    }

    private record TokenOwner(long tokenId, long userId, String userCode) {
    }
}

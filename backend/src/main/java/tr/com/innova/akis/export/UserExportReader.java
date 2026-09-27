package tr.com.innova.akis.export;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only, credential-free projection of application users. */
@Component
class UserExportReader {

    private final JdbcClient jdbc;

    UserExportReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record UserRef(
            UUID uuid,
            String userCode,
            String firstName,
            String lastName,
            String employeeNumber,
            String email,
            String status,
            OffsetDateTime createdAt) {
    }

    void forEach(Consumer<UserRef> consumer) {
        try (var rows = jdbc.sql("""
                select k.uuid, k.kullanici_kodu, k.ad, k.soyad,
                       k.sicil_numarasi, k.eposta, k.durum,
                       k.olusturulma_zamani
                  from akis.kullanici k
                 order by k.gorunen_ad, k.uuid
                """).query((rs, rowNum) -> new UserRef(
                rs.getObject("uuid", UUID.class),
                rs.getString("kullanici_kodu"),
                rs.getString("ad"),
                rs.getString("soyad"),
                rs.getString("sicil_numarasi"),
                rs.getString("eposta"),
                rs.getString("durum"),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class))).stream()) {
            rows.forEach(consumer);
        }
    }
}

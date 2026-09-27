package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tr.com.innova.akis.metadata.ApiException;

class PasswordSetupServiceIT {

    @Test
    void tokenIsSingleUseAndActivatesTheSameUserRecord() {
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(
                required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD")));
        var passwords = new PasswordEncodingConfiguration().applicationPasswordEncoder();
        Instant instant = Instant.parse("2026-09-26T12:00:00Z");
        var service = new PasswordSetupService(
                jdbc, passwords, new PasswordPolicy(), Clock.fixed(instant, ZoneOffset.UTC));

        long actorId = jdbc.sql("""
                insert into akis.kullanici(
                    uuid, gorunen_ad, kullanici_kodu, ad, parola,
                    parola_degistirilme_zamani, durum)
                values (:uuid, 'Admin', 'admin', 'Admin', :password, :changedAt, 'AKTIF')
                returning id
                """)
                .param("uuid", UUID.randomUUID())
                .param("password", passwords.encode("yonetici icin guvenli parola"))
                .param("changedAt", java.time.OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .query(Long.class)
                .single();
        UUID userUuid = UUID.randomUUID();
        long originalUserId = jdbc.sql("""
                insert into akis.kullanici(
                    uuid, gorunen_ad, kullanici_kodu, ad, durum,
                    olusturulma_zamani, olusturan_kullanici_id)
                values (:uuid, 'Yeni Kullanıcı', 'yeni.kullanici', 'Yeni',
                        'PAROLA_BEKLIYOR', :createdAt, :actorId)
                returning id
                """)
                .param("uuid", userUuid)
                .param("createdAt", java.time.OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .param("actorId", actorId)
                .query(Long.class)
                .single();

        var token = service.issue(userUuid, actorId);
        service.consume(token.token(), "kullanici icin guvenli parola");

        var state = jdbc.sql("""
                select id, durum, parola from akis.kullanici where uuid = :uuid
                """)
                .param("uuid", userUuid)
                .query((rs, rowNum) -> new Object[] {
                        rs.getLong("id"), rs.getString("durum"), rs.getString("parola") })
                .single();
        assertEquals(originalUserId, state[0]);
        assertEquals("AKTIF", state[1]);
        assertTrue(passwords.matches("kullanici icin guvenli parola", (String) state[2]));
        assertThrows(ApiException.class,
                () -> service.consume(token.token(), "baska ama guvenli bir parola"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " required");
        }
        return value;
    }
}

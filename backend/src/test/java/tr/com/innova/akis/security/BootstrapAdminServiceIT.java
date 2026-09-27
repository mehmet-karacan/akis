package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class BootstrapAdminServiceIT {

    private static JdbcClient jdbc;
    private static PasswordEncoder passwords;
    private static BootstrapAdminService service;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void connect() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_bootstrap_test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Generated bootstrap test database required.");
        }
        var dataSource = new DriverManagerDataSource(
                url, required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        passwords = new PasswordEncodingConfiguration().applicationPasswordEncoder();
        service = new BootstrapAdminService(jdbc, passwords, new PasswordPolicy());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void bootstrapsExactlyOneLocalSystemAdministrator() {
        String userCode = "admin-" + UUID.randomUUID();
        String hash = passwords.encode("uzun ve benzersiz bootstrap parolasi");
        long userId = transactions.execute(status -> service.bootstrap(
                userCode, "Mehmet", "Karacan", "10001",
                userCode + "@example.test", hash));

        var row = jdbc.sql("""
                select durum, parola, kullanici_kodu
                  from akis.kullanici where id = :id
                """)
                .param("id", userId)
                .query((rs, rowNum) -> new String[] {
                        rs.getString("durum"), rs.getString("parola"),
                        rs.getString("kullanici_kodu")})
                .single();
        assertEquals("AKTIF", row[0]);
        assertEquals(userCode, row[2]);
        assertTrue(passwords.matches("uzun ve benzersiz bootstrap parolasi", row[1]));
        assertEquals(1L, jdbc.sql("""
                select count(*)
                  from akis.kullanici_rol kr
                  join akis.rol r on r.id = kr.rol_id
                 where kr.kullanici_id = :userId
                   and r.kod = 'SISTEM_YONETICISI'
                   and kr.rol_kapsami = 'SISTEM'
                   and kr.proje_id is null
                   and kr.iptal_zamani is null
                """).param("userId", userId).query(Long.class).single());

        assertThrows(IllegalStateException.class, () -> transactions.execute(status ->
                service.bootstrap(
                        "ikinci-admin", "İkinci", "Yönetici", null,
                        null, passwords.encode("baska uzun bootstrap parolasi"))));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " required");
        }
        return value;
    }
}

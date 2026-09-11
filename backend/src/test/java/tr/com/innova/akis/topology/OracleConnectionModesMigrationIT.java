package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Types;
import java.util.Locale;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OracleConnectionModesMigrationIT {

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private long projectId;
    private long connectionId;

    @BeforeAll
    static void startDatabaseContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        Flyway.configure().dataSource(context.getBean(DataSource.class))
                .locations("classpath:db/migration").load().migrate();
        jdbc = context.getBean(JdbcClient.class);
    }

    @AfterAll
    static void stopDatabaseContext() {
        if (context != null) context.close();
    }

    @BeforeEach
    void resetProject() {
        jdbc.sql("truncate table entegrasyon.proje restart identity cascade").update();
        projectId = jdbc.sql("insert into entegrasyon.proje(kod, ad) values ('ORACLE_V2_IT', 'Oracle V2 IT') returning id")
                .query(Long.class).single();
        connectionId = jdbc.sql("insert into entegrasyon.baglanti(proje_id, kod, veritabani_turu, ad) values (:projectId, 'ORACLE_IT', 'ORACLE', 'Oracle IT') returning id")
                .param("projectId", projectId).query(Long.class).single();
    }

    @Test
    void preservesLegacyJdbcDefaultAndEnforcesOracleShape() {
        long versionId = insertJdbc("oracle.jdbc.OracleDriver", "ORCL", null);
        assertEquals("JDBC", jdbc.sql("select baglanti_modu from entegrasyon.baglanti_surumu where id = :id")
                .param("id", versionId).query(String.class).single());
        assertThrows(DataAccessException.class, () -> insertJdbc("evil.Driver", "ORCL", null));
        assertThrows(DataAccessException.class, () -> insertJdbc("oracle.jdbc.OracleDriver", "ORCL", "ORCLSID"));
    }

    @Test
    void acceptsOnlyLocalSecretlessJndiAndKeepsVersionsImmutable() {
        long versionId = jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, baglanti_modu, jndi_adi,
                            surucu_referansi, sunucu_adi, port)
                        values (:projectId, :connectionId, 1, 'JNDI',
                            'java:comp/env/jdbc/OracleMain', null, null, null)
                        returning id
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .query(Long.class).single();

        assertThrows(DataAccessException.class, () -> jdbc.sql("update entegrasyon.baglanti_surumu set jndi_adi = 'java:comp/env/jdbc/Other' where id = :id")
                .param("id", versionId).update());
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, baglanti_modu, jndi_adi,
                            surucu_referansi, sunucu_adi, port)
                        values (:projectId, :connectionId, 2, 'JNDI',
                            'ldap://remote.example/DataSource', null, null, null)
                        """).param("projectId", projectId).param("connectionId", connectionId).update());

        long secretId = jdbc.sql("""
                        insert into entegrasyon.secret_referansi(
                            proje_id, kod, referans_yolu, saglayici_kodu, ad)
                        values (:projectId, 'JNDI_SECRET', 'JNDI_SECRET_ENV', 'ENV', 'JNDI secret')
                        returning id
                        """).param("projectId", projectId).query(Long.class).single();
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        insert into entegrasyon.baglanti_secret_bagi(
                            proje_id, baglanti_surumu_id, secret_referansi_id, rol_kodu)
                        values (:projectId, :versionId, :secretId, 'KIMLIK')
                        """).param("projectId", projectId).param("versionId", versionId)
                .param("secretId", secretId).update());
    }

    private long insertJdbc(String driver, String service, String sid) {
        int version = jdbc.sql("select coalesce(max(surum_no), 0) + 1 from entegrasyon.baglanti_surumu where baglanti_id = :id")
                .param("id", connectionId).query(Integer.class).single();
        return jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, surucu_referansi,
                            sunucu_adi, servis_adi, sid, port)
                        values (:projectId, :connectionId, :version, :driver,
                            'db.example', :service, :sid, 1521)
                        returning id
                        """).param("projectId", projectId).param("connectionId", connectionId)
                .param("version", version).param("driver", driver)
                .param("service", service, Types.VARCHAR).param("sid", sid, Types.VARCHAR)
                .query(Long.class).single();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(isolatedUrl(required("SPRING_DATASOURCE_URL")));
            dataSource.setUsername(required("SPRING_DATASOURCE_USERNAME"));
            dataSource.setPassword(required("SPRING_DATASOURCE_PASSWORD"));
            return dataSource;
        }
        @Bean JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
        private static String isolatedUrl(String url) {
            String database = url.split("\\?", 2)[0];
            database = database.substring(database.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (!database.endsWith("_it") && !database.endsWith("_test")) throw new IllegalStateException("Oracle V2 tests require an isolated database.");
            return url;
        }
        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
            return value;
        }
    }
}

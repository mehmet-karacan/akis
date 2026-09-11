package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Proves V015 production-shaped references survive the V016/V017 upgrade. */
class OracleConnectionModesUpgradeIT {

    private static final UUID SKY_VERSION_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000001601");
    private static final UUID GPU_VERSION_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000001602");
    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static Flyway latest;

    @BeforeAll
    static void migrateProductionFixture() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        DataSource dataSource = context.getBean(DataSource.class);
        Flyway toV15 = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("15")
                .cleanDisabled(false).load();
        toV15.clean();
        toV15.migrate();
        jdbc = context.getBean(JdbcClient.class);
        seedV15Fixture();
        latest = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load();
        latest.migrate();
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void preservesConnectionIdentityAndEveryPinnedReference() {
        assertEquals(SKY_VERSION_UUID, jdbc.sql(
                        "select uuid from entegrasyon.baglanti_surumu where id = 1601")
                .query(UUID.class).single());
        assertEquals(GPU_VERSION_UUID, jdbc.sql(
                        "select uuid from entegrasyon.baglanti_surumu where id = 1602")
                .query(UUID.class).single());
        assertEquals(2, jdbc.sql("""
                        select count(*) from entegrasyon.baglanti_surumu
                         where id in (1601, 1602) and baglanti_modu = 'JDBC'
                        """).query(Integer.class).single());
        assertEquals(1, countReference("baglanti_secret_bagi"));
        assertEquals(1, countReference("ortam_sema_eslemesi"));
        assertEquals(1, countReference("sema_goruntusu"));
        assertEquals(1, countReference("yayin_veri_bagi"));
    }

    private static int countReference(String table) {
        return jdbc.sql("select count(*) from entegrasyon." + table
                        + " where baglanti_surumu_id = 1601")
                .query(Integer.class).single();
    }

    private static void seedV15Fixture() {
        jdbc.sql("insert into entegrasyon.proje(id, kod, ad) values (1600, 'UPGRADE_IT', 'Upgrade IT')").update();
        jdbc.sql("""
                insert into entegrasyon.secret_referansi(id, proje_id, kod, referans_yolu, saglayici_kodu, ad)
                values (1600, 1600, 'SKY_SECRET', 'AKIS_ORACLE_SOURCE_CREDENTIAL', 'ENV', 'SKY secret')
                """).update();
        jdbc.sql("""
                insert into entegrasyon.baglanti(id, proje_id, kod, veritabani_turu, durum_kodu, ad)
                values (1601, 1600, 'SKY', 'ORACLE', 'AKTIF', 'SKY'),
                       (1602, 1600, 'GPU', 'ORACLE', 'AKTIF', 'GPU')
                """).update();
        jdbc.sql("""
                insert into entegrasyon.baglanti_surumu(
                    id, proje_id, baglanti_id, surum_no, surucu_referansi, sunucu_adi,
                    servis_adi, sid, tls_modu, port, uuid)
                values (1601, 1600, 1601, 1, 'oracle.jdbc.OracleDriver', 'sky.invalid', null,
                        'TTBP2', 'DISABLED', 1907, :skyUuid),
                       (1602, 1600, 1602, 1, 'oracle.jdbc.OracleDriver', 'gpu.invalid',
                        'CT_GPU_TESTDB', null, 'DISABLED', 1521, :gpuUuid)
                """).param("skyUuid", SKY_VERSION_UUID).param("gpuUuid", GPU_VERSION_UUID).update();
        jdbc.sql("""
                insert into entegrasyon.baglanti_secret_bagi(
                    id, proje_id, baglanti_surumu_id, secret_referansi_id, rol_kodu)
                values (1600, 1600, 1601, 1600, 'KIMLIK')
                """).update();
        jdbc.sql("""
                insert into entegrasyon.fiziksel_sema(id, proje_id, baglanti_id, kod, sema_referansi, ad)
                values (1600, 1600, 1601, 'SKY_SCHEMA', 'TTBP', 'SKY schema');
                insert into entegrasyon.mantiksal_sema(id, proje_id, kod, ad)
                values (1600, 1600, 'HAKEDIS', 'Hakediş');
                insert into entegrasyon.ortam(id, proje_id, kod, ad)
                values (1600, 1600, 'TEST', 'Test');
                insert into entegrasyon.ortam_sema_eslemesi(
                    id, proje_id, mantiksal_sema_id, ortam_id, fiziksel_sema_id, baglanti_surumu_id)
                values (1600, 1600, 1600, 1600, 1600, 1601)
                """).update();
        jdbc.sql("""
                insert into entegrasyon.model(id, proje_id, mantiksal_sema_id, kod, ad)
                values (1600, 1600, 1600, 'HAKEDIS_MODEL', 'Hakediş model');
                insert into entegrasyon.veri_nesnesi(
                    id, proje_id, model_id, kod, nesne_referansi, tur_kodu, ad)
                values (1600, 1600, 1600, 'HAKEDIS_TIPI', 'TTBP.HAKEDIS_TIPI', 'TABLO', 'Hakediş tipi');
                insert into entegrasyon.sema_goruntusu(
                    id, proje_id, veri_nesnesi_id, fiziksel_sema_id, baglanti_surumu_id,
                    parmak_izi, motor_surumu, kesif_zamani)
                values (1600, 1600, 1600, 1600, 1601, :hash, 'Oracle 19c', current_timestamp)
                """).param("hash", "a".repeat(64)).update();
        jdbc.sql("""
                insert into entegrasyon.klasor(id, proje_id, kod, tur_kodu, ad)
                values (1600, 1600, 'ETL', 'GELISTIRME', 'ETL');
                insert into entegrasyon.tanim(id, proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, durum_kodu, ad)
                values (1600, 1600, 1600, 'PROJE', 'MAPPING', 'COPY_HAKEDIS', 'AKTIF', 'Copy hakediş');
                insert into entegrasyon.tanim_surumu(id, tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
                values (1600, 1600, 1, 1, :hash, '{}'::jsonb);
                insert into entegrasyon.dogrulama(id, tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
                values (1600, 1600, 1600, :hash, 'GECTI', '{}'::jsonb);
                insert into entegrasyon.senaryo(id, tanim_surumu_id, dogrulama_id, surum_no, plan_surumu, plan_ozeti, plan)
                values (1600, 1600, 1600, 1, 1, :hash, '{}'::jsonb)
                """).param("hash", "b".repeat(64)).update();
        jdbc.sql("""
                insert into entegrasyon.yayin(
                    id, proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
                    bagimlilik_ozeti, fiziksel_manifesto)
                values (1600, 1600, 1600, 1600, 1, 'ONAY_BEKLIYOR', :hash,
                        cast(:manifest as jsonb));
                insert into entegrasyon.tanim_veri_nesnesi(
                    id, proje_id, tanim_surumu_id, veri_nesnesi_id, sema_goruntusu_id, dugum_kodu, rol_kodu)
                values (1600, 1600, 1600, 1600, 1600, 'SOURCE', 'KAYNAK');
                insert into entegrasyon.yayin_veri_bagi(
                    id, proje_id, yayin_id, tanim_veri_nesnesi_id, ortam_sema_eslemesi_id,
                    fiziksel_sema_id, baglanti_surumu_id, sema_goruntusu_id, fiziksel_kimlik, bag_versiyon_no)
                values (1600, 1600, 1600, 1600, 1600, 1600, 1601, 1600, 'TTBP.HAKEDIS_TIPI', 1)
                """).param("hash", "c".repeat(64))
                .param("manifest", "{\"releaseHash\":\"" + "d".repeat(64) + "\"}").update();
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
            if (!database.endsWith("_it") && !database.endsWith("_test")) {
                throw new IllegalStateException("Oracle V2 tests require an isolated database.");
            }
            return url;
        }
        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
            return value;
        }
    }
}

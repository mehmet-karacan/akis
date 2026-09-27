package tr.com.innova.akis.export;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only, credential-safe projection of the global connection catalog. */
@Component
class ConnectionExportReader {

    private final JdbcClient jdbc;

    ConnectionExportReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record ConnectionRef(
            UUID uuid,
            String code,
            String name,
            String description,
            String databaseType,
            String mode,
            String host,
            Integer port,
            String serviceName,
            String sid,
            String databaseName,
            String username,
            boolean passwordConfigured,
            OffsetDateTime lastTestedAt,
            Boolean lastTestPassed,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int physicalSchemaCount,
            int logicalSchemaCount) {
    }

    void forEach(java.util.function.Consumer<ConnectionRef> consumer) {
        try (var rows = jdbc.sql("""
                select b.uuid, b.kod, b.ad, b.aciklama, b.saglayici_turu, b.baglanti_modu,
                       b.sunucu_adi, b.port, b.servis_adi, b.sid, b.veritabani_adi,
                       b.kullanici_adi, (b.sifre is not null) as sifre_var,
                       b.son_test_zamani, b.son_test_basarili_mi, b.durum,
                       (select k.gorunen_ad from akis.kullanici k
                          where k.id = b.olusturan_kullanici_id) as created_by,
                       b.olusturulma_zamani, b.guncellenme_zamani,
                       (select count(*) from akis.fiziksel_sema f
                          where f.baglanti_id = b.id) as physical_schema_count,
                       (select count(distinct se.mantiksal_sema_id)
                          from akis.sema_eslemesi se
                          join akis.fiziksel_sema f on f.id = se.fiziksel_sema_id
                         where f.baglanti_id = b.id) as logical_schema_count
                  from akis.baglanti b
                 order by b.ad, b.kod
                """).query((rs, rowNum) -> new ConnectionRef(
                        rs.getObject("uuid", UUID.class),
                        rs.getString("kod"), rs.getString("ad"), rs.getString("aciklama"),
                        rs.getString("saglayici_turu"), rs.getString("baglanti_modu"),
                        rs.getString("sunucu_adi"), rs.getObject("port", Integer.class),
                        rs.getString("servis_adi"), rs.getString("sid"), rs.getString("veritabani_adi"),
                        rs.getString("kullanici_adi"), rs.getBoolean("sifre_var"),
                        rs.getObject("son_test_zamani", OffsetDateTime.class),
                        rs.getObject("son_test_basarili_mi", Boolean.class), rs.getString("durum"),
                        rs.getString("created_by"), rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                        rs.getObject("guncellenme_zamani", OffsetDateTime.class),
                        rs.getInt("physical_schema_count"), rs.getInt("logical_schema_count"))).stream()) {
            rows.forEach(consumer);
        }
    }
}

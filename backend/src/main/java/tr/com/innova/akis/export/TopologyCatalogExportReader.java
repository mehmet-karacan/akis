package tr.com.innova.akis.export;

import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only, credential-safe projections for the global topology catalogs. */
@Component
class TopologyCatalogExportReader {

    private final JdbcClient jdbc;

    TopologyCatalogExportReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record EnvironmentRef(UUID uuid, String code, String name, String description,
            String risk, boolean defaultEnvironment, int policyVersion, String status,
            int mappingCount) { }

    record LogicalSchemaRef(UUID uuid, String code, String name, String description,
            String databaseType, String status, int mappingCount, int modelCount) { }

    record PhysicalSchemaRef(UUID uuid, UUID connectionUuid, String connectionCode,
            String code, String name, String description, String databaseType,
            String catalogName, String schemaName, String workCatalogName,
            String workSchemaName, boolean defaultSchema, String loadingPrefix,
            String integrationPrefix, String errorPrefix, String tempPrefix,
            String objectPattern, String remoteObjectPattern, String sequencePattern,
            String status) { }

    void forEachEnvironment(Consumer<EnvironmentRef> consumer) {
        try (var rows = jdbc.sql("""
                select o.uuid, o.kod, o.ad, o.aciklama, o.risk, o.varsayilan_mi,
                       o.politika_sema_surumu, o.durum,
                       (select count(*) from akis.sema_eslemesi se where se.ortam_id = o.id) as mapping_count
                  from akis.ortam o order by o.ad, o.kod
                """).query((rs, n) -> new EnvironmentRef(rs.getObject("uuid", UUID.class),
                rs.getString("kod"), rs.getString("ad"), rs.getString("aciklama"),
                rs.getString("risk"), rs.getBoolean("varsayilan_mi"),
                rs.getInt("politika_sema_surumu"), rs.getString("durum"),
                rs.getInt("mapping_count"))).stream()) {
            rows.forEach(consumer);
        }
    }

    void forEachLogicalSchema(Consumer<LogicalSchemaRef> consumer) {
        try (var rows = jdbc.sql("""
                select l.uuid, l.kod, l.ad, l.aciklama, l.saglayici_turu, l.durum,
                       (select count(*) from akis.sema_eslemesi se where se.mantiksal_sema_id = l.id) as mapping_count,
                       (select count(*) from akis.model m where m.mantiksal_sema_id = l.id and m.arsivlenme_zamani is null) as model_count
                  from akis.mantiksal_sema l order by l.ad, l.kod
                """).query((rs, n) -> new LogicalSchemaRef(rs.getObject("uuid", UUID.class),
                rs.getString("kod"), rs.getString("ad"), rs.getString("aciklama"),
                rs.getString("saglayici_turu"), rs.getString("durum"),
                rs.getInt("mapping_count"), rs.getInt("model_count"))).stream()) {
            rows.forEach(consumer);
        }
    }

    void forEachPhysicalSchema(Consumer<PhysicalSchemaRef> consumer) {
        try (var rows = jdbc.sql("""
                select f.uuid, b.uuid as connection_uuid, b.kod as connection_code,
                       f.kod, f.ad, f.aciklama, f.saglayici_turu, f.katalog_adi, f.sema_adi,
                       f.calisma_katalog_adi, f.calisma_sema_adi, f.varsayilan_mi,
                       f.yukleme_prefix, f.entegrasyon_prefix, f.hata_prefix, f.gecici_prefix,
                       f.nesne_deseni, f.uzak_nesne_deseni, f.sira_deseni, f.durum
                  from akis.fiziksel_sema f join akis.baglanti b on b.id = f.baglanti_id
                 order by b.kod, f.ad, f.kod
                """).query((rs, n) -> new PhysicalSchemaRef(
                rs.getObject("uuid", UUID.class), rs.getObject("connection_uuid", UUID.class),
                rs.getString("connection_code"), rs.getString("kod"), rs.getString("ad"),
                rs.getString("aciklama"), rs.getString("saglayici_turu"), rs.getString("katalog_adi"),
                rs.getString("sema_adi"), rs.getString("calisma_katalog_adi"), rs.getString("calisma_sema_adi"),
                rs.getBoolean("varsayilan_mi"), rs.getString("yukleme_prefix"), rs.getString("entegrasyon_prefix"),
                rs.getString("hata_prefix"), rs.getString("gecici_prefix"), rs.getString("nesne_deseni"),
                rs.getString("uzak_nesne_deseni"), rs.getString("sira_deseni"), rs.getString("durum"))).stream()) {
            rows.forEach(consumer);
        }
    }
}

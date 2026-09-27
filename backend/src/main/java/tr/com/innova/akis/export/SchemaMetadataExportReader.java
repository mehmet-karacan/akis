package tr.com.innova.akis.export;

import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Bounded, read-only reader for the global schema dictionary. */
@Component
class SchemaMetadataExportReader {

    private final JdbcClient jdbc;

    SchemaMetadataExportReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void forEachSchema(Consumer<SchemaRef> consumer) {
        stream("select uuid, ad, aciklama from akis.sema_tanimlari order by ad",
                (rs, n) -> new SchemaRef(rs.getObject("uuid", java.util.UUID.class), rs.getString("ad"), rs.getString("aciklama")))
                .forEach(consumer);
    }

    void forEachTable(Consumer<TableRef> consumer) {
        stream("""
                select t.uuid, s.uuid as schema_uuid, t.ad, t.aciklama
                  from akis.tablo_tanimlari t
                  join akis.sema_tanimlari s on s.id = t.sema_tanimi_id
                 order by s.ad, t.ad
                """, (rs, n) -> new TableRef(rs.getObject("uuid", java.util.UUID.class),
                rs.getObject("schema_uuid", java.util.UUID.class), rs.getString("ad"), rs.getString("aciklama")))
                .forEach(consumer);
    }

    void forEachColumn(Consumer<ColumnRef> consumer) {
        stream("""
                select k.uuid, t.uuid as table_uuid, k.sira_no, k.ad, k.aciklama,
                       k.veri_tipi, k.uzunluk, k.zorunlu_mu, k.varsayilan_deger
                  from akis.kolon_tanimlari k
                  join akis.tablo_tanimlari t on t.id = k.tablo_tanimi_id
                 order by t.ad, k.sira_no
                """, (rs, n) -> new ColumnRef(rs.getObject("uuid", java.util.UUID.class),
                rs.getObject("table_uuid", java.util.UUID.class), rs.getInt("sira_no"),
                rs.getString("ad"), rs.getString("aciklama"), rs.getString("veri_tipi"),
                rs.getObject("uzunluk", Long.class), rs.getBoolean("zorunlu_mu"),
                rs.getString("varsayilan_deger"))).forEach(consumer);
    }

    void forEachConstraint(Consumer<ConstraintRef> consumer) {
        stream("""
                select k.uuid, t.uuid as table_uuid, k.ad, k.aciklama, k.tur, k.check_ifadesi
                  from akis.kisit_tanimlari k
                  join akis.tablo_tanimlari t on t.id = k.tablo_tanimi_id
                 order by t.ad, k.ad
                """, (rs, n) -> new ConstraintRef(rs.getObject("uuid", java.util.UUID.class),
                rs.getObject("table_uuid", java.util.UUID.class), rs.getString("ad"),
                rs.getString("aciklama"), rs.getString("tur"), rs.getString("check_ifadesi")))
                .forEach(consumer);
    }

    void forEachIndex(Consumer<IndexRef> consumer) {
        stream("""
                select i.uuid, t.uuid as table_uuid, i.ad, i.aciklama, i.tur, i.benzersiz_mi
                  from akis.indeks_tanimlari i
                  join akis.tablo_tanimlari t on t.id = i.tablo_tanimi_id
                 order by t.ad, i.ad
                """, (rs, n) -> new IndexRef(rs.getObject("uuid", java.util.UUID.class),
                rs.getObject("table_uuid", java.util.UUID.class), rs.getString("ad"),
                rs.getString("aciklama"), rs.getString("tur"), rs.getBoolean("benzersiz_mi")))
                .forEach(consumer);
    }

    void forEachRelationship(Consumer<RelationshipRef> consumer) {
        stream("""
                select il.uuid, source_t.uuid as source_table_uuid, source_t.ad as source_table,
                       target_t.uuid as target_table_uuid, target_t.ad as target_table,
                       source_k.ad as source_constraint, target_k.ad as target_constraint,
                       il.silme_kurali, il.guncelleme_kurali
                  from akis.iliski_tanimlari il
                  join akis.kisit_tanimlari source_k on source_k.id = il.kaynak_kisit_tanimi_id
                  join akis.tablo_tanimlari source_t on source_t.id = source_k.tablo_tanimi_id
                  join akis.tablo_tanimlari target_t on target_t.id = il.hedef_tablo_tanimi_id
                  join akis.kisit_tanimlari target_k on target_k.id = il.hedef_kisit_tanimi_id
                 order by source_t.ad, target_t.ad
                """, (rs, n) -> new RelationshipRef(rs.getObject("uuid", java.util.UUID.class),
                rs.getObject("source_table_uuid", java.util.UUID.class), rs.getString("source_table"),
                rs.getObject("target_table_uuid", java.util.UUID.class), rs.getString("target_table"),
                rs.getString("source_constraint"), rs.getString("target_constraint"),
                rs.getString("silme_kurali"), rs.getString("guncelleme_kurali"))).forEach(consumer);
    }

    void forEachSequence(Consumer<SequenceRef> consumer) {
        stream("""
                select s.uuid, se.uuid as schema_uuid, s.ad, s.aciklama, s.baslangic_degeri,
                       s.artis_miktari, s.min_deger, s.max_deger, s.dongusel_mi
                  from akis.sira_tanimlari s
                  join akis.sema_tanimlari se on se.id = s.sema_tanimi_id
                 order by se.ad, s.ad
                """, (rs, n) -> new SequenceRef(rs.getObject("uuid", java.util.UUID.class),
                rs.getObject("schema_uuid", java.util.UUID.class), rs.getString("ad"),
                rs.getString("aciklama"), rs.getLong("baslangic_degeri"), rs.getLong("artis_miktari"),
                rs.getObject("min_deger", Long.class), rs.getObject("max_deger", Long.class),
                rs.getBoolean("dongusel_mi"))).forEach(consumer);
    }

    private <T> java.util.stream.Stream<T> stream(String sql, org.springframework.jdbc.core.RowMapper<T> mapper) {
        return jdbc.sql(sql).query(mapper).stream();
    }

    record SchemaRef(java.util.UUID uuid, String name, String description) {}
    record TableRef(java.util.UUID uuid, java.util.UUID schemaUuid, String name, String description) {}
    record ColumnRef(java.util.UUID uuid, java.util.UUID tableUuid, int ordinal, String name, String description,
            String dataType, Long length, boolean required, String defaultValue) {}
    record ConstraintRef(java.util.UUID uuid, java.util.UUID tableUuid, String name, String description,
            String type, String checkExpression) {}
    record IndexRef(java.util.UUID uuid, java.util.UUID tableUuid, String name, String description,
            String type, boolean unique) {}
    record RelationshipRef(java.util.UUID uuid, java.util.UUID sourceTableUuid, String sourceTable,
            java.util.UUID targetTableUuid, String targetTable, String sourceConstraint,
            String targetConstraint, String deleteRule, String updateRule) {}
    record SequenceRef(java.util.UUID uuid, java.util.UUID schemaUuid, String name, String description,
            long startValue, long increment, Long minValue, Long maxValue, boolean cycle) {}
}

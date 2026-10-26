-- Identity sequence'lerinin gerçek OWNED BY kolon ilişkisini metadata'ya yansıt.
-- PostgreSQL identity sequence'leri pg_depend içinde 'i', serial sequence'leri
-- ise 'a' bağımlılık türüyle tutulur.
WITH sequence_owners AS (
    SELECT seq.oid AS sequence_oid,
           seq.relname AS sequence_name,
           n.nspname AS schema_name,
           dep.refobjid AS table_oid,
           dep.refobjsubid AS column_attnum
      FROM pg_class seq
      JOIN pg_namespace n ON n.oid = seq.relnamespace
      JOIN pg_depend dep ON dep.classid = 'pg_class'::regclass
       AND dep.objid = seq.oid
       AND dep.refclassid = 'pg_class'::regclass
       AND dep.deptype IN ('a', 'i')
     WHERE n.nspname = 'akis'
       AND seq.relkind = 'S'
), resolved_owners AS (
    SELECT s.id AS sequence_id,
           owner_col.id AS owner_column_id
      FROM akis.sira_tanimlari s
      JOIN sequence_owners a
        ON a.sequence_name = s.ad
       AND a.schema_name = 'akis'
      JOIN pg_attribute owner_attr
        ON owner_attr.attrelid = a.table_oid
       AND owner_attr.attnum = a.column_attnum
      JOIN pg_class owner_rel
        ON owner_rel.oid = a.table_oid
      JOIN akis.tablo_tanimlari owner_table
        ON owner_table.sema_tanimi_id = s.sema_tanimi_id
       AND owner_table.ad = owner_rel.relname
      JOIN akis.kolon_tanimlari owner_col
        ON owner_col.tablo_tanimi_id = owner_table.id
       AND owner_col.ad = owner_attr.attname
)
UPDATE akis.sira_tanimlari s
   SET sahibi_kolon_tanimi_id = r.owner_column_id,
       guncellenme_zamani = CURRENT_TIMESTAMP,
       guncelleyen_kullanici_id = (
           SELECT id FROM akis.kullanici
            WHERE lower(kullanici_kodu) = 'mkaracan'
            LIMIT 1
       )
  FROM resolved_owners r
 WHERE s.id = r.sequence_id
   AND s.sahibi_kolon_tanimi_id IS DISTINCT FROM r.owner_column_id;

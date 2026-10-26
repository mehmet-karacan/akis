-- Şema Metadata sözlüğünü migration anındaki akis şemasıyla tamamlar.
-- Flyway ve Spring Session altyapısı tabloları uygulama veri sözlüğüne alınmaz.
SET search_path TO akis, public;

-- Demo/kurulum hesabını tek bir kurumsal kullanıcı kodunda tut.
-- Kullanıcı henüz yoksa parola kurulumu için bekleyen güvenli bir hesap açılır;
-- bootstrap-admin bu kaydı daha sonra parola ve rol ile etkinleştirir.
DO $$
DECLARE
    metadata_user_id BIGINT;
    legacy_user_id BIGINT;
BEGIN
    SELECT id INTO metadata_user_id
      FROM akis.kullanici
     WHERE lower(kullanici_kodu) = 'mkaracan'
     ORDER BY id
     LIMIT 1;

    SELECT id INTO legacy_user_id
      FROM akis.kullanici
     WHERE lower(kullanici_kodu) = 'developer'
     ORDER BY id
     LIMIT 1;

    IF metadata_user_id IS NULL THEN
        IF legacy_user_id IS NULL THEN
            INSERT INTO akis.kullanici(
                kullanici_kodu, ad, soyad, gorunen_ad, durum
            )
            VALUES ('mkaracan', 'Mehmet', 'KARACAN', 'Mehmet KARACAN', 'PAROLA_BEKLIYOR')
            RETURNING id INTO metadata_user_id;
        ELSE
            UPDATE akis.kullanici
               SET kullanici_kodu = 'mkaracan',
                   ad = 'Mehmet',
                   soyad = 'KARACAN',
                   gorunen_ad = 'Mehmet KARACAN'
             WHERE id = legacy_user_id;
            metadata_user_id := legacy_user_id;
        END IF;
    ELSE
        UPDATE akis.kullanici
           SET ad = 'Mehmet',
               soyad = 'KARACAN',
               gorunen_ad = 'Mehmet KARACAN'
         WHERE id = metadata_user_id;

        IF legacy_user_id IS NOT NULL AND legacy_user_id <> metadata_user_id THEN
            UPDATE akis.kullanici
               SET kullanici_kodu = 'legacy_developer_' || id
             WHERE id = legacy_user_id;
        END IF;
    END IF;
END $$;

-- Migration sürümünde mevcut olan tüm akis tablo/view nesnelerini sözlüğe al.
WITH actual_tables AS (
    SELECT c.oid,
           n.nspname AS schema_name,
           c.relname AS table_name,
           coalesce(
               nullif(obj_description(c.oid, 'pg_class'), ''),
               'Sistem nesnesi: ' || n.nspname || '.' || c.relname
           ) AS description
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'akis'
       AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
       AND c.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
)
INSERT INTO akis.tablo_tanimlari(
    sema_tanimi_id, ad, aciklama, olusturan_kullanici_id
)
SELECT s.id, a.table_name, left(a.description, 2000),
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM actual_tables a
  JOIN akis.sema_tanimlari s ON s.ad = a.schema_name
ON CONFLICT (sema_tanimi_id, ad) DO UPDATE
    SET aciklama = coalesce(akis.tablo_tanimlari.aciklama, excluded.aciklama);

-- Tablo kolonlarını PostgreSQL katalogundan al; tip, sıra, nullability ve default
-- bilgisi gerçek fiziksel tanımla her migration çalışmasında hizalanır.
WITH actual_columns AS (
    SELECT c.oid AS table_oid,
           n.nspname AS schema_name,
           c.relname AS table_name,
           a.attnum,
           a.attname AS column_name,
           left(replace(replace(format_type(a.atttypid, NULL), 'timestamp with time zone', 'timestamptz'), 'timestamp without time zone', 'timestamp'), 40) AS data_type,
           ic.character_maximum_length,
           a.attnotnull,
           left(coalesce(pg_get_expr(d.adbin, d.adrelid), CASE WHEN a.attidentity <> '' THEN 'identity' END), 500) AS default_value,
           left(coalesce(nullif(col_description(c.oid, a.attnum), ''), 'Sistem kolon tanımı: ' || c.relname || '.' || a.attname), 2000) AS description
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid
       AND a.attnum > 0
       AND NOT a.attisdropped
      LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
      LEFT JOIN information_schema.columns ic
        ON ic.table_schema = n.nspname
       AND ic.table_name = c.relname
       AND ic.column_name = a.attname
     WHERE n.nspname = 'akis'
       AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
       AND c.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
)
INSERT INTO akis.kolon_tanimlari(
    tablo_tanimi_id, sira_no, ad, aciklama, veri_tipi, uzunluk,
    zorunlu_mu, varsayilan_deger, olusturan_kullanici_id
)
SELECT t.id, a.attnum, a.column_name, a.description, a.data_type,
       a.character_maximum_length, a.attnotnull, a.default_value,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM actual_columns a
  JOIN akis.sema_tanimlari s ON s.ad = a.schema_name
  JOIN akis.tablo_tanimlari t ON t.sema_tanimi_id = s.id AND t.ad = a.table_name
ON CONFLICT (tablo_tanimi_id, ad) DO UPDATE
    SET sira_no = excluded.sira_no,
        veri_tipi = excluded.veri_tipi,
        uzunluk = excluded.uzunluk,
        zorunlu_mu = excluded.zorunlu_mu,
        varsayilan_deger = excluded.varsayilan_deger,
        aciklama = coalesce(akis.kolon_tanimlari.aciklama, excluded.aciklama);

-- PK, UNIQUE, CHECK ve FOREIGN KEY kısıtları.
WITH actual_constraints AS (
    SELECT c.oid,
           c.conrelid AS table_oid,
           n.nspname AS schema_name,
           rel.relname AS table_name,
           c.conname,
           CASE c.contype
               WHEN 'p' THEN 'PRIMARY_KEY'
               WHEN 'u' THEN 'UNIQUE'
               WHEN 'c' THEN 'CHECK'
               WHEN 'f' THEN 'FOREIGN_KEY'
           END AS constraint_type,
           CASE WHEN c.contype = 'c' THEN left(pg_get_constraintdef(c.oid, true), 2000) END AS check_expression,
           left(coalesce(nullif(obj_description(c.oid, 'pg_constraint'), ''), 'Sistem kısıtı: ' || c.conname), 2000) AS description
      FROM pg_constraint c
      JOIN pg_class rel ON rel.oid = c.conrelid
      JOIN pg_namespace n ON n.oid = rel.relnamespace
     WHERE n.nspname = 'akis'
       AND rel.relkind IN ('r', 'p', 'v', 'm', 'f')
       AND rel.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
       AND c.contype IN ('p', 'u', 'c', 'f')
)
INSERT INTO akis.kisit_tanimlari(
    tablo_tanimi_id, ad, aciklama, tur, check_ifadesi, olusturan_kullanici_id
)
SELECT t.id, a.conname, a.description, a.constraint_type, a.check_expression,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM actual_constraints a
  JOIN akis.sema_tanimlari s ON s.ad = a.schema_name
  JOIN akis.tablo_tanimlari t ON t.sema_tanimi_id = s.id AND t.ad = a.table_name
ON CONFLICT (tablo_tanimi_id, ad) DO UPDATE
    SET tur = excluded.tur,
        check_ifadesi = excluded.check_ifadesi,
        aciklama = coalesce(akis.kisit_tanimlari.aciklama, excluded.aciklama);

-- Kısıtlerin kapsadığı kolonlar; kompozit anahtar sırası korunur.
WITH actual_constraint_columns AS (
    SELECT c.oid AS constraint_oid,
           c.conrelid AS table_oid,
           keys.attnum,
           keys.ordinality::INTEGER AS ordinal
      FROM pg_constraint c
      CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS keys(attnum, ordinality)
     WHERE c.contype IN ('p', 'u', 'f')
)
INSERT INTO akis.kisit_kolon_tanimlari(
    kisit_tanimi_id, kolon_tanimi_id, sira_no, olusturan_kullanici_id
)
SELECT kt.id, kl.id, a.ordinal,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM actual_constraint_columns a
  JOIN pg_constraint pc ON pc.oid = a.constraint_oid
  JOIN pg_class rel ON rel.oid = a.table_oid
  JOIN pg_namespace n ON n.oid = rel.relnamespace
  JOIN akis.sema_tanimlari s ON s.ad = n.nspname
  JOIN akis.tablo_tanimlari t ON t.sema_tanimi_id = s.id AND t.ad = rel.relname
  JOIN akis.kisit_tanimlari kt ON kt.tablo_tanimi_id = t.id AND kt.ad = pc.conname
  JOIN pg_attribute pa ON pa.attrelid = a.table_oid AND pa.attnum = a.attnum
  JOIN akis.kolon_tanimlari kl ON kl.tablo_tanimi_id = t.id AND kl.ad = pa.attname
ON CONFLICT (kisit_tanimi_id, kolon_tanimi_id) DO UPDATE
    SET sira_no = excluded.sira_no;

-- Foreign key ilişkileri ve kaynak/hedef kolon eşleşmeleri.
WITH foreign_keys AS (
    SELECT fk.oid,
           fk.conrelid,
           fk.confrelid,
           fk.conname,
           fk.confdeltype,
           fk.confupdtype,
           target.oid AS target_constraint_oid
      FROM pg_constraint fk
      JOIN LATERAL (
          SELECT pk.oid
            FROM pg_constraint pk
           WHERE pk.conrelid = fk.confrelid
             AND pk.contype IN ('p', 'u')
             AND pk.conkey = fk.confkey
           ORDER BY CASE WHEN pk.contype = 'p' THEN 0 ELSE 1 END, pk.oid
           LIMIT 1
      ) target ON TRUE
     WHERE fk.contype = 'f'
)
INSERT INTO akis.iliski_tanimlari(
    kaynak_kisit_tanimi_id, hedef_tablo_tanimi_id, hedef_kisit_tanimi_id,
    silme_kurali, guncelleme_kurali, olusturan_kullanici_id
)
SELECT source_k.id, target_t.id, target_k.id,
       CASE fk.confdeltype WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET_NULL' WHEN 'd' THEN 'SET_DEFAULT' WHEN 'r' THEN 'RESTRICT' ELSE 'NO_ACTION' END,
       CASE fk.confupdtype WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET_NULL' WHEN 'd' THEN 'SET_DEFAULT' WHEN 'r' THEN 'RESTRICT' ELSE 'NO_ACTION' END,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM foreign_keys fk
  JOIN pg_class source_rel ON source_rel.oid = fk.conrelid
  JOIN pg_namespace source_ns ON source_ns.oid = source_rel.relnamespace
  JOIN pg_class target_rel ON target_rel.oid = fk.confrelid
  JOIN pg_namespace target_ns ON target_ns.oid = target_rel.relnamespace
  JOIN akis.sema_tanimlari source_s ON source_s.ad = source_ns.nspname
  JOIN akis.sema_tanimlari target_s ON target_s.ad = target_ns.nspname
  JOIN akis.tablo_tanimlari source_t ON source_t.sema_tanimi_id = source_s.id AND source_t.ad = source_rel.relname
  JOIN akis.tablo_tanimlari target_t ON target_t.sema_tanimi_id = target_s.id AND target_t.ad = target_rel.relname
  JOIN akis.kisit_tanimlari source_k ON source_k.tablo_tanimi_id = source_t.id AND source_k.ad = fk.conname
  JOIN pg_constraint target_pc ON target_pc.oid = fk.target_constraint_oid
  JOIN akis.kisit_tanimlari target_k ON target_k.tablo_tanimi_id = target_t.id AND target_k.ad = target_pc.conname
ON CONFLICT (kaynak_kisit_tanimi_id) DO UPDATE
    SET hedef_tablo_tanimi_id = excluded.hedef_tablo_tanimi_id,
        hedef_kisit_tanimi_id = excluded.hedef_kisit_tanimi_id,
        silme_kurali = excluded.silme_kurali,
        guncelleme_kurali = excluded.guncelleme_kurali;

WITH foreign_key_columns AS (
    SELECT fk.oid,
           fk.conrelid,
           fk.confrelid,
           pairs.source_attnum,
           pairs.target_attnum,
           pairs.ordinality::INTEGER AS ordinal
      FROM pg_constraint fk
      CROSS JOIN LATERAL unnest(fk.conkey, fk.confkey) WITH ORDINALITY AS pairs(source_attnum, target_attnum, ordinality)
     WHERE fk.contype = 'f'
)
INSERT INTO akis.iliski_kolon_tanimlari(
    iliski_tanimi_id, kaynak_kolon_tanimi_id, hedef_kolon_tanimi_id,
    sira_no, olusturan_kullanici_id
)
SELECT il.id, source_col.id, target_col.id, a.ordinal,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM foreign_key_columns a
  JOIN pg_constraint fk ON fk.oid = a.oid
  JOIN pg_class source_rel ON source_rel.oid = a.conrelid
  JOIN pg_namespace source_ns ON source_ns.oid = source_rel.relnamespace
  JOIN pg_class target_rel ON target_rel.oid = a.confrelid
  JOIN pg_namespace target_ns ON target_ns.oid = target_rel.relnamespace
  JOIN akis.sema_tanimlari source_s ON source_s.ad = source_ns.nspname
  JOIN akis.sema_tanimlari target_s ON target_s.ad = target_ns.nspname
  JOIN akis.tablo_tanimlari source_t ON source_t.sema_tanimi_id = source_s.id AND source_t.ad = source_rel.relname
  JOIN akis.tablo_tanimlari target_t ON target_t.sema_tanimi_id = target_s.id AND target_t.ad = target_rel.relname
  JOIN akis.kolon_tanimlari source_col ON source_col.tablo_tanimi_id = source_t.id
  JOIN pg_attribute source_attr ON source_attr.attrelid = a.conrelid AND source_attr.attnum = a.source_attnum AND source_col.ad = source_attr.attname
  JOIN akis.kolon_tanimlari target_col ON target_col.tablo_tanimi_id = target_t.id
  JOIN pg_attribute target_attr ON target_attr.attrelid = a.confrelid AND target_attr.attnum = a.target_attnum AND target_col.ad = target_attr.attname
  JOIN akis.kisit_tanimlari source_k ON source_k.tablo_tanimi_id = source_t.id AND source_k.ad = fk.conname
  JOIN akis.iliski_tanimlari il ON il.kaynak_kisit_tanimi_id = source_k.id
ON CONFLICT (iliski_tanimi_id, sira_no) DO UPDATE
    SET kaynak_kolon_tanimi_id = excluded.kaynak_kolon_tanimi_id,
        hedef_kolon_tanimi_id = excluded.hedef_kolon_tanimi_id;

-- Fiziksel indeksler ve indeks kolonları.
WITH actual_indexes AS (
    SELECT idx.oid AS index_oid,
           idx.relname AS index_name,
           idx.relkind,
           tbl.oid AS table_oid,
           n.nspname AS schema_name,
           tbl.relname AS table_name,
           upper(am.amname) AS access_method,
           i.indisunique,
           left(coalesce(nullif(obj_description(idx.oid, 'pg_class'), ''), 'Sistem indeksi: ' || idx.relname), 2000) AS description
      FROM pg_class idx
      JOIN pg_index i ON i.indexrelid = idx.oid
      JOIN pg_class tbl ON tbl.oid = i.indrelid
      JOIN pg_namespace n ON n.oid = tbl.relnamespace
      JOIN pg_am am ON am.oid = idx.relam
     WHERE n.nspname = 'akis'
       AND tbl.relkind IN ('r', 'p', 'v', 'm', 'f')
       AND tbl.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
       AND idx.relkind = 'i'
)
INSERT INTO akis.indeks_tanimlari(
    tablo_tanimi_id, ad, aciklama, tur, benzersiz_mi, olusturan_kullanici_id
)
SELECT t.id, a.index_name, a.description,
       CASE WHEN a.access_method IN ('BTREE', 'HASH', 'GIN', 'GIST', 'BRIN') THEN a.access_method ELSE 'BTREE' END,
       a.indisunique,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM actual_indexes a
  JOIN akis.sema_tanimlari s ON s.ad = a.schema_name
  JOIN akis.tablo_tanimlari t ON t.sema_tanimi_id = s.id AND t.ad = a.table_name
ON CONFLICT (tablo_tanimi_id, ad) DO UPDATE
    SET tur = excluded.tur,
        benzersiz_mi = excluded.benzersiz_mi,
        aciklama = coalesce(akis.indeks_tanimlari.aciklama, excluded.aciklama);

WITH actual_index_columns AS (
    SELECT idx.oid AS index_oid,
           i.indrelid AS table_oid,
           keys.attnum,
           keys.ordinality::INTEGER AS ordinal,
           CASE WHEN (i.indoption[keys.ordinality::INTEGER] & 1) <> 0 THEN 'DESC' ELSE 'ASC' END AS direction
      FROM pg_class idx
      JOIN pg_index i ON i.indexrelid = idx.oid
      CROSS JOIN LATERAL unnest(i.indkey::smallint[]) WITH ORDINALITY AS keys(attnum, ordinality)
     WHERE idx.relkind = 'i'
       AND keys.attnum > 0
)
INSERT INTO akis.indeks_kolon_tanimlari(
    indeks_tanimi_id, kolon_tanimi_id, sira_no, siralama, olusturan_kullanici_id
)
SELECT idx_meta.id, col_meta.id, a.ordinal, a.direction,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM actual_index_columns a
  JOIN pg_class idx ON idx.oid = a.index_oid
  JOIN pg_class tbl ON tbl.oid = a.table_oid
  JOIN pg_namespace n ON n.oid = tbl.relnamespace
  JOIN akis.sema_tanimlari s ON s.ad = n.nspname
  JOIN akis.tablo_tanimlari t ON t.sema_tanimi_id = s.id AND t.ad = tbl.relname
  JOIN akis.indeks_tanimlari idx_meta ON idx_meta.tablo_tanimi_id = t.id AND idx_meta.ad = idx.relname
  JOIN pg_attribute pa ON pa.attrelid = a.table_oid AND pa.attnum = a.attnum
  JOIN akis.kolon_tanimlari col_meta ON col_meta.tablo_tanimi_id = t.id AND col_meta.ad = pa.attname
 WHERE tbl.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
ON CONFLICT (indeks_tanimi_id, sira_no) DO UPDATE
    SET kolon_tanimi_id = excluded.kolon_tanimi_id,
        siralama = excluded.siralama;

-- Identity/serial sequence'leri ve sahip oldukları kolonları kaydet.
WITH actual_sequences AS (
    SELECT seq.oid AS sequence_oid,
           n.nspname AS schema_name,
           seq.relname AS sequence_name,
           ps.seqstart,
           ps.seqincrement,
           ps.seqmin,
           ps.seqmax,
           ps.seqcycle,
           owner_rel.relname AS owner_table_name,
           owner_attr.attname AS owner_column_name
      FROM pg_class seq
      JOIN pg_namespace n ON n.oid = seq.relnamespace
      JOIN pg_sequence ps ON ps.seqrelid = seq.oid
      LEFT JOIN pg_depend dep ON dep.classid = 'pg_class'::regclass
       AND dep.objid = seq.oid
       AND dep.refclassid = 'pg_class'::regclass
       AND dep.deptype = 'a'
      LEFT JOIN pg_class owner_rel ON owner_rel.oid = dep.refobjid
      LEFT JOIN pg_attribute owner_attr ON owner_attr.attrelid = dep.refobjid AND owner_attr.attnum = dep.refobjsubid
     WHERE n.nspname = 'akis'
       AND seq.relkind = 'S'
)
INSERT INTO akis.sira_tanimlari(
    sema_tanimi_id, ad, aciklama, baslangic_degeri, artis_miktari,
    min_deger, max_deger, dongusel_mi, sahibi_kolon_tanimi_id,
    olusturan_kullanici_id
)
SELECT s.id, a.sequence_name, 'Sistem sequence tanımı: ' || a.sequence_name,
       a.seqstart, a.seqincrement, a.seqmin, a.seqmax, a.seqcycle,
       owner_col.id,
       (SELECT id FROM akis.kullanici WHERE lower(kullanici_kodu) = 'mkaracan' LIMIT 1)
  FROM actual_sequences a
  JOIN akis.sema_tanimlari s ON s.ad = a.schema_name
  LEFT JOIN akis.tablo_tanimlari owner_table ON owner_table.sema_tanimi_id = s.id AND owner_table.ad = a.owner_table_name
  LEFT JOIN akis.kolon_tanimlari owner_col ON owner_col.tablo_tanimi_id = owner_table.id AND owner_col.ad = a.owner_column_name
ON CONFLICT (sema_tanimi_id, ad) DO UPDATE
    SET baslangic_degeri = excluded.baslangic_degeri,
        artis_miktari = excluded.artis_miktari,
        min_deger = excluded.min_deger,
        max_deger = excluded.max_deger,
        dongusel_mi = excluded.dongusel_mi,
        sahibi_kolon_tanimi_id = excluded.sahibi_kolon_tanimi_id;

-- Sözlükte ve sistem kayıtlarında audit sahibi olarak Mehmet KARACAN görünür.
DO $$
DECLARE
    metadata_user_id BIGINT;
    item RECORD;
BEGIN
    SELECT id INTO metadata_user_id
      FROM akis.kullanici
     WHERE lower(kullanici_kodu) = 'mkaracan'
     ORDER BY id
     LIMIT 1;

    FOR item IN
        SELECT DISTINCT c.relname AS table_name
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN information_schema.columns ic
            ON ic.table_schema = n.nspname
           AND ic.table_name = c.relname
         WHERE n.nspname = 'akis'
           AND c.relkind IN ('r', 'p')
           AND c.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
           AND NOT EXISTS (
               SELECT 1
                 FROM pg_trigger blocked
                WHERE blocked.tgrelid = c.oid
                  AND NOT blocked.tgisinternal
           )
           AND ic.column_name IN ('olusturan_kullanici_id', 'guncelleyen_kullanici_id')
    LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'akis'
               AND table_name = item.table_name
               AND column_name = 'olusturan_kullanici_id'
        ) THEN
            EXECUTE format(
                'UPDATE akis.%I SET olusturan_kullanici_id = coalesce(olusturan_kullanici_id, $1)',
                item.table_name
            ) USING metadata_user_id;
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'akis'
               AND table_name = item.table_name
               AND column_name = 'guncelleyen_kullanici_id'
        ) THEN
            EXECUTE format(
                'UPDATE akis.%I SET guncelleyen_kullanici_id = coalesce(guncelleyen_kullanici_id, $1) WHERE guncellenme_zamani IS NOT NULL',
                item.table_name
            ) USING metadata_user_id;
        END IF;
    END LOOP;
END $$;

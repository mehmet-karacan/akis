-- V034: V033 topoloji geçişi doğrulandı; yedek tabloları düşür ve prefix uzunluğunu çalışma zamanı ile hizala.
SET search_path TO akis;

-- 1) Çalışma tablosu prefixleri: motor "AKIS_<prefix>_<hash>" adını 30 bayta sığdırır; prefix en çok 8 karakter olabilir.
ALTER TABLE fiziksel_sema DROP CONSTRAINT ck_fiziksel_sema_prefix;
ALTER TABLE fiziksel_sema
    ALTER COLUMN yukleme_prefix     TYPE VARCHAR(8),
    ALTER COLUMN entegrasyon_prefix TYPE VARCHAR(8),
    ALTER COLUMN hata_prefix        TYPE VARCHAR(8),
    ALTER COLUMN gecici_prefix      TYPE VARCHAR(8);
ALTER TABLE fiziksel_sema ADD CONSTRAINT ck_fiziksel_sema_prefix CHECK (
        yukleme_prefix     ~ '^[A-Z][A-Z0-9_$]{0,7}$'
    AND entegrasyon_prefix ~ '^[A-Z][A-Z0-9_$]{0,7}$'
    AND hata_prefix        ~ '^[A-Z][A-Z0-9_$]{0,7}$'
    AND gecici_prefix      ~ '^[A-Z][A-Z0-9_$]{0,7}$'
    AND yukleme_prefix <> entegrasyon_prefix AND yukleme_prefix <> hata_prefix
    AND entegrasyon_prefix <> hata_prefix
    AND gecici_prefix NOT IN (yukleme_prefix, entegrasyon_prefix, hata_prefix));

UPDATE kolon_tanimlari k
   SET uzunluk = 8
  FROM tablo_tanimlari t
 WHERE k.tablo_tanimi_id = t.id AND t.ad = 'fiziksel_sema'
   AND k.ad IN ('yukleme_prefix','entegrasyon_prefix','hata_prefix','gecici_prefix');

UPDATE kisit_tanimlari k
   SET check_ifadesi = (
        SELECT regexp_replace(pg_get_constraintdef(c.oid), '^CHECK \((.*)\)$', '\1')
          FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid JOIN pg_namespace n ON n.oid = r.relnamespace
         WHERE n.nspname = 'akis' AND r.relname = 'fiziksel_sema' AND c.conname = 'ck_fiziksel_sema_prefix')
  FROM tablo_tanimlari t
 WHERE k.tablo_tanimi_id = t.id AND t.ad = 'fiziksel_sema' AND k.ad = 'ck_fiziksel_sema_prefix';

-- 2) V033 yedekleri (geçiş doğrulandıktan sonra kaldırılır).
DROP TABLE IF EXISTS yedek_calisma_prefix_migration_kaydi;
DROP TABLE IF EXISTS yedek_calisma_nesnesi_prefix;
DROP TABLE IF EXISTS yedek_sema_eslemesi;
DROP TABLE IF EXISTS yedek_ortam;
DROP TABLE IF EXISTS yedek_mantiksal_sema;
DROP TABLE IF EXISTS yedek_fiziksel_sema;
DROP TABLE IF EXISTS yedek_baglanti_testi;
DROP TABLE IF EXISTS yedek_baglanti_kimligi;
DROP TABLE IF EXISTS yedek_baglanti_surumu;
DROP TABLE IF EXISTS yedek_baglanti;

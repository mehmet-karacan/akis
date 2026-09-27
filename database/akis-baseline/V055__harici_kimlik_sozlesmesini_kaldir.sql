SET search_path TO akis, public;

-- V053 tüm yerel kullanıcı kodlarını kanonik kullanici kaydına taşıdı.
-- Uygulama artık kimliği yalnız kullanici.id/uuid üzerinden çözer.
-- Bu migration harici_kimlik tablosunu canlı ortamda güvenli şekilde kaldırabilir
-- ancak belirli önkoşulları zorunlu kılar. Olası bir geri dönüş için önce
-- harici_kimlik tablosunun yedeğini alır; önkoşul ihlali durumunda hata atar.

DO $$
BEGIN
    -- 1. Tablo yoksa hiçbir şey yapma (idempotent davranış).
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'akis' AND table_name = 'harici_kimlik'
    ) THEN
        RETURN;
    END IF;

    -- 2. harici_kimlik tablosuna başka tablolardan FK referansı varsa dur.
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints tc
        JOIN information_schema.constraint_column_usage ccu
          ON tc.constraint_name = ccu.constraint_name
         AND tc.constraint_schema = ccu.constraint_schema
        WHERE tc.constraint_type = 'FOREIGN KEY'
          AND ccu.table_schema = 'akis'
          AND ccu.table_name = 'harici_kimlik'
    ) THEN
        RAISE EXCEPTION 'harici_kimlik tablosuna dış referanslar mevcut; drop güvenli değil.';
    END IF;

    -- 3. Yerel dışı veya harici sağlayıcıya ait kimlik kaydı varsa dur.
    IF EXISTS (
        SELECT 1 FROM akis.harici_kimlik
        WHERE saglayici_turu <> 'YEREL' OR yayinlayici IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Yerel dışı harici kimlik kayıtları mevcut; önce geçiş yapılmalı.';
    END IF;

    -- 4. Yerel harici kimliklerin tamamı kullanici.kullanici_kodu ile eşleşmeli.
    IF EXISTS (
        SELECT 1 FROM akis.harici_kimlik h
        JOIN akis.kullanici k ON k.id = h.kullanici_id
        WHERE h.saglayici_turu = 'YEREL' AND h.yayinlayici IS NULL
          AND (
              k.kullanici_kodu IS NULL
              OR lower(k.kullanici_kodu) <> lower(h.harici_kullanici_anahtari)
          )
    ) THEN
        RAISE EXCEPTION 'Bazı yerel harici kimlikler kanonik kullanici kaydıyla eşleşmiyor.';
    END IF;

    -- 5. Kullanici tablosunda kullanici_kodu boş olan satır varsa dur.
    IF EXISTS (
        SELECT 1 FROM akis.kullanici
        WHERE kullanici_kodu IS NULL OR btrim(kullanici_kodu) = ''
    ) THEN
        RAISE EXCEPTION 'kullanici tablosunda doldurulmamis kullanici_kodu var.';
    END IF;
END $$;

-- Ön koşullar sağlandıysa, harici_kimlik tablosunu yedekleyip kaldır.
CREATE TABLE IF NOT EXISTS akis.harici_kimlik_drop_backup AS
SELECT *, current_timestamp AS yedek_zamani FROM akis.harici_kimlik;

DROP TABLE akis.harici_kimlik;

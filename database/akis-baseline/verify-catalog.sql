SET search_path TO akis, public;

DO $$
DECLARE actual BIGINT; required_name TEXT;
BEGIN
    FOREACH required_name IN ARRAY ARRAY[
        'model', 'alt_model', 'veri_nesnesi', 'sema_goruntusu',
        'kolon_goruntusu', 'veri_nesnesi_kolon_politikasi'
    ] LOOP
        IF to_regclass(format('akis.%I', required_name)) IS NULL THEN
            RAISE EXCEPTION 'Katalog tablosu bulunamadı: %.', required_name;
        END IF;
    END LOOP;

    FOREACH required_name IN ARRAY ARRAY[
        'id', 'uuid', 'proje_id', 'model_id', 'kod', 'ad', 'nesne_referansi', 'tur'
    ] LOOP
        SELECT COUNT(*) INTO actual
          FROM information_schema.columns
         WHERE table_schema = 'akis'
           AND table_name = 'veri_nesnesi'
           AND column_name = required_name;
        IF actual <> 1 THEN
            RAISE EXCEPTION 'veri_nesnesi.% kolonu bulunamadı.', required_name;
        END IF;
    END LOOP;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_class r ON r.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = r.relnamespace
         WHERE n.nspname = 'akis'
           AND r.relname = 'veri_nesnesi'
           AND c.conname = 'ck_veri_nesnesi_tur') THEN
        RAISE EXCEPTION 'veri_nesnesi nesne türü kısıtı bulunamadı.';
    END IF;
END $$;

SELECT 'AKIS_CATALOG_OK' AS verification_result;

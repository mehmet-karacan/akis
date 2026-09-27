SET search_path TO akis, public;

DO $$
DECLARE
    required_table TEXT;
BEGIN
    FOREACH required_table IN ARRAY ARRAY[
        'baglanti', 'fiziksel_sema', 'mantiksal_sema', 'ortam',
        'sema_eslemesi', 'baglanti_testi'
    ] LOOP
        IF to_regclass('akis.' || required_table) IS NULL THEN
            RAISE EXCEPTION 'Güncel bağlantı topolojisi tablosu eksik: %', required_table;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
          FROM information_schema.columns AS c
         WHERE c.table_schema = 'akis'
           AND c.table_name IN ('baglanti', 'fiziksel_sema', 'mantiksal_sema', 'ortam', 'sema_eslemesi')
           AND c.column_name = 'proje_id'
    ) THEN
        RAISE EXCEPTION 'V033 sonrası global topoloji tablolarında proje_id kalmamalıdır.';
    END IF;
END
$$;

SELECT 'AKIS_CONNECTIONS_OK' AS verification_result;

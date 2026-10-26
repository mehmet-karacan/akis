-- V066 sonrası Şema Metadata kataloğunun sistem tablolarıyla tamlığını doğrular.
SET search_path TO akis, public;

DO $$
DECLARE
    missing_tables BIGINT;
    missing_columns BIGINT;
    metadata_user BIGINT;
BEGIN
    SELECT id INTO metadata_user
      FROM akis.kullanici
     WHERE lower(kullanici_kodu) = 'mkaracan';

    IF metadata_user IS NULL THEN
        RAISE EXCEPTION 'mkaracan kullanıcı hesabı bulunamadı.';
    END IF;

    SELECT count(*) INTO missing_tables
      FROM information_schema.tables actual
     WHERE actual.table_schema = 'akis'
       AND actual.table_name NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
       AND NOT EXISTS (
           SELECT 1
             FROM akis.tablo_tanimlari t
             JOIN akis.sema_tanimlari s ON s.id = t.sema_tanimi_id
            WHERE s.ad = actual.table_schema
              AND t.ad = actual.table_name
       );

    IF missing_tables <> 0 THEN
        RAISE EXCEPTION '% sistem tablosunun tablo tanımı eksik.', missing_tables;
    END IF;

    SELECT count(*) INTO missing_columns
      FROM information_schema.columns actual
     WHERE actual.table_schema = 'akis'
       AND actual.table_name NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
       AND NOT EXISTS (
           SELECT 1
             FROM akis.kolon_tanimlari k
             JOIN akis.tablo_tanimlari t ON t.id = k.tablo_tanimi_id
             JOIN akis.sema_tanimlari s ON s.id = t.sema_tanimi_id
            WHERE s.ad = actual.table_schema
              AND t.ad = actual.table_name
              AND k.ad = actual.column_name
       );

    IF missing_columns <> 0 THEN
        RAISE EXCEPTION '% sistem kolonunun kolon tanımı eksik.', missing_columns;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM akis.tablo_tanimlari
         WHERE olusturan_kullanici_id IS DISTINCT FROM metadata_user
            OR (guncellenme_zamani IS NOT NULL AND guncelleyen_kullanici_id IS DISTINCT FROM metadata_user)
    ) THEN
        RAISE EXCEPTION 'Şema Metadata audit sahipleri Mehmet KARACAN değil.';
    END IF;
END $$;

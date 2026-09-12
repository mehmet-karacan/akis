SET search_path TO akis, public;

DO $$
DECLARE
    actual BIGINT;
    required_column TEXT;
BEGIN
    SELECT COUNT(*) INTO actual
      FROM information_schema.tables
     WHERE table_schema = 'akis' AND table_type = 'BASE TABLE';
    IF actual <> 21 THEN
        RAISE EXCEPTION 'Üç grup sonunda 21 Akış tablosu bekleniyordu; bulunan %.', actual;
    END IF;

    FOREACH required_column IN ARRAY ARRAY[
        'id', 'uuid', 'olusturulma_zamani', 'olusturan_kullanici_id',
        'guncellenme_zamani', 'guncelleyen_kullanici_id', 'versiyon_no'
    ] LOOP
        SELECT COUNT(*) INTO actual
          FROM information_schema.columns
         WHERE table_schema = 'akis' AND column_name = required_column;
        IF actual <> 21 THEN
            RAISE EXCEPTION 'Ortak kolon % yalnız % tabloda bulundu.', required_column, actual;
        END IF;
    END LOOP;
END
$$;

BEGIN;

INSERT INTO proje(id, kod, ad) VALUES
    (920001, 'DEFINITIONS', 'Definitions'),
    (920002, 'FOREIGN_PROJECT', 'Foreign Project');

INSERT INTO klasor(id, proje_id, kod, ad) VALUES
    (920010, 920001, 'FINANCE', 'Finance'),
    (920011, 920001, 'DAILY', 'Daily');
UPDATE klasor SET ust_klasor_id = 920010 WHERE id = 920011;

INSERT INTO tanim(id, proje_id, klasor_id, tur, kod, ad) VALUES
    (920020, 920001, 920011, 'PROSEDUR', 'LOAD_LEDGER', 'Load Ledger'),
    (920021, 920001, 920011, 'MAPPING', 'MAP_LEDGER', 'Map Ledger');

INSERT INTO tanim(id, kapsam, tur, kod, ad)
VALUES (920022, 'SISTEM', 'DEGISKEN', 'BUSINESS_DATE', 'Business Date');

INSERT INTO tanim_taslagi(proje_id, tanim_id, sema_surumu, icerik)
VALUES (920001, 920020, 1, '{"tasks":[]}'::jsonb);

INSERT INTO tanim_surumu(
    id, proje_id, tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
VALUES
    (920030, 920001, 920020, 1, 1, repeat('a', 64), '{"tasks":[]}'::jsonb),
    (920031, 920001, 920021, 1, 1, repeat('b', 64), '{"datasets":[]}'::jsonb);

INSERT INTO tanim_bagimliligi(
    proje_id, kaynak_tanim_surumu_id, hedef_tanim_surumu_id, iliski_turu)
VALUES (920001, 920030, 920031, 'CAGIRIR');

DO $$
BEGIN
    BEGIN
        UPDATE klasor SET ust_klasor_id = 920011 WHERE id = 920010;
        RAISE EXCEPTION 'Klasör döngüsü kabul edildi.';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO klasor(proje_id, ust_klasor_id, kod, ad)
        VALUES (920002, 920010, 'BAD_FOLDER', 'Bad Folder');
        RAISE EXCEPTION 'Başka projenin klasörü üst klasör kabul edildi.';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        UPDATE tanim_surumu SET icerik = '{"tasks":[1]}'::jsonb WHERE id = 920030;
        RAISE EXCEPTION 'Değişmez tanım sürümü güncellendi.';
    EXCEPTION WHEN object_not_in_prerequisite_state THEN NULL;
    END;

    BEGIN
        INSERT INTO tanim_bagimliligi(
            proje_id, kaynak_tanim_surumu_id, hedef_tanim_surumu_id, iliski_turu)
        VALUES (920002, 920030, 920031, 'OKUR');
        RAISE EXCEPTION 'Projeler arası tanım bağımlılığı kabul edildi.';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO tanim(kapsam, tur, kod, ad)
        VALUES ('SISTEM', 'PROSEDUR', 'GLOBAL_PROCEDURE', 'Global Procedure');
        RAISE EXCEPTION 'Prosedür sistem kapsamına kabul edildi.';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END
$$;

ROLLBACK;

SELECT 'AKIS_DEFINITIONS_OK' AS verification_result;

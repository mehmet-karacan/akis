SET search_path TO akis, public;

DO $$
DECLARE
    actual BIGINT;
    required_column TEXT;
BEGIN
    SELECT COUNT(*) INTO actual
      FROM information_schema.tables
     WHERE table_schema = 'akis' AND table_type = 'BASE TABLE';
    IF actual <> 16 THEN
        RAISE EXCEPTION 'İki grup sonunda 16 Akış tablosu bekleniyordu; bulunan %.', actual;
    END IF;

    FOREACH required_column IN ARRAY ARRAY[
        'id', 'uuid', 'olusturulma_zamani', 'olusturan_kullanici_id',
        'guncellenme_zamani', 'guncelleyen_kullanici_id', 'versiyon_no'
    ] LOOP
        SELECT COUNT(*) INTO actual
          FROM information_schema.columns
         WHERE table_schema = 'akis' AND column_name = required_column;
        IF actual <> 16 THEN
            RAISE EXCEPTION 'Ortak kolon % yalnız % tabloda bulundu.', required_column, actual;
        END IF;
    END LOOP;

    SELECT COUNT(*) INTO actual
      FROM information_schema.table_constraints tc
      JOIN information_schema.key_column_usage kcu
        ON kcu.constraint_schema = tc.constraint_schema
       AND kcu.constraint_name = tc.constraint_name
       AND kcu.table_name = tc.table_name
     WHERE tc.table_schema = 'akis'
       AND tc.constraint_type = 'FOREIGN KEY'
       AND kcu.column_name IN ('olusturan_kullanici_id', 'guncelleyen_kullanici_id');
    IF actual <> 32 THEN
        RAISE EXCEPTION 'Her audit kullanıcı kolonu FK olmalıdır; uygun kolon sayısı %.', actual;
    END IF;

    SELECT COUNT(*) INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'akis'
       AND column_name IN ('parola', 'sifre', 'gizli_deger', 'secret_value', 'password');
    IF actual <> 0 THEN
        RAISE EXCEPTION 'Metadata şemasında açık gizli değer kolonu bulunamaz.';
    END IF;

    SELECT COUNT(*) INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'akis'
       AND table_name IN (
           'baglanti', 'baglanti_surumu', 'baglanti_kimligi', 'baglanti_testi',
           'fiziksel_sema', 'mantiksal_sema', 'ortam', 'sema_eslemesi'
       )
       AND data_type IN ('json', 'jsonb');
    IF actual <> 0 THEN
        RAISE EXCEPTION 'Bağlantı ve şema çekirdeği yapılandırılmış kolonlar yerine JSON kullanamaz.';
    END IF;
END
$$;

BEGIN;

INSERT INTO proje(id, kod, ad) VALUES
    (910001, 'TRANSFER', 'Transfer Project'),
    (910002, 'OTHER', 'Other Project');

INSERT INTO baglanti(id, proje_id, kod, ad, saglayici_turu) VALUES
    (910010, 910001, 'SKY', 'SKY Source', 'ORACLE'),
    (910011, 910001, 'GPU', 'GPU Target', 'ORACLE'),
    (910012, 910002, 'OTHER_DB', 'Other Database', 'ORACLE');

INSERT INTO baglanti_surumu(
    id, proje_id, baglanti_id, surum_no, baglanti_modu, surucu_sinifi,
    sunucu_adi, port, servis_adi, sid)
VALUES
    (910020, 910001, 910010, 1, 'JDBC', 'oracle.jdbc.OracleDriver',
        'source.example', 1907, NULL, 'TTBP2'),
    (910021, 910001, 910011, 1, 'JDBC', 'oracle.jdbc.OracleDriver',
        'target.example', 1521, 'CT_GPU_TESTDB', NULL);

INSERT INTO baglanti_kimligi(
    proje_id, baglanti_surumu_id, kullanici_adi,
    gizli_deger_saglayicisi, gizli_deger_konumu)
VALUES
    (910001, 910020, 'INNOVA_ODI', 'ENV', 'AKIS_ORACLE_SKY_PASSWORD'),
    (910001, 910021, 'INNOVA_ODI', 'ENV', 'AKIS_ORACLE_GPU_PASSWORD');

INSERT INTO fiziksel_sema(id, proje_id, baglanti_id, kod, ad, sema_adi) VALUES
    (910030, 910001, 910010, 'SKY_TTBP', 'SKY TTBP', 'TTBP'),
    (910031, 910001, 910011, 'GPU_INNOVA_ODI', 'GPU INNOVA ODI', 'INNOVA_ODI'),
    (910032, 910002, 910012, 'OTHER_SCHEMA', 'Other Schema', 'OTHER');

INSERT INTO mantiksal_sema(id, proje_id, kod, ad)
VALUES
    (910040, 910001, 'HAKEDIS', 'Hakediş'),
    (910041, 910001, 'PERSONEL', 'Personel');

INSERT INTO ortam(id, proje_id, kod, ad, uretim_mi)
VALUES (910050, 910001, 'GELISTIRME', 'Geliştirme', FALSE);

INSERT INTO sema_eslemesi(
    proje_id, ortam_id, mantiksal_sema_id, baglanti_id,
    fiziksel_sema_id, baglanti_surumu_id)
VALUES (910001, 910050, 910040, 910011, 910031, 910021);

DO $$
BEGIN
    BEGIN
        INSERT INTO baglanti_surumu(
            proje_id, baglanti_id, surum_no, baglanti_modu,
            surucu_sinifi, sunucu_adi, port)
        VALUES (910001, 910010, 2, 'JDBC', 'oracle.jdbc.OracleDriver',
                'source.example', 1907);
        RAISE EXCEPTION 'Veritabanı konumu olmayan JDBC sürümü kabul edildi.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO baglanti_surumu(
            proje_id, baglanti_id, surum_no, baglanti_modu,
            surucu_sinifi, sunucu_adi, port, sid, baglanti_zaman_asimi_ms)
        VALUES (910001, 910010, 3, 'JDBC', 'oracle.jdbc.OracleDriver',
                'source.example', 1907, 'TTBP2', 999999);
        RAISE EXCEPTION 'Sınır dışı bağlantı zaman aşımı kabul edildi.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO sema_eslemesi(
            proje_id, ortam_id, mantiksal_sema_id, baglanti_id,
            fiziksel_sema_id, baglanti_surumu_id)
        VALUES (910001, 910050, 910041, 910012, 910032, 910021);
        RAISE EXCEPTION 'Başka projeye ait fiziksel şema eşlendi.';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;
END
$$;

INSERT INTO baglanti_testi(
    id, proje_id, baglanti_surumu_id, deneme_no, sonuc,
    urun_adi, urun_surumu, surucu_adi, surucu_surumu,
    hedef_kimlik_surumu, hedef_parmak_izi,
    baslama_zamani, tamamlanma_zamani, sure_milisaniye, uuid)
VALUES
    (910060, 910001, 910020, 1, 'BASARILI',
        'Oracle Database', '19c', 'Oracle JDBC', '23',
        1, repeat('a', 64),
        current_timestamp, current_timestamp, 0,
        '91000000-0000-0000-0000-000000000060'),
    (910061, 910001, 910021, 1, 'BASARILI',
        'Oracle Database', '19c', 'Oracle JDBC', '23',
        1, repeat('b', 64),
        current_timestamp, current_timestamp, 0,
        '91000000-0000-0000-0000-000000000061');

UPDATE baglanti_surumu
   SET durum = 'ETKIN',
       son_basarili_test_uuid = '91000000-0000-0000-0000-000000000061',
       hedef_kimlik_surumu = 1,
       hedef_parmak_izi = repeat('b', 64),
       test_edilme_zamani = current_timestamp,
       etkinlestirilme_zamani = current_timestamp
 WHERE id = 910021;

INSERT INTO baglanti_surumu(
    id, proje_id, baglanti_id, surum_no, baglanti_modu, surucu_sinifi,
    sunucu_adi, port, servis_adi)
VALUES (
    910022, 910001, 910011, 2, 'JDBC', 'oracle.jdbc.OracleDriver',
    'target-2.example', 1521, 'CT_GPU_TESTDB');

INSERT INTO baglanti_testi(
    id, proje_id, baglanti_surumu_id, deneme_no, sonuc,
    urun_adi, urun_surumu, surucu_adi, surucu_surumu,
    hedef_kimlik_surumu, hedef_parmak_izi,
    baslama_zamani, tamamlanma_zamani, sure_milisaniye, uuid)
VALUES (
    910062, 910001, 910022, 1, 'BASARILI',
    'Oracle Database', '19c', 'Oracle JDBC', '23',
    1, repeat('c', 64),
    current_timestamp, current_timestamp, 0,
    '91000000-0000-0000-0000-000000000062');

DO $$
BEGIN
    BEGIN
        UPDATE baglanti_surumu
           SET durum = 'ETKIN',
               son_basarili_test_uuid = '91000000-0000-0000-0000-000000000062',
               hedef_kimlik_surumu = 1,
               hedef_parmak_izi = repeat('c', 64),
               test_edilme_zamani = current_timestamp,
               etkinlestirilme_zamani = current_timestamp
         WHERE id = 910022;
        RAISE EXCEPTION 'Bir bağlantı için ikinci etkin sürüm kabul edildi.';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;
END
$$;

ROLLBACK;

SELECT 'AKIS_CONNECTIONS_OK' AS verification_result;

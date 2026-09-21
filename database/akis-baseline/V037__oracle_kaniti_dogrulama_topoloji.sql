SET search_path TO akis, public;

-- V033 ile baglanti_surumu kaldırıldı; Oracle kanıtı doğrulaması hâlâ sema_goruntusu.baglanti_surumu_id ve
-- baglanti_testi.baglanti_surumu_id kolonlarına bakıyordu ve her şema anlık görüntüsü yakalama
-- "column baglanti_surumu_id does not exist" ile düşüyordu (reverse engineering çalışmıyordu).
-- Zincir kontrolü korunur: görüntü ile kanıt aynı bağlantıya ait olmalı, referans verilen test o bağlantının
-- başarılı bir testi olmalıdır.
CREATE OR REPLACE FUNCTION akis.katalog_iliski_dogrula() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE goruntu_nesne_id BIGINT; goruntu_baglanti_id BIGINT; test_sonucu VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'tanim_veri_nesnesi' THEN
        SELECT veri_nesnesi_id INTO STRICT goruntu_nesne_id FROM akis.sema_goruntusu WHERE id = NEW.sema_goruntusu_id;
        IF goruntu_nesne_id <> NEW.veri_nesnesi_id THEN
            RAISE EXCEPTION 'Şema görüntüsü seçilen veri nesnesine ait değildir.' USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT baglanti_id INTO STRICT goruntu_baglanti_id FROM akis.sema_goruntusu WHERE id = NEW.sema_goruntusu_id;
        IF goruntu_baglanti_id <> NEW.baglanti_id THEN
            RAISE EXCEPTION 'Oracle kanıtı görüntüyle aynı bağlantıya ait olmalıdır.' USING ERRCODE = '23514';
        END IF;
        IF NEW.basarili_baglanti_testi_uuid IS NOT NULL THEN
            SELECT sonuc INTO STRICT test_sonucu FROM akis.baglanti_testi
             WHERE baglanti_id = NEW.baglanti_id AND uuid = NEW.basarili_baglanti_testi_uuid;
            IF test_sonucu <> 'BASARILI' THEN
                RAISE EXCEPTION 'Oracle kanıtı başarılı bir bağlantı testine dayanmalıdır.' USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

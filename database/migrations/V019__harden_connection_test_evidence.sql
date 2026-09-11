SET search_path TO entegrasyon, public;

ALTER TABLE baglanti_surumu_testi
    ADD CONSTRAINT ck_baglanti_surumu_testi_hata_kodu
        CHECK (hata_kodu IS NULL OR hata_kodu ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    ADD CONSTRAINT ck_baglanti_surumu_testi_basarisiz_icerik
        CHECK (
            sonuc_kodu <> 'FAILED'
            OR (
                database_product IS NULL
                AND database_version IS NULL
                AND database_major IS NULL
                AND database_minor IS NULL
                AND driver_name IS NULL
                AND driver_version IS NULL
                AND hedef_kimlik_surumu IS NULL
                AND hedef_parmak_izi IS NULL
            )
        );

CREATE OR REPLACE FUNCTION fn_baglanti_surumu_aktivasyon_modu_dogrula()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_baglanti_modu TEXT;
BEGIN
    IF NEW.durum_kodu = 'ACTIVE' AND OLD.durum_kodu <> 'ACTIVE' THEN
        SELECT baglanti_modu
          INTO v_baglanti_modu
          FROM entegrasyon.baglanti_surumu
         WHERE proje_id = NEW.proje_id
           AND id = NEW.baglanti_surumu_id;
        IF v_baglanti_modu IS DISTINCT FROM 'JDBC' THEN
            RAISE EXCEPTION 'Only executable JDBC connection versions can be activated.';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_baglanti_surumu_aktivasyon_modu_dogrula
BEFORE UPDATE ON baglanti_surumu_yasam_dongusu
FOR EACH ROW EXECUTE FUNCTION fn_baglanti_surumu_aktivasyon_modu_dogrula();

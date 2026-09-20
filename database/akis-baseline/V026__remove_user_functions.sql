SET search_path TO akis, public;

CREATE TEMP TABLE legacy_user_function_versions ON COMMIT DROP AS
SELECT ts.id
  FROM tanim_surumu ts
  JOIN tanim t ON t.id = ts.tanim_id
 WHERE t.tur = 'KULLANICI_FONKSIYONU';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM senaryo s
        JOIN legacy_user_function_versions v ON v.id = s.tanim_surumu_id
    ) THEN
        RAISE EXCEPTION 'Yayınlanmış kullanıcı fonksiyonu senaryoları silinmeden migration uygulanamaz.';
    END IF;
END
$$;

-- These rows are immutable in normal application use. This bounded migration is the
-- only allowed removal path and runs only after proving no executable scenario exists.
DROP TRIGGER trg_tanim_bagimliligi_degismez ON tanim_bagimliligi;
DROP TRIGGER trg_tanim_surumu_degismez ON tanim_surumu;
DROP TRIGGER trg_tanim_veri_nesnesi_degismez ON tanim_veri_nesnesi;
DROP TRIGGER trg_dogrulama_degismez ON dogrulama;

DELETE FROM dogrulama d USING legacy_user_function_versions v
 WHERE d.tanim_surumu_id = v.id;
DELETE FROM tanim_veri_nesnesi tv USING legacy_user_function_versions v
 WHERE tv.tanim_surumu_id = v.id;
DELETE FROM tanim_bagimliligi b USING legacy_user_function_versions v
 WHERE b.kaynak_tanim_surumu_id = v.id OR b.hedef_tanim_surumu_id = v.id;
DELETE FROM tanim_taslagi d USING tanim t
 WHERE d.tanim_id = t.id AND t.tur = 'KULLANICI_FONKSIYONU';
DELETE FROM tanim_surumu s USING tanim t
 WHERE s.tanim_id = t.id AND t.tur = 'KULLANICI_FONKSIYONU';
DELETE FROM tanim WHERE tur = 'KULLANICI_FONKSIYONU';

CREATE TRIGGER trg_tanim_surumu_degismez
BEFORE UPDATE OR DELETE ON tanim_surumu
FOR EACH ROW EXECUTE FUNCTION degismez_tanim_kaydini_koru();
CREATE TRIGGER trg_tanim_bagimliligi_degismez
BEFORE UPDATE OR DELETE ON tanim_bagimliligi
FOR EACH ROW EXECUTE FUNCTION degismez_tanim_kaydini_koru();
CREATE TRIGGER trg_tanim_veri_nesnesi_degismez
BEFORE UPDATE OR DELETE ON tanim_veri_nesnesi
FOR EACH ROW EXECUTE FUNCTION degismez_katalog_kanitini_koru();
CREATE TRIGGER trg_dogrulama_degismez
BEFORE UPDATE OR DELETE ON dogrulama
FOR EACH ROW EXECUTE FUNCTION degismez_senaryo_kanitini_koru();

ALTER TABLE tanim DROP CONSTRAINT ck_tanim_sistem_turu;
ALTER TABLE tanim DROP CONSTRAINT ck_tanim_tur;

ALTER TABLE tanim ADD CONSTRAINT ck_tanim_sistem_turu CHECK (
    kapsam = 'PROJE' OR tur IN ('DEGISKEN', 'SEQUENCE', 'KNOWLEDGE_MODULE')
);
ALTER TABLE tanim ADD CONSTRAINT ck_tanim_tur CHECK (tur IN (
    'MAPPING', 'YENIDEN_KULLANILABILIR_MAPPING', 'PAKET', 'PROSEDUR',
    'DEGISKEN', 'SEQUENCE', 'KNOWLEDGE_MODULE', 'LOAD_PLAN'
));

SET search_path TO akis, public;

ALTER TABLE model
    ADD COLUMN teknoloji_kodu VARCHAR(40) NOT NULL DEFAULT 'ORACLE',
    ADD COLUMN tersine_muhendislik_ortam_id BIGINT,
    ADD COLUMN tersine_muhendislik_modu VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN rkm_tanim_id BIGINT,
    ADD COLUMN tersine_muhendislik_secenekleri JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE model
    ADD CONSTRAINT fk_model_reverse_ortam
        FOREIGN KEY (proje_id, tersine_muhendislik_ortam_id)
        REFERENCES ortam(proje_id, id),
    ADD CONSTRAINT fk_model_rkm
        FOREIGN KEY (rkm_tanim_id) REFERENCES tanim(id),
    ADD CONSTRAINT ck_model_teknoloji
        CHECK (teknoloji_kodu IN ('ORACLE')),
    ADD CONSTRAINT ck_model_reverse_modu
        CHECK (tersine_muhendislik_modu IN ('STANDARD', 'CUSTOM_RKM')),
    ADD CONSTRAINT ck_model_reverse_rkm
        CHECK ((tersine_muhendislik_modu = 'STANDARD' AND rkm_tanim_id IS NULL)
            OR (tersine_muhendislik_modu = 'CUSTOM_RKM' AND rkm_tanim_id IS NOT NULL)),
    ADD CONSTRAINT ck_model_reverse_secenekleri
        CHECK (jsonb_typeof(tersine_muhendislik_secenekleri) = 'object');

CREATE INDEX ix_model_reverse_ortam
    ON model(proje_id, tersine_muhendislik_ortam_id)
    WHERE arsivlenme_zamani IS NULL;

CREATE FUNCTION model_reverse_yapilandirmasini_dogrula() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_rkm_proje_id BIGINT;
    v_rkm_turu VARCHAR(40);
BEGIN
    IF NEW.rkm_tanim_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT proje_id, tur INTO v_rkm_proje_id, v_rkm_turu
      FROM akis.tanim WHERE id = NEW.rkm_tanim_id AND arsivlenme_zamani IS NULL;
    IF NOT FOUND OR v_rkm_turu <> 'KNOWLEDGE_MODULE'
       OR (v_rkm_proje_id IS NOT NULL AND v_rkm_proje_id <> NEW.proje_id) THEN
        RAISE EXCEPTION 'Model RKM tanımı etkin bir proje veya sistem Knowledge Module olmalıdır.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_model_reverse_yapilandirmasi
BEFORE INSERT OR UPDATE OF proje_id, rkm_tanim_id ON model
FOR EACH ROW EXECUTE FUNCTION model_reverse_yapilandirmasini_dogrula();

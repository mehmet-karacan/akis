SET search_path TO entegrasyon, public;

CREATE TABLE sema_goruntusu_oracle_kaniti (
    sema_goruntusu_id BIGINT NOT NULL,
    proje_id BIGINT NOT NULL,
    baglanti_id BIGINT NOT NULL,
    baglanti_surumu_id BIGINT NOT NULL,
    baglanti_surumu_testi_uuid UUID NOT NULL,
    hedef_kimlik_surumu INTEGER NOT NULL,
    hedef_parmak_izi TEXT NOT NULL,
    yakalama_sozlesmesi_surumu INTEGER NOT NULL,
    olusturulma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sema_goruntusu_oracle_kaniti PRIMARY KEY (sema_goruntusu_id),
    CONSTRAINT fk_sema_goruntusu_oracle_kaniti_snapshot
        FOREIGN KEY (proje_id, sema_goruntusu_id)
        REFERENCES sema_goruntusu(proje_id, id),
    CONSTRAINT fk_sema_goruntusu_oracle_kaniti_baglanti
        FOREIGN KEY (proje_id, baglanti_id)
        REFERENCES baglanti(proje_id, id),
    CONSTRAINT fk_sema_goruntusu_oracle_kaniti_surum
        FOREIGN KEY (proje_id, baglanti_surumu_id)
        REFERENCES baglanti_surumu(proje_id, id),
    CONSTRAINT fk_sema_goruntusu_oracle_kaniti_test
        FOREIGN KEY (
            proje_id, baglanti_id, baglanti_surumu_id,
            baglanti_surumu_testi_uuid)
        REFERENCES baglanti_surumu_testi(
            proje_id, baglanti_id, baglanti_surumu_id, uuid),
    CONSTRAINT ck_sema_goruntusu_oracle_kaniti_hedef_surumu
        CHECK (hedef_kimlik_surumu > 0),
    CONSTRAINT ck_sema_goruntusu_oracle_kaniti_parmak_izi
        CHECK (hedef_parmak_izi ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sema_goruntusu_oracle_kaniti_sozlesme
        CHECK (yakalama_sozlesmesi_surumu > 0)
);

CREATE INDEX ix_sema_goruntusu_oracle_kaniti_surum
    ON sema_goruntusu_oracle_kaniti(proje_id, baglanti_surumu_id, sema_goruntusu_id);

CREATE INDEX ix_sema_goruntusu_oracle_kaniti_test
    ON sema_goruntusu_oracle_kaniti(
        proje_id, baglanti_id, baglanti_surumu_id, baglanti_surumu_testi_uuid);

CREATE OR REPLACE FUNCTION fn_sema_goruntusu_oracle_kaniti_dogrula()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_snapshot_surum_id BIGINT;
    v_test_sonucu TEXT;
    v_test_hedef_surumu INTEGER;
    v_test_parmak_izi TEXT;
BEGIN
    SELECT baglanti_surumu_id
      INTO v_snapshot_surum_id
      FROM entegrasyon.sema_goruntusu
     WHERE proje_id = NEW.proje_id
       AND id = NEW.sema_goruntusu_id;

    IF v_snapshot_surum_id IS NULL
            OR v_snapshot_surum_id <> NEW.baglanti_surumu_id THEN
        RAISE EXCEPTION 'Oracle snapshot evidence does not match the snapshot connection version.';
    END IF;

    SELECT sonuc_kodu, hedef_kimlik_surumu, hedef_parmak_izi
      INTO v_test_sonucu, v_test_hedef_surumu, v_test_parmak_izi
      FROM entegrasyon.baglanti_surumu_testi
     WHERE proje_id = NEW.proje_id
       AND baglanti_id = NEW.baglanti_id
       AND baglanti_surumu_id = NEW.baglanti_surumu_id
       AND uuid = NEW.baglanti_surumu_testi_uuid;

    IF v_test_sonucu IS DISTINCT FROM 'PASSED'
            OR v_test_hedef_surumu IS DISTINCT FROM NEW.hedef_kimlik_surumu
            OR v_test_parmak_izi IS DISTINCT FROM NEW.hedef_parmak_izi THEN
        RAISE EXCEPTION 'Oracle snapshot evidence requires matching passed connection test evidence.';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_sema_goruntusu_oracle_kaniti_dogrula
BEFORE INSERT ON sema_goruntusu_oracle_kaniti
FOR EACH ROW EXECUTE FUNCTION fn_sema_goruntusu_oracle_kaniti_dogrula();

CREATE OR REPLACE FUNCTION fn_sema_goruntusu_oracle_kaniti_degistirilemez()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Oracle schema snapshot evidence is append-only.';
END;
$$;

CREATE TRIGGER tr_sema_goruntusu_oracle_kaniti_degistirilemez
BEFORE UPDATE OR DELETE ON sema_goruntusu_oracle_kaniti
FOR EACH ROW EXECUTE FUNCTION fn_sema_goruntusu_oracle_kaniti_degistirilemez();

-- uq_sema_goruntusu already provides the idempotency key for a captured shape:
-- (veri_nesnesi_id, fiziksel_sema_id, baglanti_surumu_id, parmak_izi).
-- A second equivalent unique index would only duplicate write and storage cost.

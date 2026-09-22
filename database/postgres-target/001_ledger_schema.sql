-- AKIS PostgreSQL target ledger (akis_yayin_defteri), installed once per TARGET database by a DBA.
-- Mirrors the Oracle ETL_KANIT_PKG tables: one fence row per canonical target, append-only batch and publish evidence.
-- Idempotent: re-running keeps existing rows and the installation identity.
CREATE SCHEMA IF NOT EXISTS akis_yayin_defteri;
SET search_path TO akis_yayin_defteri, public;

-- Installation identity: generated once, never regenerated; part of every canonical target identity read on this database.
CREATE TABLE IF NOT EXISTS kurulum_kimligi (
    bilesen_kodu VARCHAR(30) NOT NULL PRIMARY KEY CHECK (bilesen_kodu = 'AKIS_LEDGER'),
    kurulum_uuid UUID NOT NULL,
    sema_surumu INTEGER NOT NULL CHECK (sema_surumu > 0),
    sozlesme_ozeti CHAR(64) NOT NULL CHECK (sozlesme_ozeti ~ '^[0-9a-f]{64}$'),
    kurulum_zamani TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    kuran VARCHAR(128) NOT NULL DEFAULT current_user
);
INSERT INTO kurulum_kimligi(bilesen_kodu, kurulum_uuid, sema_surumu, sozlesme_ozeti)
VALUES ('AKIS_LEDGER', gen_random_uuid(), 1, encode(sha256('AKIS_POSTGRES_LEDGER_V1'::bytea), 'hex'))
ON CONFLICT (bilesen_kodu) DO NOTHING;

-- Target fence: monotonic token per canonical target; the owner run/attempt must match on every later ledger write.
CREATE TABLE IF NOT EXISTS hedef_citi (
    hedef_anahtar_ozeti CHAR(64) NOT NULL PRIMARY KEY CHECK (hedef_anahtar_ozeti ~ '^[0-9a-f]{64}$'),
    cit_belirteci BIGINT NOT NULL CHECK (cit_belirteci >= 1),
    sahip_is_uuid UUID NOT NULL,
    sahip_calistirma_uuid UUID NOT NULL,
    sahip_deneme_no INTEGER NOT NULL CHECK (sahip_deneme_no > 0),
    surum_ozeti CHAR(64) NOT NULL CHECK (surum_ozeti ~ '^[0-9a-f]{64}$'),
    plan_ozeti CHAR(64) NOT NULL CHECK (plan_ozeti ~ '^[0-9a-f]{64}$'),
    guncellenme_zamani TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Batch evidence (chunked transfers).
CREATE TABLE IF NOT EXISTS yukleme_defteri (
    hedef_anahtar_ozeti CHAR(64) NOT NULL CHECK (hedef_anahtar_ozeti ~ '^[0-9a-f]{64}$'),
    is_uuid UUID NOT NULL,
    adim_kodu VARCHAR(128) NOT NULL CHECK (adim_kodu ~ '^[A-Za-z0-9_.:-]+$'),
    bolum_kodu VARCHAR(128) NOT NULL CHECK (bolum_kodu ~ '^[A-Za-z0-9_.:-]+$'),
    parti_anahtar_ozeti CHAR(64) NOT NULL CHECK (parti_anahtar_ozeti ~ '^[0-9a-f]{64}$'),
    parti_no BIGINT NOT NULL CHECK (parti_no >= 0),
    calistirma_uuid UUID NOT NULL,
    deneme_no INTEGER NOT NULL CHECK (deneme_no > 0),
    cit_belirteci BIGINT NOT NULL CHECK (cit_belirteci >= 1),
    surum_ozeti CHAR(64) NOT NULL CHECK (surum_ozeti ~ '^[0-9a-f]{64}$'),
    plan_ozeti CHAR(64) NOT NULL CHECK (plan_ozeti ~ '^[0-9a-f]{64}$'),
    yuk_ozeti CHAR(64) NOT NULL CHECK (yuk_ozeti ~ '^[0-9a-f]{64}$'),
    satir_sayisi BIGINT NOT NULL CHECK (satir_sayisi >= 0),
    bayt_sayisi BIGINT NOT NULL CHECK (bayt_sayisi >= 0),
    kanit_zamani TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (hedef_anahtar_ozeti, is_uuid, adim_kodu, bolum_kodu, parti_anahtar_ozeti),
    UNIQUE (hedef_anahtar_ozeti, is_uuid, adim_kodu, bolum_kodu, parti_no)
);

-- Publish evidence (atomic target publication).
CREATE TABLE IF NOT EXISTS yayin_defteri (
    hedef_anahtar_ozeti CHAR(64) NOT NULL CHECK (hedef_anahtar_ozeti ~ '^[0-9a-f]{64}$'),
    is_uuid UUID NOT NULL,
    adim_kodu VARCHAR(128) NOT NULL CHECK (adim_kodu ~ '^[A-Za-z0-9_.:-]+$'),
    yayin_anahtar_ozeti CHAR(64) NOT NULL CHECK (yayin_anahtar_ozeti ~ '^[0-9a-f]{64}$'),
    calistirma_uuid UUID NOT NULL,
    deneme_no INTEGER NOT NULL CHECK (deneme_no > 0),
    cit_belirteci BIGINT NOT NULL CHECK (cit_belirteci >= 1),
    surum_ozeti CHAR(64) NOT NULL CHECK (surum_ozeti ~ '^[0-9a-f]{64}$'),
    plan_ozeti CHAR(64) NOT NULL CHECK (plan_ozeti ~ '^[0-9a-f]{64}$'),
    asama_ozeti CHAR(64) NOT NULL CHECK (asama_ozeti ~ '^[0-9a-f]{64}$'),
    asama_satir_sayisi BIGINT NOT NULL CHECK (asama_satir_sayisi >= 0),
    yayinlanan_satir_sayisi BIGINT NOT NULL CHECK (yayinlanan_satir_sayisi >= 0),
    reddedilen_satir_sayisi BIGINT NOT NULL CHECK (reddedilen_satir_sayisi >= 0),
    alt_isaret VARCHAR(1000),
    ust_isaret VARCHAR(1000),
    kanit_zamani TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (hedef_anahtar_ozeti, is_uuid, adim_kodu, yayin_anahtar_ozeti),
    CHECK ((alt_isaret IS NULL AND ust_isaret IS NULL) OR (alt_isaret IS NOT NULL AND ust_isaret IS NOT NULL))
);

-- Evidence is append-only: nothing edits or deletes a recorded marker.
CREATE OR REPLACE FUNCTION kanit_degistirilemez() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Yayın defteri kanıtı değiştirilemez.' USING ERRCODE = 'AK099'; END $$;
DROP TRIGGER IF EXISTS trg_yukleme_defteri_sabit ON yukleme_defteri;
CREATE TRIGGER trg_yukleme_defteri_sabit BEFORE UPDATE OR DELETE ON yukleme_defteri FOR EACH ROW EXECUTE FUNCTION kanit_degistirilemez();
DROP TRIGGER IF EXISTS trg_yayin_defteri_sabit ON yayin_defteri;
CREATE TRIGGER trg_yayin_defteri_sabit BEFORE UPDATE OR DELETE ON yayin_defteri FOR EACH ROW EXECUTE FUNCTION kanit_degistirilemez();
DROP TRIGGER IF EXISTS trg_kurulum_kimligi_sabit ON kurulum_kimligi;
CREATE TRIGGER trg_kurulum_kimligi_sabit BEFORE UPDATE OR DELETE ON kurulum_kimligi FOR EACH ROW EXECUTE FUNCTION kanit_degistirilemez();
